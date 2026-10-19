package eu.wohlben.qits.cli.access.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters.Operation;
import eu.wohlben.qits.pact.consumer.Trigger;

import java.util.Map;
import java.util.Objects;

/**
 * <b>One (command, call) of a consumer pact</b> (ticket qits-1149): the recorded answer it is bound
 * to, the client method that makes the call, and the code that makes it.
 * <p>
 * {@link #interaction} is qits-pact-consumer's row: the provider state and operation, the command
 * as the {@code qits-trigger}, and what the command reads of the answer ({@code consumes}), which is
 * all the pact binds. {@link #reaches} names the client method the command calls, as {@code
 * Class#method}; {@code ClientCallsGuardTest} holds every door of every client to at least one row,
 * and the row's own test fails when the call it runs does not reach that method.
 * <p>
 * <b>A row the provider has not recorded yet</b> stays in the table, written in full, and its test
 * is skipped naming the provider state it needs. Its {@link #request} says which door it is, since
 * there is no recording to read that from; the day the provider records the state the row goes live
 * with no change here, and the pact file test fails until the file is written again.
 *
 * @param reaches the client method the call goes through: {@code ProjectsApi#work}
 * @param request {@code METHOD /path/{param}}: the door, for a row the provider has not recorded;
 *     null otherwise, where the recording says it
 * @param fieldsMatching form fields of the request body held to a regex rather than to the recorded
 *     value, each with the regex: see {@link #fieldMatches}
 */
public record CliRow(GoldenInteraction interaction, String reaches, String request, Call call,
        Map<String, String> fieldsMatching) {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The real client, pointed at {@code baseUrl}, making the call and asserting what the command reads. */
    @FunctionalInterface
    public interface Call {
        void run(String baseUrl, Operation recorded) throws Exception;
    }

    public CliRow(GoldenInteraction interaction, String reaches, String request, Call call) {
        this(interaction, reaches, request, call, Map.of());
    }

    public CliRow {
        fieldsMatching = Map.copyOf(fieldsMatching);
        Objects.requireNonNull(interaction, "interaction");
        Objects.requireNonNull(reaches, "reaches");
        Objects.requireNonNull(call, "call");
        if (!reaches.matches("[A-Za-z0-9$]+#[A-Za-z0-9]+")) {
            throw new IllegalArgumentException("reaches is Class#method, got '" + reaches + "'");
        }
        if (request != null && !request.matches("(GET|HEAD|POST|PUT|PATCH|DELETE) /\\S*")) {
            throw new IllegalArgumentException("request is 'METHOD /path', got '" + request + "'");
        }
    }

    /** {@code command}'s call of {@code operationId} in {@code state}, through {@code reaches}; reads the status only. */
    public static CliRow of(String command, String state, String operationId, String reaches, Call call) {
        return new CliRow(GoldenInteraction.of(Trigger.command(command), state, operationId), reaches, null, call);
    }

    /** The same row, reading {@code paths} of the answer: see {@link GoldenInteraction#consumes}. */
    public CliRow consumes(String... paths) {
        return new CliRow(interaction.consumes(paths), reaches, request, call, fieldsMatching);
    }

    /** {@link #consumes(String...)} over a list. */
    public CliRow consumes(java.util.List<String> paths) {
        return new CliRow(interaction.consumes(paths), reaches, request, call, fieldsMatching);
    }

    /** The same row, not pinning the length of the arrays at {@code patterns}. */
    public CliRow anyLength(String... patterns) {
        return new CliRow(interaction.anyLength(patterns), reaches, request, call, fieldsMatching);
    }

    /** The same row, binding the recorded values at {@code paths} exactly. */
    public CliRow exact(String... paths) {
        return new CliRow(interaction.exact(paths), reaches, request, call, fieldsMatching);
    }

    /** The same row, sending {@code name: value}; a {@code {param}} in the value comes from the state. */
    public CliRow header(String name, String value) {
        return new CliRow(interaction.header(name, value), reaches, request, call, fieldsMatching);
    }

    /** The same row, reading the response headers {@code names}. */
    public CliRow readsHeader(String... names) {
        return new CliRow(interaction.readsHeader(names), reaches, request, call, fieldsMatching);
    }

    /** The same row, naming its door: {@code GET /ci/api/runs/{runId}}. Required while it is not recorded. */
    public CliRow request(String door) {
        return new CliRow(interaction, reaches, door, call, fieldsMatching);
    }

    /**
     * The same row, holding the form field {@code name} of the request to {@code regex} instead of
     * the recorded value, which stays the example the provider is sent. For a value the command
     * derives from the address it reaches the provider at ({@code qits login}'s {@code redirect_uri},
     * {@code <idp>/connect/cli}): the provider records its own address, the mock serves another.
     */
    public CliRow fieldMatches(String name, String regex) {
        java.util.LinkedHashMap<String, String> all = new java.util.LinkedHashMap<>(fieldsMatching);
        all.put(name, regex);
        return new CliRow(interaction, reaches, request, call, all);
    }

    public String state() {
        return interaction.state();
    }

    public String operationId() {
        return interaction.operationId();
    }

    public String command() {
        return interaction.trigger().value();
    }

    public String description() {
        return interaction.description();
    }

    /**
     * The recorded request body with every {@code "{param}"}, in a value or a member name, the
     * state's frozen example: what the command must send, since pact matches a request body
     * exactly. Null when the operation takes none.
     */
    public static JsonNode exampleBody(Operation recorded) {
        return recorded.body() == null ? null : expand(recorded.body(), recorded);
    }

    private static JsonNode expand(JsonNode node, Operation op) {
        if (node.isTextual()) {
            return JSON.getNodeFactory().textNode(op.example(node.asText()));
        }
        if (node.isObject()) {
            var out = JSON.createObjectNode();
            node.properties().forEach(e -> out.set(op.example(e.getKey()), expand(e.getValue(), op)));
            return out;
        }
        if (node.isArray()) {
            var out = JSON.createArrayNode();
            node.forEach(e -> out.add(expand(e, op)));
            return out;
        }
        return node.deepCopy();
    }
}
