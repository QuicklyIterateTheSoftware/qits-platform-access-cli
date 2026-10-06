package eu.wohlben.qits.cli.access.report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * vitest's JUnit reporter, {@code .qits-reports/vitest-*.xml} at the root. vitest writes a
 * {@code <testsuite>} per spec file; a case's {@code classname} is the spec file and its {@code name}
 * the {@code describe} path and the test's title, joined with {@code " > "}. A spec that failed to
 * load is one case named after the file itself.
 */
public final class VitestJunitParser implements ReportParser<TestFile> {

    static final String REPORTS = ".qits-reports";
    private static final String PATH = " > ";

    @Override
    public String kind() {
        return TestResultsKind.ID;
    }

    @Override
    public String language() {
        return "typescript";
    }

    @Override
    public String tool() {
        return "vitest";
    }

    @Override
    public List<Path> discover(Path root) {
        Path reports = root.resolve(REPORTS);
        if (!Files.isDirectory(reports)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(reports)) {
            return files.filter(f -> {
                String name = f.getFileName().toString();
                return name.startsWith("vitest-") && name.endsWith(".xml") && Files.isRegularFile(f);
            }).sorted().toList();
        } catch (IOException unreadable) {
            return List.of();
        }
    }

    @Override
    public TestFile parse(Path file, StepContext step) throws IOException {
        Path project = file.toAbsolutePath().normalize().getParent().getParent();
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
                failures.add(failure(step, project, suite, c));
            }
        }
        return new TestFile(language(), tool(), MavenTestReports.module(step, project), tests, failed, errored,
                skipped, durationMs, failures);
    }

    private TestResults.Failure failure(StepContext step, Path project, JunitXml.Suite suite, JunitXml.Case c) {
        String spec = Text.orNull(c.classname());
        if (spec == null) {
            spec = Text.orNull(suite.name());
        }
        String name = c.name() == null ? "" : c.name().strip();
        boolean setup = name.isEmpty() || name.equals(spec);
        String className;
        String testName;
        List<String> path = Arrays.asList(name.split(" > ", -1));
        if (setup) {
            className = spec;
            testName = MavenTestReports.SETUP_NAME;
        } else if (path.size() > 1) {
            className = String.join(PATH, path.subList(0, path.size() - 1));
            testName = path.getLast();
        } else {
            // A test outside any describe: the spec file is the nearest thing to its class.
            className = spec;
            testName = name;
        }
        String shape = setup ? TestResults.Shape.SETUP
                : JunitXml.namesTimeout(c.type(), c.message()) ? TestResults.Shape.TIMEOUT
                : c.outcome() == JunitXml.Outcome.FAILED && isAssertion(c.type()) ? TestResults.Shape.ASSERTION
                : TestResults.Shape.ERROR;
        TestCoordinates coordinates = new TestCoordinates(language(), tool(), step.repository(), step.commitSha(),
                file(step, project, spec), className, testName, null, null);
        return new TestResults.Failure(coordinates, shape, Text.orNull(c.type()),
                Text.cap(Text.orNull(c.message()), TestResultsKind.MESSAGE_BYTES),
                Text.cap(Text.orNull(c.body()), TestResultsKind.STACK_TRACE_BYTES),
                c.seconds() == null ? null : JunitXml.millis(c.seconds()));
    }

    /** chai's and vitest's own assertion errors. */
    private static boolean isAssertion(String type) {
        return type != null && type.strip().endsWith("AssertionError");
    }

    /**
     * The spec file relative to the root: as vitest wrote it (absolute, or relative to its project,
     * the directory holding {@code .qits-reports}); null when no such file exists under the root.
     */
    private static String file(StepContext step, Path project, String spec) {
        if (spec == null) {
            return null;
        }
        Path root = step.root().toAbsolutePath().normalize();
        try {
            for (Path candidate : List.of(project.resolve(spec), root.resolve(spec))) {
                Path normal = candidate.toAbsolutePath().normalize();
                if (normal.startsWith(root) && Files.isRegularFile(normal)) {
                    return step.relative(normal);
                }
            }
        } catch (InvalidPathException notAPath) {
            return null;
        }
        return null;
    }
}
