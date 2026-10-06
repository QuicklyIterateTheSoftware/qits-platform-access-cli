package eu.wohlben.qits.cli.access.report;

import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * What one coverage file parses into, whatever its tool: per file (relative to the root, forward
 * slashes), each coverable line and whether it is covered. A line is coverable when the tool put a
 * probe or a statement on it.
 */
public record LineCoverage(String language, String tool, Map<String, NavigableMap<Integer, Boolean>> files) {

    /** An empty one to fill: {@link #line} as the parser goes. */
    static LineCoverage of(String language, String tool) {
        return new LineCoverage(language, tool, new TreeMap<>());
    }

    /** Marks {@code line} of {@code file} coverable, and covered if it is here or was already. */
    void line(String file, int line, boolean covered) {
        files.computeIfAbsent(file, f -> new TreeMap<>()).merge(line, covered, Boolean::logicalOr);
    }
}
