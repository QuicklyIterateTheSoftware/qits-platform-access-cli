package eu.wohlben.qits.cli.access.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.session.PrivateFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * {@code update.json} in the config directory, beside {@code t.json}: when the automatic check last
 * started, and the last failure of one that ran in the background, which has no terminal to say it
 * on. The next command run in the foreground prints that failure once and clears it.
 * <p>
 * A Jackson tree, not a record, so it needs no reflection in the native binary. No lock: the worst
 * a race between two commands does is one check too many, or a failure line shown twice.
 *
 * @param checkedAt when a check last started; null for never
 * @param failure   one line for a person, never holding a token; null for none
 */
record UpdateState(Instant checkedAt, String failure) {

    static final String FILE_NAME = "update.json";

    private static final ObjectMapper JSON = new ObjectMapper();

    static final UpdateState NONE = new UpdateState(null, null);

    /** What the file says; {@link #NONE} when it is missing or unreadable. */
    static UpdateState read(Path directory) {
        try {
            byte[] bytes = Files.readAllBytes(directory.resolve(FILE_NAME));
            JsonNode node = JSON.readTree(bytes);
            if (node == null || !node.isObject()) {
                return NONE;
            }
            Instant checked = null;
            if (node.path("checkedAt").isTextual()) {
                try {
                    checked = Instant.parse(node.get("checkedAt").asText());
                } catch (DateTimeParseException notATime) {
                    checked = null;
                }
            }
            String failure = node.path("failure").isTextual() ? node.get("failure").asText() : null;
            return new UpdateState(checked, failure == null || failure.isBlank() ? null : failure);
        } catch (IOException | RuntimeException missingOrUnreadable) {
            return NONE;
        }
    }

    void write(Path directory) throws IOException {
        ObjectNode node = JSON.createObjectNode();
        if (checkedAt != null) {
            node.put("checkedAt", checkedAt.toString());
        }
        if (failure != null) {
            node.put("failure", failure);
        }
        PrivateFiles.writeAtomically(directory, FILE_NAME, (node.toString() + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /** {@link #write}, and a file that cannot be written is let go: nothing here may break a command. */
    void writeQuietly(Path directory) {
        try {
            write(directory);
        } catch (IOException | RuntimeException cannot) {
            // The next command checks again; that is all a lost write costs.
        }
    }

    UpdateState checked(Instant at) {
        return new UpdateState(at, failure);
    }

    UpdateState failed(String line) {
        return new UpdateState(checkedAt, line);
    }

    UpdateState cleared() {
        return new UpdateState(checkedAt, null);
    }

    Optional<String> failureLine() {
        return Optional.ofNullable(failure);
    }
}
