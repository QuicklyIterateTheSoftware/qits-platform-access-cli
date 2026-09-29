package eu.wohlben.qits.cli.access.projects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliFailure;

import java.io.PrintStream;

/**
 * The JSON form of an answer that carries text people and agents wrote: a work entity's title and
 * description, a comment's body. Control characters are written as escapes, so what a person pipes
 * through {@code -o json} cannot move their cursor or clear their screen. {@link
 * ProjectsApi#printJson} does not do that, and is only for answers nobody but the platform writes.
 */
final class SafeJson {

    private SafeJson() {
    }

    /** The answer, indented, with every control character written as an escape. */
    static void print(PrintStream out, JsonNode node) throws CliFailure {
        try {
            out.println(SafeText.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node));
        } catch (JsonProcessingException impossible) {
            throw new CliFailure("cannot print the answer as JSON", CliFailure.FAILED);
        }
    }
}
