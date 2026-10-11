package eu.wohlben.qits.cli.access.agents;

import eu.wohlben.qits.cli.access.FakeTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The per-id cache: read at once, a stale id claimed for refresh once per TTL. */
class WorkStatesTest {

    @TempDir
    Path dir;

    private final FakeTime time = new FakeTime(Instant.parse("2026-10-10T10:00:00Z"));

    @Test
    void aStaleIdIsClaimedOncePerLifetime() throws Exception {
        WorkStates cache = new WorkStates(dir.resolve("work-states.json"), time);

        assertThat(cache.claimStale(List.of("qits-1", "qits-2"))).containsExactly("qits-1", "qits-2");
        assertThat(cache.claimStale(List.of("qits-1", "qits-2"))).isEmpty();
        assertThat(cache.read(List.of("qits-1"))).as("a claim is not a state").isEmpty();

        cache.write(Map.of("qits-1", new WorkStates.State("DONE", true, "AGENT_WAITING")));
        assertThat(cache.read(List.of("qits-1", "qits-2")))
                .containsExactly(Map.entry("qits-1", new WorkStates.State("DONE", true, "AGENT_WAITING")));

        time.jump(WorkStates.TTL);
        assertThat(cache.claimStale(List.of("qits-1", "qits-2"))).containsExactly("qits-1", "qits-2");
        assertThat(cache.read(List.of("qits-1"))).as("a stale state still shows").containsKey("qits-1");
    }

    @Test
    void oldEntriesAreDropped() throws Exception {
        WorkStates cache = new WorkStates(dir.resolve("work-states.json"), time);
        cache.write(Map.of("qits-1", new WorkStates.State("DONE", false, null)));
        time.jump(WorkStates.KEEP.plus(Duration.ofMinutes(1)));
        cache.write(Map.of("qits-2", new WorkStates.State("DONE", false, null)));

        assertThat(cache.read(List.of("qits-1", "qits-2"))).containsOnlyKeys("qits-2");
    }
}
