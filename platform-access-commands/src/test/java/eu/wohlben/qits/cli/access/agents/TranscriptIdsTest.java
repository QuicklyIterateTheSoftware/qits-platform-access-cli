package eu.wohlben.qits.cli.access.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Which ids a transcript's end names, newest first. */
class TranscriptIdsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private final WorkLinks links = new WorkLinks(List.of("qits", "shop"), "https://qits.wohlben.eu",
            WorkLinks.Style.OSC8);

    static String assistant(String text) {
        ObjectNode entry = JSON.createObjectNode().put("type", "assistant");
        entry.putObject("message").putArray("content").addObject().put("type", "text").put("text", text);
        return entry.toString();
    }

    static String toolResult(String text) {
        ObjectNode entry = JSON.createObjectNode().put("type", "user");
        entry.putObject("message").putArray("content").addObject().put("type", "tool_result").put("content", text);
        return entry.toString();
    }

    static String toolResultParts(String text) {
        ObjectNode entry = JSON.createObjectNode().put("type", "user");
        entry.putObject("message").putArray("content").addObject().put("type", "tool_result").putArray("content")
                .addObject().put("type", "text").put("text", text);
        return entry.toString();
    }

    static String person(String text) {
        ObjectNode entry = JSON.createObjectNode().put("type", "user");
        entry.putObject("message").put("content", text);
        return entry.toString();
    }

    private List<String> newest(int limit, String... lines) throws Exception {
        Path transcript = dir.resolve("t.jsonl");
        Files.writeString(transcript, String.join("\n", lines) + "\n");
        return TranscriptIds.newest(transcript, links, limit).stream().map(WorkLinks.WorkId::id).toList();
    }

    @Test
    void theNewestDistinctIdsComeFirst() throws Exception {
        assertThat(newest(5, assistant("qits-1 and qits-2"), toolResult("qits-3"), assistant("qits-1 again")))
                .containsExactly("qits-1", "qits-3", "qits-2");
    }

    @Test
    void onlyTheLimitIsKept() throws Exception {
        assertThat(newest(2, assistant("qits-1 qits-2 qits-3"))).containsExactly("qits-3", "qits-2");
    }

    @Test
    void textAndToolResultsCountAndNothingElse() throws Exception {
        assertThat(newest(5, person("qits-9"), toolResultParts("shop-4"), "not json", assistant("ticket/qits-8 `qits-7`")))
                .containsExactly("qits-7", "shop-4");
    }

    @Test
    void onlyTheTailIsReadAndItsCutLineIsDropped() throws Exception {
        String early = assistant("qits-1");
        String filler = assistant("x".repeat(TranscriptIds.TAIL_BYTES));
        assertThat(newest(5, early, filler, assistant("qits-2"))).containsExactly("qits-2");
    }
}
