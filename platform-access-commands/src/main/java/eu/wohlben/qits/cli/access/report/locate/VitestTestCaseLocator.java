package eu.wohlben.qits.cli.access.report.locate;

import eu.wohlben.qits.cli.access.report.LineRange;
import eu.wohlben.qits.cli.access.report.TestCaseLocator;
import eu.wohlben.qits.cli.access.report.TestCoordinates;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A vitest test in its spec, TypeScript or JavaScript: the {@code it}/{@code test} call from its name
 * to its closing parenthesis. The describe path ({@code className}, joined with {@code " > "}) is
 * walked through {@code describe}/{@code suite} calls whose title matches each segment in turn, and
 * the test is the {@code it}/{@code test} directly inside the innermost whose title matches the test's
 * name. A title is a string or template literal; one of an {@code .each} table, or holding a
 * placeholder ({@code %s}, {@code $name}, {@code ${…}}), matches as a pattern. No match, or more than
 * one, locates nothing.
 */
public final class VitestTestCaseLocator implements TestCaseLocator {

    private static final Set<String> SUITES = Set.of("describe", "suite");
    private static final Set<String> TESTS = Set.of("it", "test");
    private static final Set<String> MODIFIERS = Set.of("only", "skip", "todo", "concurrent", "sequential");
    /** Modifiers that take arguments of their own before the call: a table, or a condition. */
    private static final Set<String> TABLES = Set.of("each", "for");
    private static final Set<String> CONDITIONS = Set.of("skipIf", "runIf");

    private static final String PATH = " > ";

    /** A formatting placeholder vitest fills in: printf's, or a table row's {@code $name}. */
    private static final Pattern PLACEHOLDER =
            Pattern.compile("%[sdifjoOc#]|\\$[\\p{L}_$][\\p{L}\\p{N}_$]*(?:\\.[\\p{L}\\p{N}_$]+)*|%%");

    /** What the test-results kind names a spec that failed with no test of its own. */
    private static final String SETUP_NAME = "(setup)";

    @Override
    public boolean supports(String language, String tool) {
        return ("typescript".equals(language) || "javascript".equals(language)) && "vitest".equals(tool);
    }

    @Override
    public Optional<LineRange> locate(Path root, TestCoordinates test) {
        try {
            return find(root, test);
        } catch (Exception | StackOverflowError unreadable) {
            return Optional.empty();
        }
    }

    private static Optional<LineRange> find(Path root, TestCoordinates test) throws Exception {
        String name = test.testName();
        if (name == null || name.isEmpty() || name.equals(SETUP_NAME) && isFile(test.className(), test.file())) {
            return Optional.empty();
        }
        Optional<String> source = SourceFiles.read(root, test.file());
        if (source.isEmpty()) {
            return Optional.empty();
        }
        List<Token> tokens = ScriptScanner.scan(source.get());
        int[] partner = Brackets.match(tokens);
        if (partner == null) {
            return Optional.empty();
        }
        List<String> path = isFile(test.className(), test.file()) ? List.of()
                : Arrays.asList(test.className().split(PATH, -1));
        List<Call> found = new ArrayList<>();
        collect(tokens, partner, 0, tokens.size(), path, 0, name, found);
        if (found.size() != 1) {
            return Optional.empty();
        }
        Call call = found.getFirst();
        return Optional.of(new LineRange(tokens.get(call.name()).line(), tokens.get(call.close()).line()));
    }

    /**
     * Whether {@code className} names no describe: blank, or (the test-results kind's spelling of a
     * test outside any describe) the spec file itself.
     */
    private static boolean isFile(String className, String file) {
        if (className == null || className.isBlank()) {
            return true;
        }
        if (file == null || className.contains(PATH)) {
            return false;
        }
        String spec = className.replace('\\', '/');
        return spec.equals(file) || spec.endsWith("/" + file) || file.endsWith("/" + spec);
    }

    /** The tests named {@code name} under {@code path[depth..]}, directly within {@code [from, to)}. */
    private static void collect(List<Token> tokens, int[] partner, int from, int to, List<String> path, int depth,
                                String name, List<Call> found) {
        for (Call call : calls(tokens, partner, from, to)) {
            if (call.title() == null) {
                continue;
            }
            if (depth == path.size()) {
                if (!call.suite() && call.title().matches(name)) {
                    found.add(call);
                }
            } else if (call.suite() && call.title().matches(path.get(depth))) {
                collect(tokens, partner, call.open() + 1, call.close(), path, depth + 1, name, found);
            }
        }
    }

    /**
     * A describe or test call.
     *
     * @param name  the index of {@code describe}, {@code it}, ...
     * @param open  its argument list's {@code (}
     * @param close that list's {@code )}
     * @param title its first argument, when a literal; null otherwise
     */
    private record Call(boolean suite, int name, int open, int close, Title title) {
    }

    /** The describe and test calls in {@code [from, to)} that no other such call there encloses. */
    private static List<Call> calls(List<Token> tokens, int[] partner, int from, int to) {
        List<Call> calls = new ArrayList<>();
        int i = from;
        while (i < to) {
            Call call = call(tokens, partner, i, to);
            if (call != null) {
                calls.add(call);
                i = call.close() + 1;
            } else {
                i++;
            }
        }
        return calls;
    }

    /** The call starting at {@code i}: {@code describe.each(table)('title', ...)}, {@code it.only('title', ...)}. */
    private static Call call(List<Token> tokens, int[] partner, int i, int to) {
        Token head = tokens.get(i);
        if (!head.isIdent() || !SUITES.contains(head.text()) && !TESTS.contains(head.text())
                || i > 0 && tokens.get(i - 1).is('.')) {
            return null;
        }
        boolean suite = SUITES.contains(head.text());
        boolean table = false;
        int j = i + 1;
        while (j + 1 < to && tokens.get(j).is('.') && tokens.get(j + 1).isIdent()) {
            String modifier = tokens.get(j + 1).text();
            j += 2;
            if (MODIFIERS.contains(modifier) || !suite && modifier.equals("fails")) {
                continue;
            }
            if (!TABLES.contains(modifier) && !CONDITIONS.contains(modifier)) {
                return null;
            }
            table |= TABLES.contains(modifier);
            if (j < to && tokens.get(j).is('(')) {
                j = partner[j] + 1;
            } else if (j < to && TABLES.contains(modifier) && tokens.get(j).kind() == Token.Kind.TEMPLATE) {
                j++;
            } else {
                return null;
            }
        }
        if (j >= to || !tokens.get(j).is('(')) {
            return null;
        }
        int close = partner[j];
        Title title = null;
        if (j + 2 <= close) {
            Token first = tokens.get(j + 1);
            Token after = tokens.get(j + 2);
            if ((first.kind() == Token.Kind.STRING || first.kind() == Token.Kind.TEMPLATE)
                    && (after.is(',') || after.is(')'))) {
                title = Title.of(first.parts(), table);
            }
        }
        return new Call(suite, i, j, close, title);
    }

    /** A literal title: matched exactly, or as a pattern when vitest fills parts of it in. */
    private record Title(String exact, Pattern pattern) {

        static Title of(List<String> parts, boolean table) {
            boolean placeholders = parts.size() > 1 || parts.stream().anyMatch(p -> PLACEHOLDER.matcher(p).find());
            if (!table && !placeholders) {
                return new Title(parts.getFirst(), null);
            }
            StringBuilder regex = new StringBuilder();
            for (int k = 0; k < parts.size(); k++) {
                if (k > 0) {
                    regex.append(".*?");
                }
                Matcher m = PLACEHOLDER.matcher(parts.get(k));
                int last = 0;
                while (m.find()) {
                    regex.append(Pattern.quote(parts.get(k).substring(last, m.start())));
                    regex.append(m.group().equals("%%") ? Pattern.quote("%") : ".*?");
                    last = m.end();
                }
                regex.append(Pattern.quote(parts.get(k).substring(last)));
            }
            return new Title(null, Pattern.compile(regex.toString(), Pattern.DOTALL));
        }

        boolean matches(String name) {
            return pattern == null ? exact.equals(name) : pattern.matcher(name).matches();
        }
    }
}
