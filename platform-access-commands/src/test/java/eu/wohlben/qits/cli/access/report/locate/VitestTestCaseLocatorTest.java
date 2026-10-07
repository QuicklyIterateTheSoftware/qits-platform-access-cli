package eu.wohlben.qits.cli.access.report.locate;

import eu.wohlben.qits.cli.access.report.LineRange;
import eu.wohlben.qits.cli.access.report.RepositoryRef;
import eu.wohlben.qits.cli.access.report.TestCoordinates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** vitest tests located in the specs under {@code src/test/resources/report/locate/vitest}. */
class VitestTestCaseLocatorTest {

    private static final Path ROOT = JUnitTestCaseLocatorTest.fixtures();

    private final VitestTestCaseLocator locator = new VitestTestCaseLocator();

    @TempDir
    Path work;

    @Test
    void supportsTypeScriptAndJavaScriptUnderVitest() {
        assertThat(locator.supports("typescript", "vitest")).isTrue();
        assertThat(locator.supports("javascript", "vitest")).isTrue();
        assertThat(locator.supports("typescript", "jest")).isFalse();
        assertThat(locator.supports("java", "vitest")).isFalse();
    }

    @Test
    void topLevelItAndTest() {
        assertThat(locate("basics.spec.ts", "", "runs at the top level")).contains(new LineRange(3, 5));
        assertThat(locate("basics.spec.ts", "", "also at the top level")).contains(new LineRange(7, 9));
    }

    @Test
    void theSpecFileAsClassNameIsTheTopLevel() {
        String file = "vitest/basics.spec.ts";
        assertThat(locator.locate(ROOT, coordinates(file, file, "runs at the top level")))
                .contains(new LineRange(3, 5));
        assertThat(locator.locate(ROOT, coordinates(file, "basics.spec.ts", "runs at the top level")))
                .contains(new LineRange(3, 5));
        assertThat(locator.locate(ROOT, coordinates(file, null, "also at the top level")))
                .contains(new LineRange(7, 9));
        assertThat(locator.locate(ROOT, coordinates(file, file, "(setup)"))).isEmpty();
    }

    @Test
    void nestedDescribesAreWalkedInOrder() {
        assertThat(locate("basics.spec.ts", "Ledger > when empty", "refuses a withdrawal"))
                .contains(new LineRange(13, 15));
        assertThat(locate("basics.spec.ts", "Ledger", "refuses a withdrawal")).contains(new LineRange(29, 31));
        assertThat(locate("basics.spec.ts", "", "refuses a withdrawal")).isEmpty();
        assertThat(locate("basics.spec.ts", "when empty", "refuses a withdrawal")).isEmpty();
    }

    @Test
    void modifiersAreSteppedOver() {
        assertThat(locate("basics.spec.ts", "Ledger > when full", "accepts a deposit")).contains(new LineRange(19, 19));
        assertThat(locate("basics.spec.ts", "Ledger > when full", "is skipped")).contains(new LineRange(20, 20));
        assertThat(locate("basics.spec.ts", "Ledger > when full", "runs concurrently")).contains(new LineRange(21, 23));
        assertThat(locate("basics.spec.ts", "Ledger > when full", "is expected to fail"))
                .contains(new LineRange(24, 26));
    }

    @Test
    void eachTablesMatchAsPatterns() {
        assertThat(locate("tables.spec.ts", "arithmetic", "adds 2 + 1")).contains(new LineRange(4, 9));
        assertThat(locate("tables.spec.ts", "arithmetic", "returns 2 when 1 is added to 1"))
                .contains(new LineRange(11, 16));
        assertThat(locate("tables.spec.ts", "table second", "knows its name")).contains(new LineRange(23, 25));
        assertThat(locate("tables.spec.ts", "arithmetic", "subtracts 2 - 1")).isEmpty();
    }

    @Test
    void escapedQuotesAndTemplateTitles() {
        assertThat(locate("titles.spec.ts", "titles", "says 'hello'")).contains(new LineRange(4, 4));
        assertThat(locate("titles.spec.ts", "titles", "says \"bye\" !")).contains(new LineRange(5, 5));
        assertThat(locate("titles.spec.ts", "titles", "uses a template")).contains(new LineRange(6, 8));
        assertThat(locate("titles.spec.ts", "titles", "interpolates nothing")).contains(new LineRange(9, 9));
    }

    @Test
    void regexAndTemplateLiteralsDoNotUnbalanceTheBrackets() {
        assertThat(locate("titles.spec.ts", "titles", "has a regex")).contains(new LineRange(11, 15));
        assertThat(locate("titles.spec.ts", "titles", "has an object in a template")).contains(new LineRange(17, 20));
    }

    @Test
    void twoEqualTitlesLocateNothing() {
        assertThat(locate("titles.spec.ts", "titles", "same")).isEmpty();
    }

    @Test
    void anAbsentTitleLocatesNothing() {
        assertThat(locate("titles.spec.ts", "titles", "not there")).isEmpty();
        assertThat(locate("titles.spec.ts", "no such describe", "says 'hello'")).isEmpty();
    }

    @Test
    void aJavaScriptSpecWithDivisions() {
        assertThat(locator.locate(ROOT, new TestCoordinates("javascript", "vitest", new RepositoryRef("p", "r"), "sha",
                "vitest/plain.spec.js", "in javascript", "divides", null, null))).contains(new LineRange(4, 7));
    }

    @Test
    void aNullMissingOrOversizedFileLocatesNothing() throws Exception {
        assertThat(locator.locate(ROOT, coordinates(null, "", "runs at the top level"))).isEmpty();
        assertThat(locator.locate(ROOT, coordinates("vitest/nowhere.spec.ts", "", "x"))).isEmpty();
        Files.writeString(work.resolve("big.spec.ts"), "it('x', () => {});\n//" + "x".repeat((int) SourceFiles.MAX_BYTES));
        assertThat(locator.locate(work, coordinates("big.spec.ts", "", "x"))).isEmpty();
        Files.writeString(work.resolve("broken.spec.ts"), "it('x', () => {\n");
        assertThat(locator.locate(work, coordinates("broken.spec.ts", "", "x"))).isEmpty();
    }

    private Optional<LineRange> locate(String spec, String describePath, String title) {
        return locator.locate(ROOT, coordinates("vitest/" + spec, describePath, title));
    }

    private static TestCoordinates coordinates(String file, String className, String testName) {
        return new TestCoordinates("typescript", "vitest", new RepositoryRef("p", "r"), "sha", file, className, testName,
                null, null);
    }
}
