package eu.wohlben.qits.cli.access.session;

import eu.wohlben.qits.cli.access.LockHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionFileTest {

    @TempDir
    Path home;

    private static Session session(int n) {
        return new Session("https://idp.dev.wohlben.eu/idp", "qits-cli", "SECRET-access-" + n,
                Instant.parse("2026-09-11T20:15:00Z"), "SECRET-refresh-" + n, Instant.parse("2026-10-11T20:00:00Z"));
    }

    @Test
    void theLocationFollowsXdgConfigHome() {
        assertThat(SessionFile.fromEnvironment(Map.of("XDG_CONFIG_HOME", "/tmp/x")).path())
                .isEqualTo(Path.of("/tmp/x/qits/t.json"));
        assertThat(SessionFile.fromEnvironment(Map.of("XDG_CONFIG_HOME", "relative")).path())
                .isEqualTo(Path.of(System.getProperty("user.home"), ".config", "qits", "t.json"));
        assertThat(SessionFile.fromEnvironment(Map.of()).path())
                .isEqualTo(Path.of(System.getProperty("user.home"), ".config", "qits", "t.json"));
    }

    @Test
    void theFileHoldsAbsoluteTimesAndOnlyItsOwnerMayReadIt() throws Exception {
        SessionFile store = new SessionFile(home.resolve("qits"));
        store.write(session(1));

        assertThat(Files.readString(store.path()))
                .contains("\"idpUrl\" : \"https://idp.dev.wohlben.eu/idp\"")
                .contains("\"clientId\" : \"qits-cli\"")
                .contains("\"accessExpiresAt\" : \"2026-09-11T20:15:00Z\"")
                .contains("\"refreshExpiresAt\" : \"2026-10-11T20:00:00Z\"");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(store.path()))).isEqualTo("rw-------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(store.directory()))).isEqualTo("rwx------");
        assertThat(store.read()).contains(session(1));
    }

    @Test
    void anExistingWideDirectoryIsTightened() throws Exception {
        Path dir = Files.createDirectories(home.resolve("qits"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        new SessionFile(dir).write(session(1));
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir))).isEqualTo("rwx------");
    }

    /** A reader never sees half a file, and a replace leaves no temporary file behind. */
    @Test
    void aReplaceIsAtomic() throws Exception {
        SessionFile store = new SessionFile(home.resolve("qits"));
        store.write(session(0));
        AtomicBoolean done = new AtomicBoolean();
        AtomicInteger reads = new AtomicInteger();
        Thread reader = Thread.ofPlatform().start(() -> {
            while (!done.get()) {
                try {
                    assertThat(store.read()).isPresent();
                    reads.incrementAndGet();
                } catch (Exception e) {
                    throw new AssertionError("a reader saw a broken file", e);
                }
            }
        });
        AtomicInteger failures = new AtomicInteger();
        reader.setUncaughtExceptionHandler((t, e) -> failures.incrementAndGet());
        for (int i = 1; i <= 300; i++) {
            store.write(session(i));
        }
        done.set(true);
        reader.join();

        assertThat(failures).hasValue(0);
        assertThat(reads.get()).isPositive();
        assertThat(store.read()).contains(session(300));
        try (var files = Files.list(store.directory())) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("t.json");
        }
    }

    @Test
    void aBrokenFileIsReportedWithoutItsContent() throws Exception {
        SessionFile store = new SessionFile(home.resolve("qits"));
        Files.createDirectories(store.directory());
        Files.writeString(store.path(), "{\"accessToken\":\"SECRET-access-9\", broken");
        assertThatThrownBy(store::read).hasMessageContaining("not a valid session file").hasMessageNotContaining("SECRET-");
    }

    @Test
    void aSessionPrintsNoToken() {
        assertThat(session(1).toString()).doesNotContain("SECRET-").contains("2026-09-11T20:15:00Z");
    }

    @Test
    void theFingerprintSeesAReplaceOfTheSameSize() throws Exception {
        SessionFile store = new SessionFile(home.resolve("qits"));
        assertThat(store.fingerprint().exists()).isFalse();
        store.write(session(1));
        SessionFile.Fingerprint first = store.fingerprint();
        store.write(session(2));
        assertThat(store.fingerprint()).isNotEqualTo(first);
    }

    /** The write lock waits for another process, and takes the lock once that process lets go. */
    @Test
    void theWriteLockWaitsForAnotherProcess() throws Exception {
        SessionFile store = new SessionFile(home.resolve("qits"));
        Files.createDirectories(store.directory());
        Process holder = LockHolder.start(store.writeLockPath());
        Thread.ofPlatform().start(() -> {
            try {
                Thread.sleep(700);
            } catch (InterruptedException ignored) {
                // The holder is closed either way.
            }
            try {
                holder.getOutputStream().close();
            } catch (Exception ignored) {
                // It exits with the pipe.
            }
        });
        long start = System.nanoTime();
        try (ExclusiveLock ignored = store.lockForWrite()) {
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThan(Duration.ofMillis(500));
        }
        holder.waitFor();
    }
}
