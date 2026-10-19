package eu.wohlben.qits.cli.access.contracts;

import au.com.dius.pact.consumer.ConsumerPactRunnerKt;
import au.com.dius.pact.consumer.PactVerificationResult;
import au.com.dius.pact.consumer.model.MockProviderConfig;
import au.com.dius.pact.core.model.ContentType;
import au.com.dius.pact.core.model.DefaultPactWriter;
import au.com.dius.pact.core.model.OptionalBody;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Interaction;
import au.com.dius.pact.core.model.V4Pact;
import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenFiles;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.pact.consumer.ProviderStateMissing;
import org.junit.jupiter.api.Assertions;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * <b>Everything the CLI asks one provider</b> (ticket qits-1149): the rows, the client classes they
 * call through, the doors of those classes that are not REST calls (named, with the reason), and
 * the consumer pact the rows make, {@code pacts/qits-platform-access-cli_<provider>.json}.
 * <p>
 * The rows are bound to the provider's golden masters, {@code eu.wohlben.qits:<app>-golden-masters}
 * pinned in the root pom, and read through qits-pact-consumer's {@link ConsumerPact}: only what a
 * command reads is bound, and a row whose state the provider has not recorded is left out of the
 * file until it does.
 *
 * @param clients each client class with the methods that are its doors: empty for every
 *     non-private instance method of the class (an {@code *Api} class), else the named ones (a
 *     command class, whose other methods are picocli's)
 * @param excluded {@code Class#method} of a client that makes no REST call to this provider, with
 *     the reason: a WebSocket, a stream, an address built for a browser
 */
public record CliPact(
        GoldenMasters masters,
        Map<Class<?>, Set<String>> clients,
        Map<String, String> excluded,
        List<CliRow> rows) {

    /** The consumer, as every pact names it: the repository, never an application. */
    public static final String CONSUMER = "qits-platform-access-cli";

    public CliPact {
        Objects.requireNonNull(masters, "masters");
        clients = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(clients));
        excluded = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(excluded));
        rows = List.copyOf(rows);
    }

    /**
     * The CLI's calls of one provider.
     *
     * @param repository the provider as a pact names it ({@code qits-ci-service})
     * @param application the provider as its golden-master index names it ({@code qits-ci})
     */
    public static CliPact of(String repository, String application) {
        return new CliPact(GoldenMasters.of(repository, application), Map.of(), Map.of(), List.of());
    }

    /** The same, with {@code type} as a client: every non-private instance method a door, unless {@code doors} names them. */
    public CliPact client(Class<?> type, String... doors) {
        Map<Class<?>, Set<String>> all = new LinkedHashMap<>(clients);
        all.put(type, Set.of(doors));
        return new CliPact(masters, all, excluded, rows);
    }

    /** The same, with {@code Class#method} named as no REST call of this provider, for {@code reason}. */
    public CliPact excluded(String method, String reason) {
        Map<String, String> all = new LinkedHashMap<>(excluded);
        all.put(method, reason);
        return new CliPact(masters, clients, all, rows);
    }

    public CliPact rows(List<CliRow> table) {
        List<CliRow> all = new ArrayList<>(rows);
        all.addAll(table);
        return new CliPact(masters, clients, excluded, all);
    }

    public String provider() {
        return masters.repository();
    }

    /** qits-pact-consumer's pact of the rows. */
    public ConsumerPact pact() {
        return ConsumerPact.of(CONSUMER, masters, rows.stream().map(CliRow::interaction).toList());
    }

    /** Whether the provider recorded the row's (state, operation) at the pinned version. */
    public boolean recorded(CliRow row) {
        return masters.recorded(row.state(), row.operationId());
    }

    public List<CliRow> live() {
        return rows.stream().filter(this::recorded).toList();
    }

    public List<CliRow> pending() {
        return rows.stream().filter(r -> !recorded(r)).toList();
    }

    /** Why a pending row waits, in qits-pact-consumer's words, with the door it is. */
    public String needs(CliRow row) {
        return ProviderStateMissing.message(provider(), row.state(), row.operationId())
                + (row.request() == null ? "" : " (" + row.request() + ")");
    }

    /** The committed pact's file name: both sides by repository name. */
    public String file() {
        return pact().file();
    }

    // --- the pact, with the request media type a recording leaves unnamed ----------------------

    /**
     * The V4 pact of {@code selected}: qits-pact-consumer's, with three corrections.
     * <ul>
     *   <li>A recording that names no request media type is sent as {@code application/json} there;
     *       a row whose command sends another ({@code application/merge-patch+json} on a PATCH door)
     *       names it as its {@code Content-Type} header, and that is the header and the body's type
     *       here.</li>
     *   <li>A JSON request body that is a bare string ({@code "2100.1.0"}, the npm dist-tag PUT) is a
     *       text node in the golden-master index, which qits-pact-consumer takes for a form-like text
     *       body and sends as it stands under the recorded {@code application/json}. A recording
     *       that holds it unquoted ({@code 2100.1.0}, qits-artifacts before 2026.1019.62657) is not
     *       JSON, so pact-jvm can neither match nor write it: under a JSON media type such a text
     *       node is the JSON string it was recorded as, and that is the body here. A recording that
     *       holds it quoted is JSON already and goes as it is.</li>
     *   <li>A form field the row holds to a regex ({@link CliRow#fieldMatches}) gets that matcher;
     *       the recorded value stays the example. Where the recording names that value as a state
     *       param ({@code redirect_uri={redirectUri}}), the field is also generated from it, so the
     *       provider is sent its own value rather than the example.</li>
     * </ul>
     */
    public V4Pact pact(List<CliRow> selected) {
        V4Pact pact = pact().pact(selected.stream().map(CliRow::interaction).toList());
        for (CliRow row : selected) {
            String type = row.interaction().requestHeaders().get("Content-Type");
            var op = masters.operation(row.state(), row.operationId());
            boolean jsonString = op.binaryBody() == null && op.body() != null && op.body().isTextual()
                    && op.contentType() != null && isJson(op.contentType()) && !parses(op.body().asText());
            if (type == null && !jsonString && row.fieldsMatching().isEmpty()) {
                continue;
            }
            for (var interaction : pact.getInteractions()) {
                if (interaction instanceof V4Interaction.SynchronousHttp http
                        && http.getDescription().equals(row.description())
                        && http.getProviderStates().getFirst().getName().equals(row.state())) {
                    var request = http.getRequest();
                    if (jsonString) {
                        request.setBody(OptionalBody.body(
                                quoted(op.example(op.body().asText())).getBytes(StandardCharsets.UTF_8),
                                new ContentType(op.contentType())));
                    }
                    row.fieldsMatching().forEach((field, regex) -> {
                        request.getMatchingRules().addCategory("body").addRule("$." + field,
                                new au.com.dius.pact.core.model.matchingrules.RegexMatcher(regex));
                        String recorded = formField(op, field);
                        if (recorded != null && op.hasParam(recorded)) {
                            request.getGenerators().addGenerator(
                                    au.com.dius.pact.core.model.generators.Category.BODY, "$." + field,
                                    new au.com.dius.pact.core.model.generators.ProviderStateGenerator(
                                            op.expression(recorded),
                                            au.com.dius.pact.core.support.expressions.DataType.STRING));
                        }
                    });
                    if (type != null) {
                        request.getHeaders().put("Content-Type", List.of(type));
                        OptionalBody body = request.getBody();
                        if (body.isPresent()) {
                            request.setBody(OptionalBody.body(body.unwrap(), new ContentType(type)));
                        }
                    }
                }
            }
        }
        return pact;
    }

    /** The recorded value of form field {@code name}, a {@code {param}} as recorded; null without one. */
    private static String formField(eu.wohlben.qits.pact.consumer.GoldenMasters.Operation op, String name) {
        if (op.body() == null || !op.body().isTextual()) {
            return null;
        }
        for (String pair : op.body().asText().split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }

    private static boolean isJson(String mediaType) {
        String media = mediaType.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
        return media.equals("application/json") || media.endsWith("+json");
    }

    /** Whether {@code text} is a JSON document already: a recording that holds the string quoted, {@code "\"2100.1.0\""}. */
    private static boolean parses(String text) {
        try {
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(text);
            return true;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return false;
        }
    }

    private static String quoted(String text) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(text);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * {@link #pact(List)} of the live rows as the committed file holds it, normalised: what {@code
     * everyInteractionCarriesBothReferences} reads, rather than qits-pact-consumer's own pact, which
     * pact-jvm cannot write while it holds a JSON string body uncorrected.
     */
    public com.fasterxml.jackson.databind.JsonNode normalisedPact() {
        StringWriter out = new StringWriter();
        try (PrintWriter writer = new PrintWriter(out)) {
            DefaultPactWriter.INSTANCE.writePact(pact(live()), writer, PactSpecVersion.V4);
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(ConsumerPact.normalise(out.toString()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@link ConsumerPact#run}, over {@link #pact(List)}: the call against a mock answering {@code row} alone. */
    public void run(CliRow row) {
        var op = masters.operation(row.state(), row.operationId());
        PactVerificationResult result = ConsumerPactRunnerKt.runConsumerTest(pact(List.of(row)),
                MockProviderConfig.createDefault(PactSpecVersion.V4), (mockServer, context) -> {
                    row.call().run(mockServer.getUrl(), op);
                    return null;
                });
        if (result instanceof PactVerificationResult.Error error) {
            if (error.getError() instanceof AssertionError assertion) {
                throw assertion;
            }
            Assertions.fail(row.description() + ": " + error.getError(), error.getError());
        }
        if (!(result instanceof PactVerificationResult.Ok)) {
            Assertions.fail(row.description() + ": " + result.getDescription() + " — " + result);
        }
    }

    /**
     * {@link ConsumerPact#compareOrWritePactFile}, over {@link #pact(List)}: the live rows' pact,
     * normalised, against the committed {@code pacts/<file>}; {@code -Dgolden.update=true} writes it.
     * With no live row, no file may be committed.
     */
    public void compareOrWritePactFile() {
        Path committed = ConsumerPact.pactsDirectory().resolve(file());
        if (live().isEmpty()) {
            Assertions.assertFalse(Files.exists(committed), committed + " is committed, but no row of it is recorded");
            org.junit.jupiter.api.Assumptions.abort("no pact to write yet: "
                    + pending().stream().map(this::needs).toList());
        }
        try {
            StringWriter out = new StringWriter();
            try (PrintWriter writer = new PrintWriter(out)) {
                DefaultPactWriter.INSTANCE.writePact(pact(live()), writer, PactSpecVersion.V4);
            }
            Path scratch = Path.of("target", "pacts", file());
            Files.createDirectories(scratch.getParent());
            Files.writeString(scratch, out.toString());
            GoldenFiles.compareOrWrite(committed, ConsumerPact.normalise(out.toString()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The {@code paths} the recording of (state, operation) holds, in at least one element where a
     * path crosses an array: a command reads an optional field only where the service sends it, so
     * a field the recording leaves out is not one the pact can demand. All of them while the
     * provider has not recorded the pair, so a pending row still says everything it will read.
     */
    public List<String> held(String state, String operationId, List<String> paths) {
        if (!masters.recorded(state, operationId)) {
            return List.copyOf(paths);
        }
        com.fasterxml.jackson.databind.JsonNode body = masters.json(state, operationId);
        var op = masters.operation(state, operationId);
        return paths.stream().filter(p -> holds(body, steps(op.example(p)))).toList();
    }

    private static List<String> steps(String path) {
        List<String> out = new ArrayList<>();
        for (String segment : path.split("\\.")) {
            String key = segment.replace("[]", "");
            if (!key.isEmpty()) {
                out.add(key);
            }
            for (int i = segment.indexOf("[]"); i >= 0; i = segment.indexOf("[]", i + 2)) {
                out.add("[]");
            }
        }
        return out;
    }

    private static boolean holds(com.fasterxml.jackson.databind.JsonNode node, List<String> steps) {
        if (steps.isEmpty()) {
            return true;
        }
        String step = steps.getFirst();
        List<String> rest = steps.subList(1, steps.size());
        if (step.equals("[]")) {
            for (com.fasterxml.jackson.databind.JsonNode element : node) {
                if (node.isArray() && holds(element, rest)) {
                    return true;
                }
            }
            return false;
        }
        return node.isObject() && node.has(step) && holds(node.get(step), rest);
    }
}
