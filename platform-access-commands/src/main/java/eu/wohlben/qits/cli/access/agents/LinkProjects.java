package eu.wohlben.qits.cli.access.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The project slugs whose work ids are linked, cached in a file for an hour.
 * <p>
 * The hook must never wait for the network, so it reads the cache as it is and, when that is stale
 * or missing, starts a refresh it does not wait for. It touches the file first, so the batches that
 * follow within the hour do not all start one.
 */
public final class LinkProjects {

    static final Duration TTL = Duration.ofHours(1);

    /** The slug used until the first refresh has written the cache. */
    static final String FALLBACK = "qits";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;
    private final Clock clock;

    public LinkProjects(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
    }

    /** {@code $XDG_CACHE_HOME/qits/work-links.json}, else {@code ~/.cache/qits/work-links.json}. */
    public static LinkProjects fromEnvironment(Map<String, String> env, Clock clock) {
        return new LinkProjects(cacheDirectory(env).resolve("work-links.json"), clock);
    }

    /** {@code $XDG_CACHE_HOME/qits}, else {@code ~/.cache/qits}. A relative XDG_CACHE_HOME is ignored. */
    static Path cacheDirectory(Map<String, String> env) {
        String xdg = env.get("XDG_CACHE_HOME");
        Path base = xdg != null && !xdg.isBlank() && Path.of(xdg).isAbsolute()
                ? Path.of(xdg)
                : Path.of(System.getProperty("user.home"), ".cache");
        return base.resolve("qits");
    }

    public Path file() {
        return file;
    }

    /**
     * The cached slugs, else {@link #FALLBACK}. Runs {@code refresh} when the cache is stale or
     * missing, after touching it.
     */
    public List<String> read(Runnable refresh) {
        List<String> slugs = List.of(FALLBACK);
        boolean stale;
        try {
            List<String> cached = slugs(JSON.readTree(file.toFile()));
            if (!cached.isEmpty()) {
                slugs = cached;
            }
            FileTime written = Files.getLastModifiedTime(file);
            stale = written.toInstant().plus(TTL).isBefore(clock.instant());
        } catch (IOException | RuntimeException unreadable) {
            stale = true;
        }
        if (stale && touch()) {
            refresh.run();
        }
        return slugs;
    }

    /** Writes {@code slugs} in one rename, so a reader never sees half a file. */
    public void write(List<String> slugs) throws IOException {
        Files.createDirectories(file.getParent());
        ArrayNode array = JSON.createArrayNode();
        slugs.forEach(array::add);
        Path partial = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(partial, JSON.createObjectNode().set("slugs", array).toString());
        Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** {@code {"slugs":[…]}}. */
    static List<String> slugs(JsonNode cache) {
        List<String> slugs = new ArrayList<>();
        JsonNode array = cache == null ? null : cache.get("slugs");
        if (array != null && array.isArray()) {
            array.forEach(s -> {
                if (s.isTextual() && !s.asText().isBlank()) {
                    slugs.add(s.asText().strip());
                }
            });
        }
        return slugs;
    }

    private boolean touch() {
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                Files.writeString(file, "{}");
            }
            Files.setLastModifiedTime(file, FileTime.from(clock.instant()));
            return true;
        } catch (IOException | RuntimeException cannot) {
            // A cache that cannot be touched would start a refresh on every batch.
            return false;
        }
    }
}
