package eu.wohlben.qits.cli.access.report;

import eu.wohlben.qits.cli.access.report.locate.JUnitTestCaseLocator;
import eu.wohlben.qits.cli.access.report.locate.VitestTestCaseLocator;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** The registered {@link TestCaseLocator}s: JUnit's for Java, vitest's for TypeScript and JavaScript. */
public final class TestCaseLocators {

    private final List<TestCaseLocator> locators;

    public TestCaseLocators(List<TestCaseLocator> locators) {
        this.locators = List.copyOf(locators);
    }

    /**
     * What this release registers: a JUnit test method under surefire or failsafe, and a vitest
     * {@code it}/{@code test} call. Every other failure's lines stay null.
     */
    public static TestCaseLocators registered() {
        return new TestCaseLocators(List.of(new JUnitTestCaseLocator(), new VitestTestCaseLocator()));
    }

    /**
     * The test with its lines filled in by the first locator that supports its language and tool,
     * or unchanged. A locator that throws locates nothing; it never fails a submit.
     */
    public TestCoordinates locate(Path root, TestCoordinates test) {
        if (test.file() == null) {
            return test;
        }
        for (TestCaseLocator locator : locators) {
            if (locator.supports(test.language(), test.tool())) {
                Optional<LineRange> found;
                try {
                    found = locator.locate(root, test);
                } catch (RuntimeException broken) {
                    found = Optional.empty();
                }
                return found.map(range -> test.withLines(range.start(), range.end())).orElse(test);
            }
        }
        return test;
    }
}
