package eu.wohlben.qits.cli.access.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** vitest 4's JUnit reporter, from the fixture tree. */
class VitestJunitParserTest {

    @TempDir
    Path work;

    @Test
    void findsTheReportsTheArchetypeAsksFor() {
        Path root = Fixtures.tree(work);

        assertThat(Fixtures.paths(Fixtures.step(root), new VitestJunitParser().discover(root)))
                .containsExactly(".qits-reports/vitest-app.xml");
        assertThat(new VitestJunitParser().discover(work.resolve("service"))).isEmpty();
    }

    @Test
    void countsEverySpecsCases() throws Exception {
        TestFile file = parse();

        assertThat(file.language()).isEqualTo("typescript");
        assertThat(file.tool()).isEqualTo("vitest");
        assertThat(file.module()).isEqualTo(".");
        assertThat(file.tests()).isEqualTo(6);
        assertThat(file.failed()).isEqualTo(3);
        assertThat(file.errored()).isZero();
        assertThat(file.skipped()).isEqualTo(1);
        assertThat(file.failures()).hasSize(3);
    }

    @Test
    void anAssertionNamesItsDescribePathItsTitleAndItsSpec() throws Exception {
        TestResults.Failure failure = parse().failures().stream()
                .filter(f -> f.coordinates().testName().equals("refuses a withdrawal")).findFirst().orElseThrow();

        assertThat(failure.coordinates().className()).isEqualTo("Ledger > when empty");
        assertThat(failure.coordinates().file()).isEqualTo("src/app/ledger.spec.ts");
        assertThat(failure.coordinates().commitSha()).isEqualTo(Fixtures.COMMIT);
        assertThat(failure.shape()).isEqualTo(TestResults.Shape.ASSERTION);
        assertThat(failure.failureType()).isEqualTo("AssertionError");
        assertThat(failure.message()).isEqualTo("expected 'accepted' to be 'refused' // Object.is equality");
        assertThat(failure.stackTrace()).startsWith("AssertionError: expected 'accepted'")
                .contains("src/app/ledger.spec.ts:10:26");
    }

    @Test
    void aTimeoutIsATimeout() throws Exception {
        TestResults.Failure failure = parse().failures().stream()
                .filter(f -> f.coordinates().testName().equals("waits too long")).findFirst().orElseThrow();

        assertThat(failure.shape()).isEqualTo(TestResults.Shape.TIMEOUT);
        assertThat(failure.coordinates().className()).isEqualTo("Ledger");
        assertThat(failure.message()).startsWith("Test timed out in 100ms.");
    }

    @Test
    void aSpecThatDidNotLoadIsSetup() throws Exception {
        TestResults.Failure failure = parse().failures().stream()
                .filter(f -> f.shape().equals(TestResults.Shape.SETUP)).findFirst().orElseThrow();

        assertThat(failure.coordinates().className()).isEqualTo("src/app/broken.spec.ts");
        assertThat(failure.coordinates().testName()).isEqualTo(MavenTestReports.SETUP_NAME);
        assertThat(failure.coordinates().file()).isEqualTo("src/app/broken.spec.ts");
        assertThat(failure.message()).startsWith("Cannot find module './does-not-exist'");
    }

    private TestFile parse() throws Exception {
        Path root = Fixtures.tree(work);
        return new VitestJunitParser().parse(root.resolve(".qits-reports/vitest-app.xml"), Fixtures.step(root));
    }
}
