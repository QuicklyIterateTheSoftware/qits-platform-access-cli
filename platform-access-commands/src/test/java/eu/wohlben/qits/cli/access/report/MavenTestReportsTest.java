package eu.wohlben.qits.cli.access.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/** Surefire's and failsafe's own XML, from the fixture tree. */
class MavenTestReportsTest {

    @TempDir
    Path work;

    @Test
    void surefireFindsEveryTestClassReportUnderAnyModulesTarget() {
        Path root = Fixtures.tree(work);
        StepContext step = Fixtures.step(root);

        assertThat(Fixtures.paths(step, new SurefireXmlParser().discover(root))).containsExactly(
                "service/target/surefire-reports/TEST-eu.wohlben.qits.fx.BrokenSetupTest.xml",
                "service/target/surefire-reports/TEST-eu.wohlben.qits.fx.ClockTest.xml",
                "service/target/surefire-reports/TEST-eu.wohlben.qits.fx.LedgerTest.xml",
                "service/target/surefire-reports/TEST-eu.wohlben.qits.fx4.LegacyTest.xml");
        assertThat(Fixtures.paths(step, new FailsafeXmlParser().discover(root))).containsExactly(
                "service/target/failsafe-reports/TEST-eu.wohlben.qits.fx.LedgerIT.xml");
    }

    @Test
    void nothingIsFoundWhereNoModuleWasBuilt() {
        assertThat(new SurefireXmlParser().discover(work)).isEmpty();
        assertThat(new SurefireXmlParser().discover(work.resolve("absent"))).isEmpty();
    }

    @Test
    void aClassWithANestedClassCountsItsCasesNotItsAttributes() throws Exception {
        Path root = Fixtures.tree(work);
        // Surefire writes tests="0" on this class; its six cases are what ran.
        TestFile ledger = parse(root, "TEST-eu.wohlben.qits.fx.LedgerTest.xml");

        assertThat(ledger.language()).isEqualTo("java");
        assertThat(ledger.tool()).isEqualTo("surefire");
        assertThat(ledger.module()).isEqualTo("service");
        assertThat(ledger.tests()).isEqualTo(6);
        assertThat(ledger.failed()).isEqualTo(2);
        assertThat(ledger.errored()).isEqualTo(1);
        assertThat(ledger.skipped()).isEqualTo(1);
        assertThat(ledger.durationMs()).isEqualTo(58);
        assertThat(ledger.failures()).extracting(f -> f.coordinates().testName())
                .containsExactly("readsTheLedger", "refusesAnotherRunsToken", "refusesAWithdrawal");
    }

    @Test
    void anAssertionFailureNamesItsClassItsTestItsFileAndItsMessage() throws Exception {
        Path root = Fixtures.tree(work);
        TestResults.Failure failure = failure(parse(root, "TEST-eu.wohlben.qits.fx.LedgerTest.xml"),
                f -> f.coordinates().testName().equals("refusesAnotherRunsToken"));

        TestCoordinates at = failure.coordinates();
        assertThat(at.language()).isEqualTo("java");
        assertThat(at.tool()).isEqualTo("surefire");
        assertThat(at.repository()).isEqualTo(Fixtures.REPOSITORY);
        assertThat(at.commitSha()).isEqualTo(Fixtures.COMMIT);
        assertThat(at.file()).isEqualTo("service/src/test/java/eu/wohlben/qits/fx/LedgerTest.java");
        assertThat(at.className()).isEqualTo("eu.wohlben.qits.fx.LedgerTest");
        assertThat(at.lineStart()).isNull();
        assertThat(at.lineEnd()).isNull();
        assertThat(failure.shape()).isEqualTo(TestResults.Shape.ASSERTION);
        assertThat(failure.failureType()).isEqualTo("org.opentest4j.AssertionFailedError");
        assertThat(failure.message())
                .isEqualTo("the door must refuse another run's token ==> expected: <403> but was: <204>");
        assertThat(failure.stackTrace()).startsWith("org.opentest4j.AssertionFailedError: the door must")
                .contains("at eu.wohlben.qits.fx.LedgerTest.refusesAnotherRunsToken(LedgerTest.java:18)");
        assertThat(failure.durationMs()).isEqualTo(7L);
    }

    @Test
    void aNestedClassIsNamedWholeAndMapsToItsOuterFile() throws Exception {
        Path root = Fixtures.tree(work);
        TestResults.Failure failure = failure(parse(root, "TEST-eu.wohlben.qits.fx.LedgerTest.xml"),
                f -> f.coordinates().testName().equals("refusesAWithdrawal"));

        assertThat(failure.coordinates().className()).isEqualTo("eu.wohlben.qits.fx.LedgerTest$WhenEmpty");
        assertThat(failure.coordinates().file()).isEqualTo("service/src/test/java/eu/wohlben/qits/fx/LedgerTest.java");
        assertThat(failure.shape()).isEqualTo(TestResults.Shape.ASSERTION);
    }

    @Test
    void anErrorIsAnErrorAndATimeoutIsATimeout() throws Exception {
        Path root = Fixtures.tree(work);
        TestResults.Failure npe = failure(parse(root, "TEST-eu.wohlben.qits.fx.LedgerTest.xml"),
                f -> f.coordinates().testName().equals("readsTheLedger"));
        TestFile clock = parse(root, "TEST-eu.wohlben.qits.fx.ClockTest.xml");

        assertThat(npe.shape()).isEqualTo(TestResults.Shape.ERROR);
        assertThat(npe.failureType()).isEqualTo("java.lang.NullPointerException");
        assertThat(npe.message()).isEqualTo("Cannot invoke \"Object.toString()\" because \"ledger\" is null");
        assertThat(clock.errored()).isEqualTo(1);
        assertThat(clock.failures()).singleElement().satisfies(f -> {
            assertThat(f.shape()).isEqualTo(TestResults.Shape.TIMEOUT);
            assertThat(f.coordinates().testName()).isEqualTo("waitsTooLong");
            assertThat(f.message()).isEqualTo("waitsTooLong() timed out after 200 milliseconds");
        });
    }

    @Test
    void aClassThatFailsBeforeAnyTestIsSetup() throws Exception {
        Path root = Fixtures.tree(work);
        TestFile broken = parse(root, "TEST-eu.wohlben.qits.fx.BrokenSetupTest.xml");
        TestFile legacy = parse(root, "TEST-eu.wohlben.qits.fx4.LegacyTest.xml");

        assertThat(broken.failures()).singleElement().satisfies(f -> {
            assertThat(f.shape()).isEqualTo(TestResults.Shape.SETUP);
            assertThat(f.coordinates().className()).isEqualTo("eu.wohlben.qits.fx.BrokenSetupTest");
            assertThat(f.coordinates().testName()).isEqualTo(MavenTestReports.SETUP_NAME);
            assertThat(f.coordinates().file()).isEqualTo("service/src/test/java/eu/wohlben/qits/fx/BrokenSetupTest.java");
            assertThat(f.message()).isEqualTo("the database did not start");
        });
        assertThat(legacy.failures()).singleElement().satisfies(f -> {
            assertThat(f.shape()).isEqualTo(TestResults.Shape.SETUP);
            assertThat(f.coordinates().file()).isEqualTo("service/src/test/java/eu/wohlben/qits/fx4/LegacyTest.java");
            assertThat(f.message()).startsWith("Invalid test class 'eu.wohlben.qits.fx4.LegacyTest':\n");
        });
    }

    @Test
    void junitFoursInitializationErrorAndAClassNamedCaseAreSetupToo() {
        assertThat(MavenTestReports.isSetup("a.b.C", "initializationError")).isTrue();
        assertThat(MavenTestReports.isSetup("a.b.C", "a.b.C")).isTrue();
        assertThat(MavenTestReports.isSetup("a.b.C$D", "D")).isTrue();
        assertThat(MavenTestReports.isSetup("a.b.C", "")).isTrue();
        assertThat(MavenTestReports.isSetup("a.b.C", "addsTwoEntries")).isFalse();
    }

    @Test
    void failsafeFindsAnIntegrationTestsSourceUnderSrcItJava() throws Exception {
        Path root = Fixtures.tree(work);
        Path file = new FailsafeXmlParser().discover(root).getFirst();
        TestFile it = new FailsafeXmlParser().parse(file, Fixtures.step(root));

        assertThat(it.tool()).isEqualTo("failsafe");
        assertThat(it.tests()).isEqualTo(2);
        assertThat(it.failed()).isEqualTo(1);
        assertThat(it.failures()).singleElement().satisfies(f -> {
            assertThat(f.coordinates().tool()).isEqualTo("failsafe");
            assertThat(f.coordinates().file()).isEqualTo("service/src/it/java/eu/wohlben/qits/fx/LedgerIT.java");
            assertThat(f.coordinates().testName()).isEqualTo("servesTheLedger");
            assertThat(f.message()).isEqualTo("GET /ledger ==> expected: <200> but was: <503>");
        });
    }

    @Test
    void aClassWhoseSourceIsNotThereHasNoFile() throws Exception {
        Path root = Fixtures.tree(work);
        java.nio.file.Files.delete(root.resolve("service/src/test/java/eu/wohlben/qits/fx/ClockTest.java"));

        assertThat(parse(root, "TEST-eu.wohlben.qits.fx.ClockTest.xml").failures())
                .singleElement().satisfies(f -> assertThat(f.coordinates().file()).isNull());
    }

    private static TestFile parse(Path root, String name) throws Exception {
        return new SurefireXmlParser().parse(root.resolve("service/target/surefire-reports").resolve(name),
                Fixtures.step(root));
    }

    private static TestResults.Failure failure(TestFile file, Predicate<TestResults.Failure> which) {
        List<TestResults.Failure> found = file.failures().stream().filter(which).toList();
        assertThat(found).hasSize(1);
        return found.getFirst();
    }
}
