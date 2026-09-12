package eu.wohlben.qits.cli.access.observe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;

/** Stream frames as qits-observability sends them, with the records its query API returns. */
final class Frames {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Instant T0 = Instant.parse("2026-09-12T10:00:00Z");
    static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    /** 10:00:01.000 UTC. */
    static final long AT = T0.plusSeconds(1).toEpochMilli() * 1_000_000L;

    private Frames() {
    }

    static String log(String body) {
        return frame("log", record().put("epochNanos", AT).put("severityNumber", 17).put("severityText", "ERROR")
                .put("body", body).put("traceId", TRACE).put("spanId", "00f067aa0ba902b7"));
    }

    static String span(String name, String status) {
        ObjectNode record = record().put("traceId", TRACE).put("spanId", "00f067aa0ba902b7").putNull("parentSpanId")
                .put("scopeName", "io.quarkus").put("name", name).put("kind", "SERVER").put("startEpochNanos", AT)
                .put("durationMs", 12).put("status", status).putNull("statusMessage");
        record.putArray("events").addObject().put("name", "exception").put("epochNanos", AT).putObject("attributes")
                .put("exception.type", "java.io.IOException");
        return frame("span", record);
    }

    static String metric(String name, double value, String unit) {
        return frame("metric", record().put("name", name).put("description", "d").put("unit", unit).put("type", "GAUGE")
                .put("value", value).put("epochNanos", AT));
    }

    static ObjectNode record() {
        ObjectNode record = JSON.createObjectNode().put("serviceName", "qits-ci");
        record.putObject("attributes");
        record.putObject("resourceAttributes").put("service.name", "qits-ci");
        return record;
    }

    static String frame(String kind, ObjectNode record) {
        ObjectNode frame = JSON.createObjectNode().put("kind", kind).put("receivedAtMillis", T0.plusSeconds(2).toEpochMilli())
                .put("source", "_service/qits-ci");
        frame.set("record", record);
        return frame.toString();
    }

    static JsonNode tree(String json) {
        try {
            return JSON.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
