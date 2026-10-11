package eu.wohlben.qits.cli.access.agents;

import eu.wohlben.qits.cli.access.FakeTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The slug cache: read as it is, refreshed in the background once per hour at most. */
class LinkProjectsTest {

    private static final Instant T0 = Instant.parse("2026-10-10T10:00:00Z");

    @TempDir
    Path dir;

    private final FakeTime time = new FakeTime(T0);
    private final AtomicInteger refreshes = new AtomicInteger();

    @Test
    void noCacheIsTheFallbackAndOneRefresh() {
        LinkProjects cache = new LinkProjects(dir.resolve("qits/work-links.json"), time);

        assertThat(cache.read(refreshes::incrementAndGet)).containsExactly("qits");
        assertThat(cache.read(refreshes::incrementAndGet)).containsExactly("qits");
        assertThat(refreshes).as("the first read touched the file").hasValue(1);
    }

    @Test
    void aFreshCacheIsReadAndNotRefreshed() throws Exception {
        LinkProjects cache = new LinkProjects(dir.resolve("work-links.json"), time);
        cache.write(List.of("qits", "shop"));
        Files.setLastModifiedTime(cache.file(), FileTime.from(T0.minus(Duration.ofMinutes(59))));

        assertThat(cache.read(refreshes::incrementAndGet)).containsExactly("qits", "shop");
        assertThat(refreshes).hasValue(0);
    }

    @Test
    void aStaleCacheIsStillReadWhileItRefreshes() throws Exception {
        LinkProjects cache = new LinkProjects(dir.resolve("work-links.json"), time);
        cache.write(List.of("shop"));
        Files.setLastModifiedTime(cache.file(), FileTime.from(T0.minus(Duration.ofMinutes(61))));

        assertThat(cache.read(refreshes::incrementAndGet)).containsExactly("shop");
        assertThat(cache.read(refreshes::incrementAndGet)).containsExactly("shop");
        assertThat(refreshes).hasValue(1);
    }

    @Test
    void aBrokenCacheIsTheFallback() throws Exception {
        LinkProjects cache = new LinkProjects(dir.resolve("work-links.json"), time);
        Files.writeString(cache.file(), "not json");

        assertThat(cache.read(refreshes::incrementAndGet)).containsExactly("qits");
        assertThat(refreshes).hasValue(1);
    }
}
