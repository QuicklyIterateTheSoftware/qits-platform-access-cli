package eu.wohlben.qits.cli.access.report;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** The registered {@link TestCaseLocator}s. None yet: the slicers are qits-755's. */
public final class TestCaseLocators {

    private final List<TestCaseLocator> locators;

    public TestCaseLocators(List<TestCaseLocator> locators) {
        this.locators = List.copyOf(locators);
    }

    /** What this release registers: nothing, so every failure's lines stay null. */
    public static TestCaseLocators registered() {
        return new TestCaseLocators(List.of());
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
