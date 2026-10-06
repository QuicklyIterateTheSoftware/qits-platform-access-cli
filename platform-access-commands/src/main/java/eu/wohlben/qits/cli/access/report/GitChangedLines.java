package eu.wohlben.qits.cli.access.report;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The lines the fold changed against the baseline's tag, from git: {@code git diff -U0 --no-renames
 * <version> HEAD}, read as the new side's added or changed line numbers per file. The tag comes from
 * {@link BaselineTag}, which fetches it into the shallow clone; a two-tree diff needs no merge base.
 * <p>
 * Computed on first use and at most once, and only when there is a baseline. Any failure, the fetch's
 * or the diff's, makes it unavailable, said once as one warning line: diff coverage is then absent,
 * never wrong.
 */
public final class GitChangedLines implements ChangedLines {

    /** {@code @@ -a[,b] +c[,d] @@}: the new side starts at c and runs d lines (1 when d is left out). */
    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@");

    private final BaselineTag tag;
    private final Consumer<String> warnings;

    private Map<String, Set<Integer>> changed;

    public GitChangedLines(BaselineTag tag, Consumer<String> warnings) {
        this.tag = tag;
        this.warnings = warnings;
    }

    @Override
    public boolean available() {
        return computed().isPresent();
    }

    @Override
    public Set<String> files() {
        return computed().map(Map::keySet).orElse(Set.of());
    }

    @Override
    public Set<Integer> lines(String file) {
        return computed().map(m -> m.getOrDefault(file, Set.of())).orElse(Set.of());
    }

    private synchronized Optional<Map<String, Set<Integer>>> computed() {
        if (changed == null) {
            changed = compute();
        }
        return changed == UNAVAILABLE ? Optional.empty() : Optional.of(changed);
    }

    private static final Map<String, Set<Integer>> UNAVAILABLE = Collections.unmodifiableMap(new LinkedHashMap<>());

    private Map<String, Set<Integer>> compute() {
        try {
            Optional<String> ref = tag.ref();
            if (ref.isEmpty()) {
                return UNAVAILABLE;
            }
            // --relative: paths relative to the step's root (where the reports' paths are), even when
            // that is a directory inside the repository; nothing outside it is listed.
            Git.Result diff = tag.git().run("diff", "-U0", "--no-renames", "--no-color", "--no-ext-diff",
                    "--relative", ref.get(), "HEAD");
            if (!diff.ok()) {
                warnings.accept("changed lines: `git diff` against " + tag.baseline().map(Baseline::version).orElse("")
                        + " failed, so no diff coverage: " + Git.said(diff));
                return UNAVAILABLE;
            }
            return Collections.unmodifiableMap(parse(diff.text()));
        } catch (Git.Failure failed) {
            warnings.accept("changed lines: " + failed.getMessage() + ", so no diff coverage");
            return UNAVAILABLE;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return UNAVAILABLE;
        }
    }

    /**
     * A {@code -U0} diff as the new side's line numbers per file. A deleted file has none; a hunk that
     * only removes lines ({@code +c,0}) adds none.
     */
    static Map<String, Set<Integer>> parse(String diff) {
        Map<String, Set<Integer>> files = new LinkedHashMap<>();
        Set<Integer> current = null;
        for (String line : diff.split("\n", -1)) {
            if (line.startsWith("diff --git ")) {
                current = null;
            } else if (line.startsWith("+++ ")) {
                String path = line.substring(4);
                if (path.equals("/dev/null")) {
                    current = null;
                    continue;
                }
                path = unquote(path);
                if (path.startsWith("b/")) {
                    path = path.substring(2);
                }
                current = files.computeIfAbsent(path, p -> new TreeSet<>());
            } else if (current != null && line.startsWith("@@ ")) {
                Matcher hunk = HUNK.matcher(line);
                if (hunk.find()) {
                    int start = Integer.parseInt(hunk.group(1));
                    int count = hunk.group(2) == null ? 1 : Integer.parseInt(hunk.group(2));
                    for (int n = start; n < start + count; n++) {
                        current.add(n);
                    }
                }
            }
        }
        files.values().removeIf(Set::isEmpty);
        files.replaceAll((file, lines) -> Collections.unmodifiableSet(lines));
        return files;
    }

    /**
     * A path git wrote in C quotes ({@code "b/a\tb.txt"}), which it does for a name with a control
     * character, a quote or a backslash even with {@code core.quotePath=false}. Octal escapes are
     * UTF-8 bytes.
     */
    static String unquote(String path) {
        String trimmed = path.endsWith("\t") ? path.substring(0, path.length() - 1) : path;
        if (trimmed.length() < 2 || trimmed.charAt(0) != '"' || trimmed.charAt(trimmed.length() - 1) != '"') {
            return trimmed;
        }
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        String body = trimmed.substring(1, trimmed.length() - 1);
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c != '\\' || i + 1 >= body.length()) {
                bytes.writeBytes(String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                continue;
            }
            char e = body.charAt(++i);
            switch (e) {
                case 'n' -> bytes.write('\n');
                case 't' -> bytes.write('\t');
                case 'r' -> bytes.write('\r');
                case 'a' -> bytes.write(7);
                case 'b' -> bytes.write('\b');
                case 'f' -> bytes.write('\f');
                case 'v' -> bytes.write(11);
                case '0', '1', '2', '3' -> {
                    int end = Math.min(i + 3, body.length());
                    bytes.write(Integer.parseInt(body.substring(i, end), 8));
                    i = end - 1;
                }
                default -> bytes.write(e);
            }
        }
        return bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
