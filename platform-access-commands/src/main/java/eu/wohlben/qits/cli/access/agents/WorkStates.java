package eu.wohlben.qits.cli.access.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The status and block of each work id the status line shows, cached in one file with a short
 * lifetime: {@code {"qits-1": {"status": …, "blocked": …, "blockSource": …, "checked": <ms>}}}.
 * <p>
 * The status line never waits for the network: it shows what is cached and starts one background
 * refresh for the ids that are stale. {@code checked} is also set when a refresh is started, so the
 * runs that follow while it works do not start another.
 */
final class WorkStates {

    static final Duration TTL = Duration.ofSeconds(60);

    /** Entries not looked at for this long are dropped, so the file stays small. */
    static final Duration KEEP = Duration.ofDays(1);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** What is known of one id. {@code status} is null when nothing is. */
    record State(String status, boolean blocked, String blockSource) {
    }

    private final Path file;
    private final Clock clock;

    WorkStates(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
    }

    /** {@code $XDG_CACHE_HOME/qits/work-states.json}, beside the slug cache. */
    static WorkStates fromEnvironment(Map<String, String> env, Clock clock) {
        return new WorkStates(LinkProjects.cacheDirectory(env).resolve("work-states.json"), clock);
    }

    Path file() {
        return file;
    }

    /** The known state of each id that has one. */
    Map<String, State> read(Collection<String> ids) {
        ObjectNode all = load();
        Map<String, State> states = new HashMap<>();
        for (String id : ids) {
            JsonNode entry = all.get(id);
            if (entry != null && entry.hasNonNull("status")) {
                states.put(id, new State(entry.get("status").asText(), entry.path("blocked").asBoolean(false),
                        entry.hasNonNull("blockSource") ? entry.get("blockSource").asText() : null));
            }
        }
        return states;
    }

    /**
     * The ids not checked within {@link #TTL}, now marked as checked. The caller refreshes them; the
     * mark keeps the next runs from doing the same.
     */
    List<String> claimStale(Collection<String> ids) throws IOException {
        ObjectNode all = load();
        long now = clock.millis();
        List<String> stale = new ArrayList<>();
        for (String id : ids) {
            JsonNode entry = all.get(id);
            if (entry == null || !entry.isObject() || entry.path("checked").asLong(0) + TTL.toMillis() <= now) {
                stale.add(id);
                ObjectNode marked = entry != null && entry.isObject() ? (ObjectNode) entry : all.putObject(id);
                marked.put("checked", now);
            }
        }
        if (!stale.isEmpty()) {
            save(all);
        }
        return stale;
    }

    /** Writes what the projects service said of each id, keeping every other entry. */
    void write(Map<String, State> answers) throws IOException {
        ObjectNode all = load();
        long now = clock.millis();
        answers.forEach((id, state) -> {
            ObjectNode entry = all.putObject(id);
            entry.put("status", state.status()).put("blocked", state.blocked()).put("blockSource", state.blockSource())
                    .put("checked", now);
        });
        save(all);
    }

    private ObjectNode load() {
        try {
            JsonNode tree = JSON.readTree(file.toFile());
            if (tree instanceof ObjectNode object) {
                return object;
            }
        } catch (IOException | RuntimeException missingOrBroken) {
            // Start again from nothing.
        }
        return JSON.createObjectNode();
    }

    /** In one rename, so a reader never sees half a file. */
    private void save(ObjectNode all) throws IOException {
        long oldest = clock.millis() - KEEP.toMillis();
        for (Iterator<Map.Entry<String, JsonNode>> it = all.fields(); it.hasNext(); ) {
            if (it.next().getValue().path("checked").asLong(0) < oldest) {
                it.remove();
            }
        }
        Files.createDirectories(file.getParent());
        Path partial = file.resolveSibling(file.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
        Files.writeString(partial, all.toString());
        Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
