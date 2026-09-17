package eu.wohlben.qits.cli.access.session;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Files in the qits config directory: the directory is 0700, each file 0600, and a file is replaced
 * atomically. {@code t.json} and {@code git.json} both hold tokens, so both go through here.
 */
public final class PrivateFiles {

    private PrivateFiles() {
    }

    public static void ensureDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            Files.createDirectories(directory,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
        // createDirectories applies the umask, and an existing directory may be wider.
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    }

    /**
     * A temporary file in the same directory, fsync, rename, then fsync of the directory so the
     * rename itself survives a crash. A reader never sees half a file. The caller holds the file's
     * write lock.
     */
    public static void writeAtomically(Path directory, String fileName, byte[] bytes) throws IOException {
        ensureDirectory(directory);
        Path target = directory.resolve(fileName);
        Path temporary = Files.createTempFile(directory, fileName + ".", ".tmp",
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
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("cannot replace " + target + " atomically", e);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        syncDirectory(directory);
    }

    private static void syncDirectory(Path directory) {
        try (FileChannel dir = FileChannel.open(directory, StandardOpenOption.READ)) {
            dir.force(true);
        } catch (IOException ignored) {
            // Not every file system lets a directory be opened for this. The rename is done.
        }
    }
}
