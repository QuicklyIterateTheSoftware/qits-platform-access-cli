package eu.wohlben.qits.cli.access.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

/**
 * The session file {@code t.json} and the two lock files beside it.
 * <p>
 * <b>Two locks, because one cannot do both jobs.</b> {@code t.json.lock} is the WRITE lock: it is
 * held for a short time around every read-refresh-write of the daemon and around the write of
 * {@code qits login}, so only one process spends a refresh token at a time. {@code
 * session-daemon.lock} is held for a daemon's whole life, and keeps a second daemon out. A daemon
 * that held the write lock for life would block {@code qits login} forever; a daemon that held it
 * only briefly would let a second daemon in.
 */
public final class SessionFile {

    public static final String FILE_NAME = "t.json";

    private static final ObjectMapper JSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    private final Path directory;

    public SessionFile(Path directory) {
        this.directory = directory;
    }

    /**
     * {@code $XDG_CONFIG_HOME/qits}, else {@code ~/.config/qits}. A relative XDG_CONFIG_HOME is
     * ignored, as the XDG specification says.
     */
    public static SessionFile fromEnvironment(Map<String, String> env) {
        String xdg = env.get("XDG_CONFIG_HOME");
        Path base = xdg != null && !xdg.isBlank() && Path.of(xdg).isAbsolute()
                ? Path.of(xdg)
                : Path.of(System.getProperty("user.home"), ".config");
        return new SessionFile(base.resolve("qits"));
    }

    public Path directory() {
        return directory;
    }

    public Path path() {
        return directory.resolve(FILE_NAME);
    }

    public Path writeLockPath() {
        return directory.resolve(FILE_NAME + ".lock");
    }

    public Path daemonLockPath() {
        return directory.resolve("session-daemon.lock");
    }

    /** The write lock. Waits while another process holds it. */
    public ExclusiveLock lockForWrite() throws IOException, InterruptedException {
        ensureDirectory();
        return ExclusiveLock.acquire(writeLockPath());
    }

    /** The daemon's lifetime lock; empty when another daemon holds it. */
    public Optional<ExclusiveLock> tryLockForDaemon() throws IOException {
        ensureDirectory();
        return ExclusiveLock.tryAcquire(daemonLockPath());
    }

    /** The session, or empty when there is no file. A file that does not parse throws. */
    public Optional<Session> read() throws IOException {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path());
        } catch (NoSuchFileException absent) {
            return Optional.empty();
        }
        Session session;
        try {
            session = JSON.readValue(bytes, Session.class);
        } catch (IOException e) {
            // Jackson's message may quote the content, and the content holds tokens.
            throw new IOException(path() + " is not a valid session file");
        }
        if (session == null || blank(session.idpUrl()) || blank(session.accessToken())
                || blank(session.refreshToken()) || session.accessExpiresAt() == null
                || session.refreshExpiresAt() == null) {
            throw new IOException(path() + " is missing a field");
        }
        return Optional.of(session);
    }

    /**
     * Replaces the file atomically: a temporary file in the same directory, fsync, rename, then
     * fsync of the directory so the rename itself survives a crash. The caller holds the write
     * lock.
     */
    public void write(Session session) throws IOException {
        ensureDirectory();
        byte[] bytes = JSON.writeValueAsBytes(session);
        Path temporary = Files.createTempFile(directory, FILE_NAME + ".", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temporary, path(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("cannot replace " + path() + " atomically", e);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        syncDirectory();
    }

    /**
     * What the file looks like now: absent, or its size, mtime and a hash of its content. The
     * daemon polls this to see a new session; the content hash catches a replace that keeps the
     * size and lands in the same mtime tick.
     */
    public Fingerprint fingerprint() {
        try {
            byte[] bytes = Files.readAllBytes(path());
            long mtime = Files.getLastModifiedTime(path()).toMillis();
            return new Fingerprint(true, bytes.length, mtime, sha256(bytes));
        } catch (IOException absent) {
            return Fingerprint.ABSENT;
        }
    }

    public record Fingerprint(boolean exists, long size, long mtimeMillis, byte[] sha256) {
        static final Fingerprint ABSENT = new Fingerprint(false, -1, -1, new byte[0]);

        @Override
        public boolean equals(Object other) {
            return other instanceof Fingerprint that && exists == that.exists && size == that.size
                    && mtimeMillis == that.mtimeMillis && Arrays.equals(sha256, that.sha256);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(sha256);
        }

        @Override
        public String toString() {
            return exists ? "Fingerprint[size=" + size + ", mtime=" + mtimeMillis + "]" : "Fingerprint[absent]";
        }
    }

    private void ensureDirectory() throws IOException {
        if (!Files.isDirectory(directory)) {
            Files.createDirectories(directory,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
        // createDirectories applies the umask, and an existing directory may be wider.
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    }

    private void syncDirectory() {
        try (FileChannel dir = FileChannel.open(directory, StandardOpenOption.READ)) {
            dir.force(true);
        } catch (IOException ignored) {
            // Not every file system lets a directory be opened for this. The rename is done.
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
        }
    }
}
