package eu.wohlben.qits.cli.access.observe;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * One streamed record as one line for a person:
 * <pre>
 * 12:03:11.123 log    qits-ci ERROR connection refused  [4bf92f35]
 * 12:03:11.140 span   qits-ci ERROR GET /ci/api/builds  [4bf92f35]
 * 12:03:12.000 metric qits-ci 42 ms http.server.duration
 * </pre>
 * The local time, the kind, the service, then the level (log), the status (span) or the value
 * (metric), the body (log) or the name (span, metric), and the first 8 characters of the trace id.
 * Every value is passed through {@link SafeText#line}.
 */
final class RecordLine {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final String[] BANDS = {"TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL"};

    private RecordLine() {
    }

    static String render(JsonNode frame, ZoneId zone) {
        String kind = text(frame, "kind");
        JsonNode record = frame.path("record");
        long nanos;
        String state;
        String what;
        String trace = text(record, "traceId");
        switch (kind) {
            case "log" -> {
                nanos = record.path("epochNanos").asLong(0);
                state = severity(record);
                what = text(record, "body");
            }
            case "span" -> {
                nanos = record.path("startEpochNanos").asLong(0);
                state = text(record, "status");
                what = text(record, "name");
            }
            case "metric" -> {
                nanos = record.path("epochNanos").asLong(0);
                state = value(record);
                what = text(record, "name");
            }
            default -> {
                nanos = 0;
                state = "";
                what = "";
            }
        }
        long millis = nanos > 0 ? nanos / 1_000_000 : frame.path("receivedAtMillis").asLong(0);
        StringBuilder line = new StringBuilder()
                .append(millis > 0 ? TIME.format(Instant.ofEpochMilli(millis).atZone(zone)) : "--:--:--.---")
                .append(' ').append(pad(orDash(kind), 6))
                .append(' ').append(orDash(text(record, "serviceName")))
                .append(' ').append(pad(orDash(state), 5))
                .append(' ').append(what);
        if (!trace.isEmpty()) {
            line.append("  [").append(trace, 0, Math.min(8, trace.length())).append(']');
        }
        // An empty body or name leaves the padding of the column before it.
        return line.toString().stripTrailing();
    }

    /** The band of the severity number, as the filters count it; else the record's own text. */
    private static String severity(JsonNode record) {
        int number = record.path("severityNumber").asInt(0);
        if (number >= 1 && number <= 24) {
            return BANDS[(number - 1) / 4];
        }
        return text(record, "severityText");
    }

    private static String value(JsonNode record) {
        JsonNode node = record.path("value");
        if (!node.isNumber()) {
            return text(record, "value");
        }
        double value = node.asDouble();
        String number = Math.rint(value) == value && Math.abs(value) < 1e15
                ? Long.toString((long) value)
                : Double.toString(value);
        String unit = text(record, "unit");
        // "1" is OpenTelemetry's unit for a plain count; "42 1" would only confuse.
        return unit.isEmpty() || unit.equals("1") ? number : number + " " + unit;
    }

    /** A field as safe text; empty when it is missing or null. */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : SafeText.line(value.asText()).strip();
    }

    private static String orDash(String value) {
        return value.isEmpty() ? "-" : value;
    }

    private static String pad(String value, int width) {
        return value.length() >= width ? value : value + " ".repeat(width - value.length());
    }
}
