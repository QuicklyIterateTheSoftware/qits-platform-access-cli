package eu.wohlben.qits.cli.access.registry;

import eu.wohlben.qits.cli.access.daemon.SessionListener;
import eu.wohlben.qits.cli.access.session.ExclusiveLock;
import eu.wohlben.qits.cli.access.session.PrivateFiles;
import eu.wohlben.qits.cli.access.session.Session;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Writes the registry blocks of {@link ManagedBlocks} into the files {@link RegistryState} names,
 * and is the session daemon's hook for the same.
 * <p>
 * Each file is replaced atomically (temporary file beside it, fsync, rename) with mode 0600, since
 * it holds a bearer. A symbolic link is followed, so a dotfiles link stays a link. A file that
 * would not change is not written.
 */
public final class RegistryLogins implements SessionListener {

    /**
     * How long before expiry the daemon refreshes while registry logins are set up. npm and Maven
     * read the token once, when they start, so it has to outlive a build's start by a margin. The
     * idp gives 15-minute access tokens, so this means a refresh every 10 minutes.
     */
    public static final Duration MARGIN = Duration.ofMinutes(5);

    private final Path configDirectory;

    public RegistryLogins(Path configDirectory) {
        this.configDirectory = configDirectory;
    }

    /** One file's outcome. {@code diff} is redacted, and empty when nothing changes. */
    public record FileChange(Path path, boolean changed, String diff, List<String> warnings) {
    }

    public Optional<RegistryState> state() throws IOException {
        return RegistryState.read(configDirectory);
    }

    public void saveState(RegistryState state) throws IOException {
        try (ExclusiveLock ignored = lock()) {
            state.write(configDirectory);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    /** Writes both files, or with {@code dryRun} only says what would change. */
    public List<FileChange> apply(RegistryState state, String token, boolean removeStale, boolean dryRun)
            throws IOException, InterruptedException {
        try (ExclusiveLock ignored = lock()) {
            Path npmrc = Path.of(state.npmrc());
            Path settings = Path.of(state.mavenSettings());
            String npmBefore = read(npmrc);
            String mavenBefore = read(settings);
            ManagedBlocks.Result npm = ManagedBlocks.npmrc(npmBefore, state.npmRegistries(), token, removeStale);
            ManagedBlocks.Result maven;
            try {
                maven = ManagedBlocks.mavenSettings(mavenBefore, state.mavenUrl(), token, removeStale);
            } catch (IllegalArgumentException notSettings) {
                throw new IOException(settings + " is not a Maven settings file: " + notSettings.getMessage());
            }
            List<FileChange> changes = new ArrayList<>();
            changes.add(change(npmrc, npmBefore, npm, dryRun));
            changes.add(change(settings, mavenBefore, maven, dryRun));
            return changes;
        }
    }

    private static FileChange change(Path path, String before, ManagedBlocks.Result after, boolean dryRun)
            throws IOException {
        boolean changed = !before.equals(after.text());
        String diff = UnifiedDiff.of(path.toString(), before, after.text(), ManagedBlocks::redact);
        if (changed && !dryRun) {
            write(path, after.text());
        }
        return new FileChange(path, changed, diff, after.warnings());
    }

    // ---------------------------------------------------------------- the daemon's hook

    @Override
    public String accessTokenChanged(Session session) throws IOException, InterruptedException {
        Optional<RegistryState> state = state();
        if (state.isEmpty()) {
            return null;
        }
        List<String> written = apply(state.get(), session.accessToken(), false, false).stream()
                .filter(FileChange::changed).map(c -> c.path().toString()).toList();
        return written.isEmpty() ? null : "registry logins updated in " + String.join(" and ", written);
    }

    @Override
    public Duration minimumMargin() {
        return Files.exists(configDirectory.resolve(RegistryState.FILE_NAME)) ? MARGIN : Duration.ZERO;
    }

    // ---------------------------------------------------------------- files

    private ExclusiveLock lock() throws IOException, InterruptedException {
        PrivateFiles.ensureDirectory(configDirectory);
        return ExclusiveLock.acquire(configDirectory.resolve(RegistryState.FILE_NAME + ".lock"));
    }

    private static String read(Path path) throws IOException {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (NoSuchFileException absent) {
            return "";
        }
    }

    static void write(Path path, String text) throws IOException {
        Path target = Files.isSymbolicLink(path) ? path.toRealPath() : path.toAbsolutePath();
        Path directory = target.getParent();
        if (!Files.isDirectory(directory)) {
            Files.createDirectories(directory,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
        Path temporary = Files.createTempFile(directory, target.getFileName() + ".", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
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
    }
}
