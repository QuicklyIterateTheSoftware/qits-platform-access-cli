package eu.wohlben.qits.cli.access.report;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * One tool or format feeding one kind. Adding a language or a tool is one more implementation, listed
 * in {@link ReportKinds}.
 *
 * @param <R> what one file parses into; the kind it feeds knows the type
 */
public interface ReportParser<R> {

    /** The {@link ReportKind#id()} it feeds. */
    String kind();

    /** "java", "typescript". */
    String language();

    /** "surefire", "failsafe", "vitest", "jacoco", "vitest-coverage". */
    String tool();

    /** The files this tool leaves where the archetype puts them, in a stable order; empty when absent. */
    List<Path> discover(Path root);

    R parse(Path file, StepContext step) throws IOException;
}
