package eu.wohlben.qits.cli.access.report.locate;

import eu.wohlben.qits.cli.access.report.LineRange;
import eu.wohlben.qits.cli.access.report.TestCaseLocator;
import eu.wohlben.qits.cli.access.report.TestCoordinates;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A JUnit test method in its Java source, for surefire and failsafe: from its first annotation to its
 * closing brace. The class is found by its binary name ({@code a.b.C$Inner} is the type {@code Inner}
 * in the top-level type {@code C}), the method by its name with surefire's parameter list and
 * invocation index cut off ({@code name(String)[1]}). Among overloads the one carrying a test
 * annotation wins; two that remain equal locate nothing, as does a class-level failure.
 */
public final class JUnitTestCaseLocator implements TestCaseLocator {

    /** The annotations that make a method a test, by simple name (the last segment of a qualified one). */
    private static final Set<String> TEST_ANNOTATIONS = Set.of("Test", "ParameterizedTest", "RepeatedTest",
            "TestFactory", "TestTemplate", "Property");

    private static final Set<String> TYPE_KEYWORDS = Set.of("class", "interface", "enum", "record");

    /** What the test-results kind names a class-level failure that had no test name of its own. */
    private static final String SETUP_NAME = "(setup)";

    @Override
    public boolean supports(String language, String tool) {
        return "java".equals(language) && ("surefire".equals(tool) || "failsafe".equals(tool));
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
        String className = test.className();
        String method = methodName(test.testName());
        if (className == null || className.isBlank() || method == null || isSetup(className, test.testName())) {
            return Optional.empty();
        }
        Optional<String> source = SourceFiles.read(root, test.file());
        if (source.isEmpty()) {
            return Optional.empty();
        }
        List<Token> tokens = JavaScanner.scan(source.get());
        int[] partner = Brackets.match(tokens);
        if (partner == null) {
            return Optional.empty();
        }
        String binary = className.substring(className.lastIndexOf('.') + 1);
        int from = 0;
        int to = tokens.size();
        for (String type : binary.split("\\$", -1)) {
            int body = typeBody(tokens, partner, from, to, type);
            if (body < 0) {
                return Optional.empty();
            }
            from = body + 1;
            to = partner[body];
        }
        return method(tokens, partner, from, to, method);
    }

    /** The method name in surefire's spelling of a test: {@code name(String)[1]} is {@code name}; null when none. */
    static String methodName(String testName) {
        if (testName == null) {
            return null;
        }
        String name = testName;
        int cut = name.length();
        int paren = name.indexOf('(');
        int bracket = name.indexOf('[');
        if (paren >= 0) {
            cut = paren;
        }
        if (bracket >= 0) {
            cut = Math.min(cut, bracket);
        }
        name = name.substring(0, cut).strip();
        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) {
            return null;
        }
        for (int k = 1; k < name.length(); k++) {
            if (!Character.isJavaIdentifierPart(name.charAt(k))) {
                return null;
            }
        }
        return name;
    }

    /** A failure of the class rather than of a method: initializationError, or a case named after the class. */
    private static boolean isSetup(String className, String testName) {
        String name = testName.strip();
        if (name.equals("initializationError") || name.equals(SETUP_NAME)) {
            return true;
        }
        String simple = className.substring(Math.max(className.lastIndexOf('.'), className.lastIndexOf('$')) + 1);
        return name.equals(className) || name.equals(simple);
    }

    /** The index of the {@code {} opening type {@code name}'s body, declared directly in {@code [from, to)}; -1 when none is. */
    private static int typeBody(List<Token> tokens, int[] partner, int from, int to, String name) {
        int i = from;
        while (i < to) {
            Token t = tokens.get(i);
            if (t.is('{') || t.is('(') || t.is('[')) {
                i = partner[i] + 1;
                continue;
            }
            if (t.isIdent() && TYPE_KEYWORDS.contains(t.text()) && i + 1 < to && tokens.get(i + 1).isIdent(name)
                    && (i == from || !tokens.get(i - 1).is('.'))
                    && (!t.text().equals("record") || i + 2 < to && (tokens.get(i + 2).is('(') || tokens.get(i + 2).is('<')))) {
                for (int j = i + 2; j < to; j++) {
                    Token header = tokens.get(j);
                    if (header.is('{')) {
                        return j;
                    }
                    if (header.is('(') || header.is('[')) {
                        j = partner[j];
                    } else if (header.is(';')) {
                        break;
                    }
                }
            }
            i++;
        }
        return -1;
    }

    private record Candidate(int start, int close, boolean test) {
    }

    /** Method {@code name} among the members of the body {@code [from, to)}. */
    private static Optional<LineRange> method(List<Token> tokens, int[] partner, int from, int to, String name) {
        List<Candidate> candidates = new ArrayList<>();
        int member = from;
        int i = from;
        while (i < to) {
            Token t = tokens.get(i);
            if (t.is(';')) {
                member = ++i;
                continue;
            }
            if (t.is('{')) {
                i = partner[i] + 1;
                member = i;
                continue;
            }
            if (t.is('(') || t.is('[')) {
                i = partner[i] + 1;
                continue;
            }
            if (t.isIdent(name) && i > member && i + 1 < to && tokens.get(i + 1).is('(')
                    && declares(tokens.get(i - 1))) {
                int body = partner[i + 1] + 1;
                if (body < to && tokens.get(body).isIdent("throws")) {
                    while (body < to && !tokens.get(body).is('{') && !tokens.get(body).is(';')) {
                        body++;
                    }
                }
                if (body < to && tokens.get(body).is('{')) {
                    candidates.add(new Candidate(member, partner[body], annotatedAsTest(tokens, partner, member, i)));
                    i = partner[body] + 1;
                    member = i;
                    continue;
                }
            }
            i++;
        }
        List<Candidate> tests = candidates.stream().filter(Candidate::test).toList();
        List<Candidate> chosen = candidates.size() == 1 ? candidates : tests;
        if (chosen.size() != 1) {
            return Optional.empty();
        }
        Candidate found = chosen.getFirst();
        return Optional.of(new LineRange(tokens.get(found.start()).line(), tokens.get(found.close()).line()));
    }

    /** Whether the token before a name and its {@code (} makes them a method declaration: a type, or {@code void}. */
    private static boolean declares(Token before) {
        return before.isIdent() && !before.text().equals("new") || before.is('>') || before.is(']');
    }

    private static boolean annotatedAsTest(List<Token> tokens, int[] partner, int from, int to) {
        for (int k = from; k < to; k++) {
            Token t = tokens.get(k);
            if (t.is('(') || t.is('[') || t.is('{')) {
                k = partner[k];
                continue;
            }
            if (t.is('@') && k + 1 < to && tokens.get(k + 1).isIdent() && !tokens.get(k + 1).isIdent("interface")) {
                int last = k + 1;
                while (last + 2 < to && tokens.get(last + 1).is('.') && tokens.get(last + 2).isIdent()) {
                    last += 2;
                }
                if (TEST_ANNOTATIONS.contains(tokens.get(last).text())) {
                    return true;
                }
                k = last;
            }
        }
        return false;
    }
}
