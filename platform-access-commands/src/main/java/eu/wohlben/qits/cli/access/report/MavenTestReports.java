package eu.wohlben.qits.cli.access.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The JUnit XML a maven test plugin leaves in {@code <module>/target/<plugin>-reports/TEST-*.xml}: one
 * file per test class, its nested classes included. Surefire and failsafe differ only in the
 * directory and in where their test classes' sources live.
 */
abstract class MavenTestReports implements ReportParser<TestFile> {

    /** The test name a class-level failure carries when it has none of its own. */
    static final String SETUP_NAME = "(setup)";

    /** Directories never searched for a module: they hold no maven module of the repository's. */
    private static final Set<String> NOT_MODULES = Set.of("node_modules", ".git", ".qits-reports", "src");

    /** How deep a module may sit under the root. */
    private static final int MAX_DEPTH = 12;

    private final String tool;
    private final String reportsDirectory;
    private final List<String> sourceRoots;

    MavenTestReports(String tool, String reportsDirectory, List<String> sourceRoots) {
        this.tool = tool;
        this.reportsDirectory = reportsDirectory;
        this.sourceRoots = List.copyOf(sourceRoots);
    }

    @Override
    public String kind() {
        return TestResultsKind.ID;
    }

    @Override
    public String language() {
        return "java";
    }

    @Override
    public String tool() {
        return tool;
    }

    /** Every {@code **}{@code /target/<plugin>-reports/TEST-*.xml}, in path order. */
    @Override
    public List<Path> discover(Path root) {
        List<Path> found = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return found;
        }
        try {
            Files.walkFileTree(root, Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) throws IOException {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (!dir.equals(root) && NOT_MODULES.contains(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (name.equals("target")) {
                        Path reports = dir.resolve(reportsDirectory);
                        if (Files.isDirectory(reports)) {
                            try (Stream<Path> files = Files.list(reports)) {
                                files.filter(MavenTestReports::isReport).forEach(found::add);
                            }
                        }
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException unreadable) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | UncheckedIOException unreadable) {
            // A tree that cannot be walked holds nothing this parser can read.
        }
        found.sort(null);
        return found;
    }

    private static boolean isReport(Path file) {
        String name = file.getFileName().toString();
        return name.startsWith("TEST-") && name.endsWith(".xml") && Files.isRegularFile(file);
    }

    @Override
    public TestFile parse(Path file, StepContext step) throws IOException {
        Path moduleDirectory = file.toAbsolutePath().normalize().getParent().getParent().getParent();
        String module = module(step, moduleDirectory);
        int tests = 0;
        int failed = 0;
        int errored = 0;
        int skipped = 0;
        long durationMs = 0;
        List<TestResults.Failure> failures = new ArrayList<>();
        for (JunitXml.Suite suite : JunitXml.read(file)) {
            durationMs += suite.durationMs();
            for (JunitXml.Case c : suite.cases()) {
                tests++;
                switch (c.outcome()) {
                    case PASSED -> {
                        continue;
                    }
                    case SKIPPED -> {
                        skipped++;
                        continue;
                    }
                    case FAILED -> failed++;
                    case ERRORED -> errored++;
                }
                failures.add(failure(step, moduleDirectory, suite, c));
            }
        }
        return new TestFile(language(), tool, module, tests, failed, errored, skipped, durationMs, failures);
    }

    private TestResults.Failure failure(StepContext step, Path moduleDirectory, JunitXml.Suite suite, JunitXml.Case c) {
        String className = Text.orNull(c.classname());
        if (className == null) {
            className = Text.orNull(suite.name());
        }
        String name = c.name() == null ? "" : c.name().strip();
        boolean setup = isSetup(className, name);
        String shape = setup ? TestResults.Shape.SETUP
                : JunitXml.namesTimeout(c.type(), c.message()) ? TestResults.Shape.TIMEOUT
                : c.outcome() == JunitXml.Outcome.FAILED ? TestResults.Shape.ASSERTION
                : TestResults.Shape.ERROR;
        String testName = setup && !name.equals("initializationError") ? SETUP_NAME : name;
        TestCoordinates coordinates = new TestCoordinates(language(), tool, step.repository(), step.commitSha(),
                file(step, moduleDirectory, className), className, testName, null, null);
        return new TestResults.Failure(coordinates, shape, Text.orNull(c.type()),
                Text.cap(Text.orNull(c.message()), TestResultsKind.MESSAGE_BYTES),
                Text.cap(Text.orNull(c.body()), TestResultsKind.STACK_TRACE_BYTES),
                c.seconds() == null ? null : JunitXml.millis(c.seconds()));
    }

    /** A test case with no method of its own: JUnit 4's initializationError, a blank or a class-named case. */
    static boolean isSetup(String className, String name) {
        if (name.isEmpty() || name.equals("initializationError")) {
            return true;
        }
        if (className == null) {
            return false;
        }
        String simple = className.substring(Math.max(className.lastIndexOf('.'), className.lastIndexOf('$')) + 1);
        return name.equals(className) || name.equals(simple);
    }

    /**
     * {@code a.b.C$Inner} as {@code <module>/<source root>/a/b/C.java}, under the first source root
     * that has it; null when none has.
     */
    private String file(StepContext step, Path moduleDirectory, String className) {
        if (className == null) {
            return null;
        }
        String outer = className.contains("$") ? className.substring(0, className.indexOf('$')) : className;
        if (outer.isEmpty() || !outer.matches("[\\p{L}\\p{N}_$.]+")) {
            return null;
        }
        String path = outer.replace('.', '/') + ".java";
        for (String sourceRoot : sourceRoots) {
            Path candidate = moduleDirectory.resolve(sourceRoot).resolve(path);
            if (Files.isRegularFile(candidate)) {
                return step.relative(candidate);
            }
        }
        return null;
    }

    /** The module directory relative to the root; "." for the root itself. */
    static String module(StepContext step, Path moduleDirectory) {
        String relative = step.relative(moduleDirectory);
        return relative.isEmpty() ? "." : relative;
    }
}
