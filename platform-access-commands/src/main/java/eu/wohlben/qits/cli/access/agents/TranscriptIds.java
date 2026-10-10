package eu.wohlben.qits.cli.access.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The work ids a Claude session spoke of most recently, read off its transcript (JSONL).
 * <p>
 * Only the tail is read, because a status line runs often and a transcript grows without end. Ids
 * count from Claude's text and from tool results, which is where an id read with {@code qits work}
 * shows up.
 */
final class TranscriptIds {

    /** How much of the transcript's end is read. */
    static final int TAIL_BYTES = 200 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();

    private TranscriptIds() {
    }

    /** The newest {@code limit} distinct ids, newest first. */
    static List<WorkLinks.WorkId> newest(Path transcript, WorkLinks links, int limit) throws IOException {
        List<WorkLinks.WorkId> all = new ArrayList<>();
        for (String line : tail(transcript)) {
            try {
                texts(JSON.readTree(line)).forEach(text -> all.addAll(links.ids(text)));
            } catch (IOException notJson) {
                // A line cut by a write in progress; the next run reads it whole.
            }
        }
        Map<String, WorkLinks.WorkId> newest = new LinkedHashMap<>();
        for (int i = all.size() - 1; i >= 0 && newest.size() < limit; i--) {
            newest.putIfAbsent(all.get(i).id(), all.get(i));
        }
        return List.copyOf(newest.values());
    }

    /** The whole lines in the last {@link #TAIL_BYTES} of the file. */
    static List<String> tail(Path file) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r")) {
            long length = in.length();
            long from = Math.max(0, length - TAIL_BYTES);
            byte[] bytes = new byte[(int) (length - from)];
            in.seek(from);
            in.readFully(bytes);
            List<String> lines = new ArrayList<>(List.of(new String(bytes, StandardCharsets.UTF_8).split("\n")));
            if (from > 0 && !lines.isEmpty()) {
                lines.removeFirst();
            }
            return lines;
        }
    }

    /** The text of an assistant message and of the tool results in a user message. */
    static List<String> texts(JsonNode entry) {
        List<String> texts = new ArrayList<>();
        String type = entry.path("type").asText("");
        JsonNode content = entry.path("message").path("content");
        if (!content.isArray()) {
            return texts;
        }
        for (JsonNode block : content) {
            String kind = block.path("type").asText("");
            if (type.equals("assistant") && kind.equals("text")) {
                texts.add(block.path("text").asText(""));
            } else if (type.equals("user") && kind.equals("tool_result")) {
                JsonNode result = block.path("content");
                if (result.isTextual()) {
                    texts.add(result.asText());
                } else if (result.isArray()) {
                    result.forEach(part -> {
                        if (part.path("type").asText("").equals("text")) {
                            texts.add(part.path("text").asText(""));
                        }
                    });
                }
            }
        }
        return texts;
    }
}
