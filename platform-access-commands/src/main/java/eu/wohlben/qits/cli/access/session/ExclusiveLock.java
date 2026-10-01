package eu.wohlben.qits.cli.access.session;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * An exclusive lock on a file, held between {@code qits} processes and between threads.
 * <p>
 * <b>Between processes it is an fcntl record lock, not flock(2).</b> That is what
 * {@link FileChannel#lock()} takes on Linux. So the shell's {@code flock} tool does not see it.
 * <p>
 * <b>Two traps of fcntl locks, and how this class avoids them:</b>
 * <ul>
 *   <li>Inside one JVM a second, overlapping lock throws instead of waiting. So a per-path
 *       semaphore comes first, and threads wait on it.</li>
 *   <li>The kernel drops the whole process's lock when ANY descriptor of the file is closed. So
 *       the file is opened only after the semaphore is taken, and closed only on release. Nothing
 *       else in this program opens a lock file.</li>
 * </ul>
 */
public final class ExclusiveLock implements AutoCloseable {

    private static final Map<Path, Semaphore> IN_PROCESS = new ConcurrentHashMap<>();

    private final Semaphore gate;
    private final FileChannel channel;
    private final FileLock lock;

    private ExclusiveLock(Semaphore gate, FileChannel channel, FileLock lock) {
        this.gate = gate;
        this.channel = channel;
        this.lock = lock;
    }

    /** Waits until the lock is free, then takes it. */
    public static ExclusiveLock acquire(Path file) throws IOException, InterruptedException {
        Semaphore gate = gate(file);
        gate.acquire();
        FileChannel channel = null;
        try {
            channel = open(file);
            return new ExclusiveLock(gate, channel, channel.lock());
        } catch (IOException | RuntimeException e) {
            closeQuietly(channel);
            gate.release();
            throw e;
        }
    }

    /** Takes the lock if it is free now; empty when someone else holds it. */
    public static Optional<ExclusiveLock> tryAcquire(Path file) throws IOException {
        Semaphore gate = gate(file);
        if (!gate.tryAcquire()) {
            return Optional.empty();
        }
        FileChannel channel = null;
        try {
            channel = open(file);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                closeQuietly(channel);
                gate.release();
                return Optional.empty();
            }
            return Optional.of(new ExclusiveLock(gate, channel, lock));
        } catch (IOException | RuntimeException e) {
            closeQuietly(channel);
            gate.release();
            throw e;
        }
    }

    @Override
    public void close() {
        try {
            lock.release();
        } catch (IOException ignored) {
            // Closing the channel below releases it too.
        }
        closeQuietly(channel);
        gate.release();
    }

    private static Semaphore gate(Path file) {
        return IN_PROCESS.computeIfAbsent(file.toAbsolutePath().normalize(), p -> new Semaphore(1));
    }

    /** A lock file is created 0600, like every file in the session directory. */
    private static FileChannel open(Path file) throws IOException {
        return FileChannel.open(file, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // Nothing to do: the process keeps no lock through a closed channel.
            }
        }
    }
}
