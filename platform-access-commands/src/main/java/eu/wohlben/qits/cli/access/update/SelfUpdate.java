package eu.wohlben.qits.cli.access.update;

import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliFailure;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * One check, and when a newer version is released, one replacement of the running binary.
 * <p>
 * The new file is written beside the binary under a temporary name, so the last step is a rename in
 * one directory, which is atomic: a command started at any moment runs either the old binary or the
 * new one, never half of either. Before that rename the file has to have the length and the sha256
 * digest the store announced, and has to answer {@code -V} with the version it was downloaded as.
 * Any failure on the way deletes the temporary file and leaves the old binary as it was.
 * <p>
 * Every part of the world is a seam (the store, the running binary's path, the {@code -V} probe), so
 * the tests run it in a temporary directory with a script standing in for the binary.
 */
final class SelfUpdate {

    /** What the run came to. Failures are not outcomes; they are a {@link CliFailure}. */
    enum Result {
        /** The running version is the newest. */
        UP_TO_DATE,
        /** A newer version is released, and only a check was asked for. */
        AVAILABLE,
        /** The binary was replaced. */
        UPDATED,
        /** This build does not know its version. */
        UNKNOWN_VERSION,
        /** This process is not the native binary (a JVM: tests, the dev loop). */
        NOT_BINARY,
        /** The newer version is downloaded and checked, but the binary's directory cannot be written. */
        NOT_WRITABLE
    }

    /** @param message what to tell the person, one or more lines */
    record Outcome(Result result, String message) {
        boolean done() {
            return result == Result.UP_TO_DATE || result == Result.AVAILABLE || result == Result.UPDATED;
        }
    }

    /** Runs a binary with {@code -V} and gives what it printed on stdout. */
    @FunctionalInterface
    interface VersionProbe {
        String versionOf(Path binary) throws IOException, InterruptedException;
    }

    /** How long a downloaded binary has to answer {@code -V}. */
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);

    private final Releases releases;
    private final Optional<String> running;
    private final Optional<Path> binary;
    private final Path staging;
    private final VersionProbe probe;

    /**
     * @param running the running version; empty when the build does not know it
     * @param binary  the running binary, symlinks resolved; empty when this is not the binary
     * @param staging where a checked download is left when the binary's directory cannot be written
     */
    SelfUpdate(Releases releases, Optional<String> running, Optional<Path> binary, Path staging, VersionProbe probe) {
        this.releases = releases;
        this.running = running;
        this.binary = binary;
        this.staging = staging;
        this.probe = probe;
    }

    /**
     * @param checkOnly  only say whether a newer version is released
     * @param background the automatic run: where the directory is not writable it says so in one
     *                   line and downloads nothing, rather than fetching tens of megabytes an hour
     *                   for a person who has not asked
     */
    Outcome run(boolean checkOnly, boolean background) throws CliFailure, InterruptedException {
        if (running.isEmpty()) {
            return new Outcome(Result.UNKNOWN_VERSION,
                    "This build of qits does not know its version, so it does not update itself.");
        }
        String from = running.get();
        String latest = releases.latest();
        if (!QitsVersion.newer(latest, from)) {
            return new Outcome(Result.UP_TO_DATE, "qits is up to date (" + from + ").");
        }
        if (checkOnly) {
            return new Outcome(Result.AVAILABLE,
                    "qits " + latest + " is released; this is " + from + ". Run `qits update` to install it.");
        }
        if (binary.isEmpty()) {
            return new Outcome(Result.NOT_BINARY, "qits " + latest + " is released, but this qits runs in a JVM, "
                    + "not as the binary, so it does not replace itself.");
        }
        Path self = binary.get();
        Path directory = self.getParent();
        // The rename needs the directory, not the file: a binary installed read-only is replaced too.
        boolean writable = directory != null && Files.isWritable(directory);
        if (!writable && background) {
            return new Outcome(Result.NOT_WRITABLE, "qits " + latest + " is released, but " + directory
                    + " is not writable, so qits " + from + " cannot replace itself. Run `qits update` to see how "
                    + "to install it.");
        }
        Path into = writable ? directory : staging;
        Path checked = fetch(latest, into);
        if (writable) {
            try {
                Files.move(checked, self, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                deleteQuietly(checked);
                throw new CliFailure("Cannot replace " + self + ": " + describe(e) + ". qits " + from
                        + " is still installed.", CliFailure.FAILED);
            }
            return new Outcome(Result.UPDATED, "Updated qits from " + from + " to " + latest + ".");
        }
        Path kept = staging.resolve("qits-" + latest);
        try {
            Files.move(checked, kept, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            deleteQuietly(checked);
            throw new CliFailure("Cannot keep the download in " + staging + ": " + describe(e) + ".", CliFailure.FAILED);
        }
        return new Outcome(Result.NOT_WRITABLE, String.join(System.lineSeparator(), List.of(
                "qits " + latest + " is released, but " + directory + " is not writable, so qits " + from
                        + " cannot replace itself.",
                "The new version is downloaded and checked. Install it with:",
                "  sudo install -m 755 " + kept + " " + self)));
    }

    /**
     * {@code version}'s file, downloaded into {@code directory} under a temporary name and checked:
     * length, digest, mode 755 and its own {@code -V}. On any failure nothing is left behind.
     */
    private Path fetch(String version, Path directory) throws CliFailure, InterruptedException {
        Releases.Published published = releases.describe(version);
        Path temporary;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, ".qits-update-", ".tmp");
        } catch (IOException e) {
            throw new CliFailure("Cannot write in " + directory + ": " + describe(e) + ".", CliFailure.FAILED);
        }
        boolean kept = false;
        try {
            releases.download(version, temporary);
            long size = Files.size(temporary);
            if (size != published.size()) {
                throw new CliFailure("The download of qits " + version + " is " + size + " bytes, not the "
                        + published.size() + " the store announced. Nothing was replaced.", CliFailure.FAILED);
            }
            String digest = sha256(temporary);
            if (!digest.equals(published.sha256())) {
                throw new CliFailure("The download of qits " + version + " does not have the sha256 digest the "
                        + "store announced. Nothing was replaced.", CliFailure.FAILED);
            }
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rwxr-xr-x"));
            String said;
            try {
                said = probe.versionOf(temporary);
            } catch (IOException e) {
                throw new CliFailure("The downloaded qits " + version + " does not run (" + describe(e)
                        + "). Nothing was replaced.", CliFailure.FAILED);
            }
            String answer = said == null ? "" : said.strip();
            if (!answer.equals(version)) {
                String shown = SafeText.line(answer);
                if (shown.length() > 80) {
                    shown = shown.substring(0, 80) + "...";
                }
                throw new CliFailure("The downloaded qits " + version + " says its version is '" + shown
                        + "', not " + version + ". Nothing was replaced.", CliFailure.FAILED);
            }
            kept = true;
            return temporary;
        } catch (IOException e) {
            throw new CliFailure("Cannot check the download of qits " + version + ": " + describe(e)
                    + ". Nothing was replaced.", CliFailure.FAILED);
        } finally {
            if (!kept) {
                deleteQuietly(temporary);
            }
        }
    }

    /** {@code binary -V}'s stdout, within {@link #PROBE_TIMEOUT}; a non-zero exit is a failure. */
    static String probe(Path binary) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(binary.toString(), "-V")
                .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        // Waited for before stdout is read: a binary that hangs would otherwise block the read for
        // ever. -V prints one short line, which the pipe holds until it is read.
        if (!process.waitFor(PROBE_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("-V did not answer within " + PROBE_TIMEOUT.toSeconds() + " seconds");
        }
        byte[] out;
        try (InputStream stdout = process.getInputStream()) {
            out = stdout.readNBytes(4096);
        }
        if (process.exitValue() != 0) {
            throw new IOException("-V exited with " + process.exitValue());
        }
        return new String(out, StandardCharsets.UTF_8);
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("this JDK has no SHA-256", impossible);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A temporary name nobody runs; the next update writes another.
        }
    }

    private static String describe(Exception e) {
        if (e instanceof AtomicMoveNotSupportedException) {
            return "the file system cannot rename it in one step";
        }
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : " (" + message + ")");
    }
}
