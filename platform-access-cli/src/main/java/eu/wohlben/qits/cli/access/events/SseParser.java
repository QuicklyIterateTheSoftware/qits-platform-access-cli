package eu.wohlben.qits.cli.access.events;

import java.util.Optional;

/**
 * Server-Sent Events, one line at a time, as the HTML specification reads them: {@code data:}
 * lines join with a newline, {@code id:} sets the last event id, a line starting with {@code :} is
 * a comment, and an empty line ends the event. One space after the colon is not part of the value.
 * An event the stream never ended with an empty line is dropped.
 */
final class SseParser {

    /** One complete event. {@code id} is the last id the stream set; it may be null. */
    record Event(String id, String type, String data) {
    }

    private final StringBuilder data = new StringBuilder();
    private boolean hasData;
    private String type;
    private String lastId;

    /** Takes one line without its line end. Answers the event an empty line completes. */
    Optional<Event> accept(String line) {
        if (line.isEmpty()) {
            if (!hasData) {
                type = null;
                return Optional.empty();
            }
            Event event = new Event(lastId, type, data.toString());
            data.setLength(0);
            hasData = false;
            type = null;
            return Optional.of(event);
        }
        if (line.startsWith(":")) {
            return Optional.empty();
        }
        int colon = line.indexOf(':');
        String field = colon < 0 ? line : line.substring(0, colon);
        String value = colon < 0 ? "" : line.substring(colon + 1);
        if (value.startsWith(" ")) {
            value = value.substring(1);
        }
        switch (field) {
            case "data" -> {
                if (hasData) {
                    data.append('\n');
                }
                data.append(value);
                hasData = true;
            }
            case "id" -> {
                if (value.indexOf('\0') < 0) {
                    lastId = value;
                }
            }
            case "event" -> type = value;
            default -> {
                // retry: and unknown fields are ignored, as the specification says.
            }
        }
        return Optional.empty();
    }
}
