package eu.wohlben.qits.cli.access.report;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * istanbul's {@code json} reporter, {@code coverage-final.json}, which vitest writes with either of its
 * providers (v8 and istanbul). Looked for under {@code coverage/} at the root, where Angular's
 * unit-test builder leaves it ({@code coverage/<project>/coverage-final.json}: it cannot be pointed
 * elsewhere), and under {@code .qits-reports/coverage/}. Never inside {@code node_modules}.
 * <p>
 * One entry per source file, keyed by its absolute path, which is made relative to the step's root; a
 * file outside the root is left out. A line is coverable when a statement starts on it, and covered
 * when any statement starting on it ran.
 */
public final class IstanbulJsonParser implements ReportParser<LineCoverage> {

    static final String FILE = "coverage-final.json";

    /** Where the file is looked for, relative to the root, in this order. */
    static final List<String> DIRECTORIES = List.of(VitestJunitParser.REPORTS + "/coverage", "coverage");

    private static final Set<String> NEVER = Set.of("node_modules", ".git");
    private static final int MAX_DEPTH = 8;

    @Override
    public String kind() {
        return CoverageKind.ID;
    }

    @Override
    public String language() {
        return "typescript";
    }

    @Override
    public String tool() {
        return "vitest-coverage";
    }

    @Override
    public List<Path> discover(Path root) {
        List<Path> found = new ArrayList<>();
        for (String directory : DIRECTORIES) {
            Path start = root.resolve(directory);
            if (!Files.isDirectory(start)) {
                continue;
            }
            List<Path> here = new ArrayList<>();
            try {
                Files.walkFileTree(start, Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                        Path name = dir.getFileName();
                        return name != null && NEVER.contains(name.toString()) ? FileVisitResult.SKIP_SUBTREE
                                : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                        if (attributes.isRegularFile() && file.getFileName().toString().equals(FILE)) {
                            here.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException unreadable) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException | UncheckedIOException unreadable) {
                // Nothing readable here.
            }
            here.sort(null);
            found.addAll(here);
        }
        return found;
    }

    @Override
    public LineCoverage parse(Path file, StepContext step) throws IOException {
        JsonNode document = ReportJson.MAPPER.readTree(file.toFile());
        if (document == null || !document.isObject()) {
            throw new IOException("not an istanbul coverage map: the top level is not an object");
        }
        LineCoverage coverage = LineCoverage.of(language(), tool());
        for (Map.Entry<String, JsonNode> entry : document.properties()) {
            JsonNode fileCoverage = entry.getValue();
            String path = relative(step, fileCoverage.path("path").isTextual()
                    ? fileCoverage.path("path").asText() : entry.getKey());
            if (path == null) {
                continue;
            }
            JsonNode statements = fileCoverage.path("statementMap");
            JsonNode counts = fileCoverage.path("s");
            for (Map.Entry<String, JsonNode> statement : statements.properties()) {
                int line = statement.getValue().path("start").path("line").asInt(0);
                if (line <= 0) {
                    continue;
                }
                coverage.line(path, line, counts.path(statement.getKey()).asLong(0) > 0);
            }
        }
        return coverage;
    }

    /** {@code path} relative to the root, or null when it is not under it (or under a node_modules). */
    static String relative(StepContext step, String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        Path root = step.root().toAbsolutePath().normalize();
        Path file;
        try {
            Path given = Path.of(path);
            file = (given.isAbsolute() ? given : root.resolve(given)).normalize();
        } catch (InvalidPathException notAPath) {
            return null;
        }
        if (!file.startsWith(root)) {
            // The tree may be reached through a link (the step's working directory against the path
            // vitest resolved); try the real one.
            try {
                Path real = root.toRealPath();
                if (!file.startsWith(real)) {
                    return null;
                }
                file = root.resolve(real.relativize(file));
            } catch (IOException | InvalidPathException unresolvable) {
                return null;
            }
        }
        String relative = step.relative(file);
        if (relative.isEmpty() || ("/" + relative + "/").contains("/node_modules/")) {
            return null;
        }
        return relative;
    }
}
