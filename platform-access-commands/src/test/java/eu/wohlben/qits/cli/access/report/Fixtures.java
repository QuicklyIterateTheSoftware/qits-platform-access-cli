package eu.wohlben.qits.cli.access.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The fixture tree under {@code src/test/resources/report/tree}: a maven module whose surefire and
 * failsafe reports are real output of surefire 3.5.4 on JUnit 6 (a pass, a failure, an error, a
 * timeout, a skip, a nested class, a failing {@code @BeforeAll}, an invalid JUnit 4 class and a
 * failing integration test under {@code src/it/java}), with its {@code <properties>} blocks taken
 * out; and the JUnit XML vitest 4.1 wrote for two specs (a failure, a timeout, a skip, a spec that
 * did not load). Kept as {@code target.fixture}, because {@code target/} is ignored by git, and laid
 * out here under its real name.
 */
final class Fixtures {

    static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
    static final RepositoryRef REPOSITORY = new RepositoryRef("8f1c2d3e-0000-4000-8000-000000000001", "qits-fx-service");

    private Fixtures() {
    }

    /** The fixture tree, copied to {@code into}, with {@code target.fixture} named {@code target}. */
    static Path tree(Path into) {
        Path source;
        try {
            source = Path.of(Fixtures.class.getResource("/report/tree").toURI());
        } catch (URISyntaxException impossible) {
            throw new IllegalStateException(impossible);
        }
        try (Stream<Path> all = Files.walk(source)) {
            for (Path from : all.toList()) {
                Path to = into.resolve(source.relativize(from).toString().replace("target.fixture", "target"));
                if (Files.isDirectory(from)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(from, to);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return into;
    }

    static StepContext step(Path root) {
        return step(root, 0, Optional.empty());
    }

    static StepContext step(Path root, int exitCode, Optional<Baseline> baseline) {
        return new StepContext(root, "run-1", 2, REPOSITORY, COMMIT, exitCode, baseline, ChangedLines.unavailable(),
                TestCaseLocators.registered());
    }

    static List<String> paths(StepContext step, List<Path> files) {
        return files.stream().map(step::relative).toList();
    }
}
