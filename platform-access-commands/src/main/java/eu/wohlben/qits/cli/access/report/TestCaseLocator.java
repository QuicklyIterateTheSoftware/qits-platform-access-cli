package eu.wohlben.qits.cli.access.report;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Where a test case sits in its file, for one language and tool. qits-755 implements these; with none
 * registered, {@link TestCoordinates#lineStart()} and {@link TestCoordinates#lineEnd()} stay null.
 */
public interface TestCaseLocator {

    boolean supports(String language, String tool);

    /** The test case's lines in {@code test.file()}, under {@code root}; empty when it cannot be found. */
    Optional<LineRange> locate(Path root, TestCoordinates test);
}
