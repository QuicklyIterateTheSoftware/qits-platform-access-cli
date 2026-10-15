package eu.wohlben.qits.cli.access.update;

import eu.wohlben.qits.cli.access.platform.CliFailure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One check and one replacement, in a temporary directory, with shell scripts standing in for the
 * running binary and for the release. The probe is the real one: it runs the script with {@code -V}.
 */
class SelfUpdateTest {

    private static final String RUNNING = "2026.919.40352";
    private static final String LATEST = "2026.1015.175513";

    @TempDir
    Path home;

    private Path bin;
    private Path self;
    private Path staging;
    private FakeReleases releases;

    @BeforeEach
    void setUp() throws IOException {
        bin = Files.createDirectories(home.resolve("bin"));
        self = bin.resolve("qits");
        Files.writeString(self, script(RUNNING));
        Files.setPosixFilePermissions(self, PosixFilePermissions.fromString("rwxr-xr-x"));
        staging = home.resolve("cache");
        releases = new FakeReleases(LATEST, script(LATEST).getBytes(StandardCharsets.UTF_8));
    }

    private static String script(String version) {
        return "#!/bin/sh\necho " + version + "\n";
    }

    private SelfUpdate update(Optional<String> running) {
        return new SelfUpdate(releases, running, Optional.of(self), staging, SelfUpdate::probe);
    }

    private SelfUpdate update() {
        return update(Optional.of(RUNNING));
    }

    private void assertOldBinaryAndNoLeftovers() throws IOException {
        assertThat(Files.readString(self)).isEqualTo(script(RUNNING));
        try (Stream<Path> files = Files.list(bin)) {
            assertThat(files).containsExactly(self);
        }
    }

    @Test
    void aNewerReleaseReplacesTheBinaryInPlace() throws Exception {
        SelfUpdate.Outcome outcome = update().run(false, false);

        assertThat(outcome).isEqualTo(new SelfUpdate.Outcome(SelfUpdate.Result.UPDATED,
                "Updated qits from 2026.919.40352 to 2026.1015.175513."));
        assertThat(Files.readString(self)).isEqualTo(script(LATEST));
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(self))).isEqualTo("rwxr-xr-x");
        try (Stream<Path> files = Files.list(bin)) {
            assertThat(files).as("no temporary file is left").containsExactly(self);
        }
        assertThat(SelfUpdate.probe(self).strip()).isEqualTo(LATEST);
    }

    @Test
    void theSameVersionIsUpToDateAndDownloadsNothing() throws Exception {
        releases.latest = RUNNING;

        assertThat(update().run(false, false)).isEqualTo(new SelfUpdate.Outcome(SelfUpdate.Result.UP_TO_DATE,
                "qits is up to date (2026.919.40352)."));
        assertThat(releases.downloads).isZero();
        assertOldBinaryAndNoLeftovers();
    }

    @Test
    void anOlderReleaseIsNoUpdate() throws Exception {
        releases.latest = "2026.918.235959";

        assertThat(update().run(false, false).result()).isEqualTo(SelfUpdate.Result.UP_TO_DATE);
        assertOldBinaryAndNoLeftovers();
    }

    @Test
    void aCheckOnlySaysSoAndDownloadsNothing() throws Exception {
        SelfUpdate.Outcome outcome = update().run(true, false);

        assertThat(outcome).isEqualTo(new SelfUpdate.Outcome(SelfUpdate.Result.AVAILABLE,
                "qits 2026.1015.175513 is released; this is 2026.919.40352. Run `qits update` to install it."));
        assertThat(releases.downloads).isZero();
        assertOldBinaryAndNoLeftovers();
    }

    @Test
    void aBuildThatDoesNotKnowItsVersionNeverUpdatesNorAsks() throws Exception {
        SelfUpdate.Outcome outcome = update(Optional.empty()).run(false, false);

        assertThat(outcome.result()).isEqualTo(SelfUpdate.Result.UNKNOWN_VERSION);
        assertThat(outcome.done()).isFalse();
        assertThat(releases.asked).as("the store is not even asked").isZero();
        assertOldBinaryAndNoLeftovers();
    }

    @Test
    void aJvmIsNotTheBinaryAndReplacesNothing() throws Exception {
        SelfUpdate.Outcome outcome = new SelfUpdate(releases, Optional.of(RUNNING), Optional.empty(), staging,
                SelfUpdate::probe).run(false, false);

        assertThat(outcome.result()).isEqualTo(SelfUpdate.Result.NOT_BINARY);
        assertThat(releases.downloads).isZero();
    }

    @Test
    void aDigestMismatchKeepsTheOldBinary() throws Exception {
        releases.announcedSha256 = "0".repeat(64);

        assertThatThrownBy(() -> update().run(false, false)).isInstanceOf(CliFailure.class)
                .hasMessage("The download of qits 2026.1015.175513 does not have the sha256 digest the store "
                        + "announced. Nothing was replaced.");
        assertOldBinaryAndNoLeftovers();
    }

    @Test
    void aSizeMismatchKeepsTheOldBinary() throws Exception {
        releases.served = "#!/bin/sh\necho 2026.10".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> update().run(false, false)).isInstanceOf(CliFailure.class)
                .hasMessageStartingWith("The download of qits 2026.1015.175513 is 22 bytes, not the ")
                .hasMessageEndingWith(" the store announced. Nothing was replaced.");
        assertOldBinaryAndNoLeftovers();
    }

    @Test
    void aBrokenDownloadKeepsTheOldBinary() throws Exception {
        releases.failDownload = true;

        assertThatThrownBy(() -> update().run(false, false)).isInstanceOf(CliFailure.class)
                .hasMessageContaining("Cannot download");
        assertOldBinaryAndNoLeftovers();
    }

    @Test
    void aDownloadThatAnswersAnotherVersionKeepsTheOldBinary() throws Exception {
        releases.serve(script("2026.1015.1\u001b[31m"));

        assertThatThrownBy(() -> update().run(false, false)).isInstanceOf(CliFailure.class)
                .hasMessage("The downloaded qits 2026.1015.175513 says its version is '2026.1015.1', not "
                        + "2026.1015.175513. Nothing was replaced.");
        assertOldBinaryAndNoLeftovers();
    }

    @Test
    void aDownloadThatDoesNotRunKeepsTheOldBinary() throws Exception {
        releases.serve("#!/bin/sh\nexit 3\n");

        assertThatThrownBy(() -> update().run(false, false)).isInstanceOf(CliFailure.class)
                .hasMessage("The downloaded qits 2026.1015.175513 does not run (IOException (-V exited with 3)). "
                        + "Nothing was replaced.");
        assertOldBinaryAndNoLeftovers();
    }

    @Test
    void aDirectoryThatIsNotWritableGetsTheCheckedDownloadAndTheCommandToInstallIt() throws Exception {
        Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            SelfUpdate.Outcome outcome = update().run(false, false);

            Path kept = staging.resolve("qits-" + LATEST);
            assertThat(outcome.result()).isEqualTo(SelfUpdate.Result.NOT_WRITABLE);
            assertThat(outcome.done()).isFalse();
            assertThat(outcome.message().lines()).containsExactly(
                    "qits 2026.1015.175513 is released, but " + bin + " is not writable, so qits 2026.919.40352 "
                            + "cannot replace itself.",
                    "The new version is downloaded and checked. Install it with:",
                    "  sudo install -m 755 " + kept + " " + self);
            assertThat(Files.readString(kept)).isEqualTo(script(LATEST));
            assertThat(Files.isExecutable(kept)).isTrue();
            try (Stream<Path> files = Files.list(staging)) {
                assertThat(files).containsExactly(kept);
            }
        } finally {
            Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        assertOldBinaryAndNoLeftovers();
    }

    @Test
    void inTheBackgroundADirectoryThatIsNotWritableIsOneLineAndNoDownload() throws Exception {
        Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            SelfUpdate.Outcome outcome = update().run(false, true);

            assertThat(outcome.result()).isEqualTo(SelfUpdate.Result.NOT_WRITABLE);
            assertThat(outcome.message().lines()).hasSize(1);
            assertThat(outcome.message()).contains(bin.toString()).contains("Run `qits update`");
            assertThat(releases.downloads).isZero();
            assertThat(staging).doesNotExist();
        } finally {
            Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    /** The store in memory: what it names as latest, what it announces and what it serves. */
    static final class FakeReleases implements Releases {
        String latest;
        byte[] served;
        String announcedSha256;
        Long announcedSize;
        boolean failDownload;
        int asked;
        int downloads;

        FakeReleases(String latest, byte[] served) {
            this.latest = latest;
            serve(served);
        }

        void serve(String text) {
            serve(text.getBytes(StandardCharsets.UTF_8));
        }

        /** Serves these bytes and announces exactly them; a test then breaks one side. */
        void serve(byte[] bytes) {
            served = bytes;
            announcedSize = (long) bytes.length;
            try {
                Path scratch = Files.createTempFile("fake-release", ".bin");
                Files.write(scratch, bytes);
                announcedSha256 = SelfUpdate.sha256(scratch);
                Files.delete(scratch);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public String latest() {
            asked++;
            return latest;
        }

        @Override
        public Published describe(String version) {
            return new Published(announcedSize, announcedSha256);
        }

        @Override
        public void download(String version, Path target) throws CliFailure {
            downloads++;
            if (failDownload) {
                try {
                    Files.write(target, new byte[] {1, 2, 3});
                } catch (IOException ignored) {
                    // The failure below is the point.
                }
                throw CliFailure.retryable("Cannot download " + address(version) + ": IOException (reset)");
            }
            try {
                Files.write(target, served);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public URI address(String version) {
            return URI.create("https://registry.qits.example.eu/artifacts/daemons/qits-platform-access-cli/" + version);
        }
    }
}
