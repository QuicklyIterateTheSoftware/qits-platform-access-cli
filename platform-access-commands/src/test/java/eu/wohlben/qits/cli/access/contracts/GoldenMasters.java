package eu.wohlben.qits.cli.access.contracts;

import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.Matchers;
import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.consumer.dsl.PactDslJsonArray;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslJsonRootValue;
import au.com.dius.pact.core.model.matchingrules.NullMatcher;
import au.com.dius.pact.core.model.matchingrules.RegexMatcher;
import au.com.dius.pact.core.model.matchingrules.TypeMatcher;
import au.com.dius.pact.core.support.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>qits-projects' recorded answers, as the commands' consumer pact reads them</b> (epics qits-546
 * and qits-965).
 * <p>
 * qits-projects records what it answers for each provider state — {@code golden-masters/index.json}
 * plus one JSON per (state, operation) — and publishes the tree as {@code
 * eu.wohlben.qits:qits-projects-golden-masters}, a test-scoped pin in the root pom. This class reads
 * it off the classpath and turns one recorded (state, operation) into a pact-jvm V4 interaction,
 * with matchers derived from the index's {@code frozen} lists. It is qits-workspaces-service's
 * {@code testing/contracts/GoldenMasters}, kept in step by hand (the two repositories share no test
 * library), with what the CLI needs on top: a recorded request body, and an object whose keys the
 * recorder froze.
 * <p>
 * <b>The frozen lists are read from the index, never inferred from a value's shape.</b> A string
 * that happens to look like a UUID is still type-matched unless the recorder listed its path under
 * {@code frozen.ids}. The mapping:
 * <ul>
 *   <li>{@code frozen.ids} — the whole value is a UUID: {@code uuid} matcher;
 *   <li>{@code frozen.instants} — an ISO-8601 timestamp: a regex matcher ({@link #ISO_INSTANT});
 *   <li>{@code frozen.strings} — a string carrying a frozen value: type match;
 *   <li>{@code frozen.keys} — an object keyed by a frozen value ({@code transitionWork}'s answer,
 *       keyed by qualified id): a {@code values} matcher, so any key is accepted and every value is
 *       matched against one template; the paths beneath it name the key {@code *};
 *   <li>{@code frozen.listFilteredTo} — the array the recorder reduced to the state's own entities:
 *       {@code minArrayLike(recorded length)}, "contains", never equals;
 *   <li>every other leaf — type match ({@code null} only where the recording holds nothing else).
 * </ul>
 * <p>
 * <b>Every other non-empty array is {@code minMaxArrayLike(n, n)}</b> with n the recorded length,
 * its template the merge of every recorded element (a leaf null in one element and a string in
 * another becomes {@code type OR null}). <b>Elements that disagree on which keys they carry are
 * matched element by element instead</b>, each in its place against its own recording: a merged
 * template requires every key of every element on every element (pact compares a template's keys
 * as present), so {@code listProjectWork}'s epics and tickets, which carry {@code blocked}, would
 * otherwise demand it of its features and tasks, which do not (qits-965). Absent is not null.
 * <b>An array nested in a merged template whose elements recorded it at different lengths</b>
 * ({@code commits[*].parents}: three on a merge commit, one elsewhere) is {@code minArrayLike} of
 * the shortest length with no maximum, since no one exact length holds for every element (qits-893).
 * <p>
 * <b>A request body is the recorded one, exactly.</b> A value the recorder wrote as {@code
 * "{param}"} is the state's frozen example here and a provider-state expression ({@code
 * ${param}}) for the verifier. A MEMBER NAME written that way ({@code transitionWork}'s {@code
 * {"{qualifiedId}": …}}) is the frozen example only: pact-jvm 4.6 generates values, never keys, so
 * the verifier receives the frozen key as it stands.
 */
public final class GoldenMasters {

    /** The consumer, as the pact names it: the repository name, never an application name. */
    public static final String CONSUMER = "qits-platform-access-cli";

    /** The provider, as the pact names it: the repository name, never the bare application name. */
    public static final String PROVIDER = "qits-projects-service";

    /** The provider as the golden-master index names it: the application name. */
    public static final String INDEX_PROVIDER = "qits-projects";

    /** Where the jar puts the tree on the classpath. */
    public static final String ROOT = "golden-masters/";

    /** An ISO-8601 timestamp, any fraction length, Z or a numeric offset. */
    public static final String ISO_INSTANT =
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?(Z|[+-]\\d{2}:?\\d{2})$";

    private static final String UUID_REGEX =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    private static final Pattern PARAM = Pattern.compile("\\{([A-Za-z0-9_]+)}");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static volatile JsonNode index;

    private GoldenMasters() {
    }

    // --- the index ------------------------------------------------------------------------------

    /** One recorded (state, operation), as the index describes it. */
    public record Operation(
            String state,
            Map<String, String> params,
            String operationId,
            String method,
            String path,
            JsonNode body,
            int status,
            String file,
            Set<String> ids,
            Set<String> instants,
            Set<String> strings,
            Set<String> keys,
            String listFilteredTo) {

        /** The path with every {@code {param}} replaced by the state's frozen example. */
        public String examplePath() {
            return substitute(path, params::get);
        }

        /** The path as a provider-state expression: {@code {param}} becomes {@code ${param}}. */
        public String expressionPath() {
            return substitute(path, name -> "${" + name + "}");
        }

        /** The recorded request body with every {@code "{param}"} expanded, or null when it has none. */
        public JsonNode exampleBody() {
            return body == null ? null : expand(body);
        }

        private JsonNode expand(JsonNode node) {
            if (node.isTextual()) {
                return MAPPER.getNodeFactory().textNode(substitute(node.asText(), params::get));
            }
            if (node.isObject()) {
                var out = MAPPER.createObjectNode();
                node.properties().forEach(e -> out.set(substitute(e.getKey(), params::get), expand(e.getValue())));
                return out;
            }
            if (node.isArray()) {
                var out = MAPPER.createArrayNode();
                node.forEach(e -> out.add(expand(e)));
                return out;
            }
            return node.deepCopy();
        }

        String substitute(String template, Function<String, String> value) {
            Matcher m = PARAM.matcher(template);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                String name = m.group(1);
                if (!params.containsKey(name)) {
                    throw new IllegalStateException("golden master " + state + "/" + operationId + ": " + template
                            + " names {" + name + "}, which the state's params do not hold");
                }
                m.appendReplacement(out, Matcher.quoteReplacement(value.apply(name)));
            }
            m.appendTail(out);
            return out.toString();
        }

        boolean hasParam(String template) {
            return PARAM.matcher(template).find();
        }
    }

    /** The provider state's frozen example params (e.g. {@code qualifiedId}). */
    public static Map<String, String> params(String state) {
        Map<String, String> params = new LinkedHashMap<>();
        stateNode(state).path("params").properties().forEach(e -> params.put(e.getKey(), e.getValue().asText()));
        return params;
    }

    /** The index entry for one (state, operation); fails naming both when the index has none. */
    public static Operation operation(String state, String operationId) {
        JsonNode stateNode = stateNode(state);
        for (JsonNode op : stateNode.path("operations")) {
            if (operationId.equals(op.path("operationId").asText())) {
                JsonNode frozen = op.path("frozen");
                JsonNode filtered = frozen.path("listFilteredTo");
                JsonNode body = op.get("body");
                return new Operation(
                        state,
                        params(state),
                        operationId,
                        op.path("method").asText(),
                        op.path("path").asText(),
                        body == null || body.isNull() ? null : body,
                        op.path("status").asInt(),
                        op.path("file").asText(),
                        strings(frozen.path("ids")),
                        strings(frozen.path("instants")),
                        strings(frozen.path("strings")),
                        strings(frozen.path("keys")),
                        filtered.isTextual() ? filtered.asText() : null);
            }
        }
        throw new IllegalArgumentException(
                "qits-projects' golden masters record no operation " + operationId + " in state '" + state + "'");
    }

    /** The recorded JSON for one (state, operation), byte for byte as the jar carries it. */
    public static String body(String state, String operationId) {
        return resource(ROOT + operation(state, operationId).file());
    }

    /** {@link #body}, parsed — a fresh tree each call, so a caller may edit it. */
    public static JsonNode json(String state, String operationId) {
        try {
            return MAPPER.readTree(body(state, operationId));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --- the pact -------------------------------------------------------------------------------

    /**
     * What made the CLI make the call — the {@code qits-trigger} reference every interaction carries.
     * For this consumer it is always a command a person or an agent types, so the kind is {@code
     * command} and its key {@code command}: {@code qits work details}, {@code qits work comment
     * create}. The command, not its options: one command's calls are one trigger.
     */
    public record Trigger(String kind, String app, String key, String value) {

        public Trigger {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(app, "app");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
        }

        public static Trigger command(String command) {
            return new Trigger("command", CONSUMER, "command", command);
        }

        /** The {@code qits-trigger} group, all values strings, in a fixed key order. */
        public Map<String, String> reference() {
            Map<String, String> ref = new LinkedHashMap<>();
            ref.put("kind", kind);
            ref.put("app", app);
            ref.put(key, value);
            return ref;
        }
    }

    /** The interaction's description: the trigger first, so (description, state) stays unique. */
    public static String description(String operationId, Trigger trigger) {
        return trigger.value() + ": " + operationId;
    }

    /**
     * Add the V4 HTTP interaction for one recorded (state, operation), reached from {@code trigger}.
     * <p>
     * {@code given(state, params)}; the request path from the index template, each {@code {param}}
     * as a provider-state expression with the frozen example; the recorded request body, if any,
     * sent as {@code contentType}; the status exact; the answer from the recording with the matchers
     * the class javadoc lists; and the per-interaction {@code comments.references} — {@code
     * qits-call} (the provider operation) and {@code qits-trigger}.
     *
     * @param contentType the request's media type, as the CLI sends it; ignored without a body
     * @throws NullPointerException when {@code trigger} is null: an interaction nobody can attribute
     *     to an entry point is exactly what the references exist to prevent
     */
    public static PactBuilder interaction(
            PactBuilder builder, String state, String operationId, Trigger trigger, String contentType) {
        Objects.requireNonNull(trigger, "trigger: every interaction names the entry point that makes it");
        Operation op = operation(state, operationId);
        DslPart answer = responseBody(op);
        DslPart request = op.body() == null ? null : requestBody(op);
        Map<String, Object> references = new LinkedHashMap<>();
        Map<String, String> call = new LinkedHashMap<>();
        call.put("app", PROVIDER);
        call.put("operationId", operationId);
        references.put("qits-call", call);
        references.put("qits-trigger", trigger.reference());
        return builder.expectsToReceiveHttpInteraction(description(operationId, trigger), http -> {
            http.state(state, new LinkedHashMap<String, Object>(op.params()));
            http.withRequest(r -> {
                r.method(op.method());
                if (op.hasParam(op.path())) {
                    r.path(Matchers.fromProviderState(op.expressionPath(), op.examplePath()));
                } else {
                    r.path(op.path());
                }
                if (request != null) {
                    r.header("Content-Type", contentType);
                    r.body(request);
                }
                return r;
            });
            http.willRespondWith(response -> response
                    .status(op.status())
                    .header("Content-Type", Matchers.regexp("application/json.*", "application/json"))
                    .body(answer));
            // pact-jvm 4.6's DSL has no setter for an arbitrary comment group (only `comment(text)`
            // and the test name), but the V4 model's comments map is mutable and written verbatim.
            http.getInteraction().getComments().put("references", Json.toJson(references));
            return http;
        });
    }

    /** The recorded answer with the index's matchers, built for {@link #interaction}. */
    static DslPart responseBody(Operation op) {
        return responseBody(op, json(op.state(), op.operationId()));
    }

    /** The one operation whose {@code phases.*.flow} arrays {@link #unboundedFlow} rewrites. */
    private static final String ARCHETYPE_REGISTRY_OPERATION = "listWorkArchetypes";

    /** {@link #responseBody(Operation)} over a given recording rather than the jar's. */
    static DslPart responseBody(Operation op, JsonNode recorded) {
        if (!recorded.isObject()) {
            throw new IllegalStateException("golden master " + op.state() + "/" + op.operationId()
                    + ": only an object body is supported, got " + recorded.getNodeType());
        }
        PactDslJsonBody root = new PactDslJsonBody();
        Shape flowTemplate = ARCHETYPE_REGISTRY_OPERATION.equals(op.operationId())
                ? canonicalFlowStep(op, recorded) : null;
        fillObject(root, Shape.of(recorded), "$", op, flowTemplate);
        return root;
    }

    // --- the request body -----------------------------------------------------------------------

    /** The recorded request, values exact, a {@code "{param}"} value a provider-state expression. */
    static DslPart requestBody(Operation op) {
        if (!op.body().isObject()) {
            throw new IllegalStateException("golden master " + op.state() + "/" + op.operationId()
                    + ": only an object request body is supported, got " + op.body().getNodeType());
        }
        PactDslJsonBody root = new PactDslJsonBody();
        requestObject(root, op.body(), op);
        return root;
    }

    private static void requestObject(PactDslJsonBody target, JsonNode recorded, Operation op) {
        for (Map.Entry<String, JsonNode> field : recorded.properties()) {
            String name = op.substitute(field.getKey(), op.params()::get);
            JsonNode value = field.getValue();
            if (value.isObject()) {
                PactDslJsonBody nested = target.object(name);
                requestObject(nested, value, op);
                nested.closeObject();
            } else if (value.isArray()) {
                PactDslJsonArray nested = target.array(name);
                requestArray(nested, value, op);
                nested.closeArray();
            } else if (value.isNull()) {
                target.nullValue(name);
            } else if (value.isTextual() && op.hasParam(value.asText())) {
                target.valueFromProviderState(name, op.substitute(value.asText(), p -> "${" + p + "}"),
                        op.substitute(value.asText(), op.params()::get));
            } else if (value.isTextual()) {
                target.stringValue(name, value.asText());
            } else if (value.isNumber()) {
                target.numberValue(name, value.numberValue());
            } else if (value.isBoolean()) {
                target.booleanValue(name, value.asBoolean());
            } else {
                throw new IllegalStateException("golden master " + op.state() + "/" + op.operationId()
                        + ": a " + value.getNodeType() + " in the request body");
            }
        }
    }

    private static void requestArray(PactDslJsonArray target, JsonNode recorded, Operation op) {
        for (JsonNode value : recorded) {
            if (value.isObject()) {
                PactDslJsonBody nested = target.object();
                requestObject(nested, value, op);
                nested.closeObject();
            } else if (value.isArray()) {
                PactDslJsonArray nested = target.array();
                requestArray(nested, value, op);
                nested.closeArray();
            } else if (value.isNull()) {
                target.nullValue();
            } else if (value.isTextual() && op.hasParam(value.asText())) {
                target.valueFromProviderState(op.substitute(value.asText(), p -> "${" + p + "}"),
                        op.substitute(value.asText(), op.params()::get));
            } else if (value.isTextual()) {
                target.stringValue(value.asText());
            } else if (value.isNumber()) {
                target.numberValue(value.numberValue());
            } else if (value.isBoolean()) {
                target.booleanValue(value.asBoolean());
            } else {
                throw new IllegalStateException("golden master " + op.state() + "/" + op.operationId()
                        + ": a " + value.getNodeType() + " in the request body");
            }
        }
    }

    // --- the answer -----------------------------------------------------------------------------

    /**
     * {@code flowTemplate} is non-null only while building {@link #ARCHETYPE_REGISTRY_OPERATION}'s
     * answer: the one shape {@link #unboundedFlow} gives every {@code phases.*.flow} array,
     * regardless of what this particular status recorded. It is threaded through rather than
     * recomputed, because once {@link #array} is reached the original document is gone — only the
     * {@link Shape} tree survives.
     */
    private static void fillObject(PactDslJsonBody target, Shape shape, String path, Operation op, Shape flowTemplate) {
        boolean keyed = op.keys().contains(path);
        for (Map.Entry<String, Shape> field : shape.fields.entrySet()) {
            String name = field.getKey();
            Shape child = field.getValue();
            if (keyed) {
                // The key is a frozen value: any key, every value against one template.
                if (child.kind != Shape.Kind.OBJECT || child.nullable) {
                    throw unsupported(op, path + ".*", "a keyed " + child.kind + "; only object values are");
                }
                PactDslJsonBody value = target.eachKeyLike(name);
                fillObject(value, child, path + ".*", op, flowTemplate);
                value.closeObject();
                continue;
            }
            String childPath = path + "." + name;
            switch (child.kind) {
                case NULL -> target.nullValue(name);
                case LEAF -> leaf(target, name, child, childPath, op);
                case OBJECT -> {
                    if (child.nullable) {
                        throw unsupported(op, childPath, "an object that is null in some elements");
                    }
                    PactDslJsonBody nested = target.object(name);
                    fillObject(nested, child, childPath, op, flowTemplate);
                    nested.closeObject();
                }
                case ARRAY -> array(target, name, child, childPath, op, flowTemplate);
                default -> throw unsupported(op, childPath, "a " + child.kind);
            }
        }
    }

    private static void leaf(PactDslJsonBody target, String name, Shape leaf, String path, Operation op) {
        JsonNode example = leaf.example;
        if (op.ids().contains(path)) {
            requireText(example, path, op, "ids");
            if (leaf.nullable) {
                target.or(name, example.asText(), new RegexMatcher(UUID_REGEX, example.asText()), NullMatcher.INSTANCE);
            } else {
                target.uuid(name, example.asText());
            }
        } else if (op.instants().contains(path)) {
            requireText(example, path, op, "instants");
            if (leaf.nullable) {
                target.or(name, example.asText(), new RegexMatcher(ISO_INSTANT, example.asText()), NullMatcher.INSTANCE);
            } else {
                target.stringMatcher(name, ISO_INSTANT, example.asText());
            }
        } else if (leaf.nullable) {
            // frozen.strings or an ordinary leaf: type match, widened to null where a sibling had null.
            target.or(name, scalar(example), TypeMatcher.INSTANCE, NullMatcher.INSTANCE);
        } else if (example.isTextual()) {
            target.stringType(name, example.asText());
        } else if (example.isNumber()) {
            target.numberType(name, example.numberValue());
        } else if (example.isBoolean()) {
            target.booleanType(name, example.asBoolean());
        } else {
            throw unsupported(op, path, "a " + example.getNodeType() + " leaf");
        }
    }

    private static void array(PactDslJsonBody target, String name, Shape array, String path, Operation op, Shape flowTemplate) {
        if (flowTemplate != null && name.equals("flow") && path.contains(".phases.")) {
            // phases.*.flow, listWorkArchetypes only: see unboundedFlow. Bypasses every rule below,
            // length and recorded content alike — a flow array is never pinned to either.
            unboundedFlow(target, name, flowTemplate, path, op);
            return;
        }
        if (array.nullable) {
            throw unsupported(op, path, "an array that is null in some elements");
        }
        boolean filtered = path.equals(op.listFilteredTo());
        int n = array.length;
        if (array.maxLength != n) {
            openEnded(target, name, array, path, op, flowTemplate);
            return;
        }
        if (n == 0) {
            // Nothing to build a template from: the recording says "empty", and an empty array with
            // no rule is compared as exactly that.
            target.array(name).closeArray();
            return;
        }
        Shape element = array.element;
        String elementPath = path + "[*]";
        if (!array.mergeable || !expressible(element)) {
            // Elements one template cannot hold (the registry's archetypes, each with its own
            // statuses): each element is matched in its place, by type, against its own recording.
            PactDslJsonArray positional = target.array(name);
            positional(positional, array.items, elementPath, op, flowTemplate);
            positional.closeArray();
            return;
        }
        switch (element.kind) {
            case OBJECT -> {
                PactDslJsonBody template = filtered ? target.minArrayLike(name, n, n) : target.minMaxArrayLike(name, n, n, n);
                fillObject(template, element, elementPath, op, flowTemplate);
                DslPart closed = template.closeObject();
                ((PactDslJsonArray) closed).closeArray();
            }
            case LEAF -> {
                PactDslJsonRootValue value = rootLeaf(element, elementPath, op);
                if (filtered) {
                    target.minArrayLike(name, n, value, n);
                } else {
                    target.minMaxArrayLike(name, n, n, value, n);
                }
            }
            default -> throw unsupported(op, elementPath, "an array of " + element.kind);
        }
    }

    /**
     * An array nested in a merged template whose recorded elements disagree on its length (qits-893):
     * {@code listReleaseRequestCommits}' {@code commits[*].parents} is three long on the fold's merge
     * commit and one long on every other commit, and {@code commits[*].files} is empty on the merge
     * and one long elsewhere. pact-jvm holds one rule per path, so {@code minMaxArrayLike(n, n)} with
     * any one n contradicts some element of the provider's own recording — the shortest length, which
     * the merge used to keep, failed the merge commit's three parents. Such an array is matched by type
     * with the shortest recorded length as its minimum and no maximum, its template the merge of every
     * element it holds anywhere in the recording, so even an array that is empty on the first element
     * ({@code files}) borrows its element shape from a sibling instead of being pinned to empty. An
     * array whose recorded lengths agree keeps its exact length, as {@link #array} pins it.
     */
    private static void openEnded(
            PactDslJsonBody target, String name, Shape array, String path, Operation op, Shape flowTemplate) {
        int min = array.length;
        int examples = Math.max(min, 1);
        Shape element = array.element;
        String elementPath = path + "[*]";
        if (element == null || !array.mergeable || !expressible(element)) {
            throw unsupported(op, path, "an array whose elements disagree on both its length and its element shape");
        }
        switch (element.kind) {
            case OBJECT -> {
                PactDslJsonBody template = target.minArrayLike(name, min, examples);
                fillObject(template, element, elementPath, op, flowTemplate);
                DslPart closed = template.closeObject();
                ((PactDslJsonArray) closed).closeArray();
            }
            case LEAF -> target.minArrayLike(name, min, rootLeaf(element, elementPath, op), examples);
            default -> throw unsupported(op, elementPath, "an array of " + element.kind);
        }
    }

    /**
     * {@code phases.*.flow}, for {@link #ARCHETYPE_REGISTRY_OPERATION} only (qits-1075):
     * qits-projects is about to lengthen every lifecycle archetype's flow — {@code REPORTED} from
     * one step to three, {@code REFINED} from no steps at all to two — so pinning today's length,
     * the way {@link #array} pins every other array, would fail the moment the provider ships.
     * Every flow step carries the same four fields no matter how many steps a status has, so the
     * whole array is matched by type with no length at all ({@code minArrayLike(name, 0, …)}), and
     * its one template element is {@code flowTemplate}, which {@link #canonicalFlowStep} merges
     * from every step the recording carries anywhere in the document — so a status recorded with
     * no steps at all today ({@code REFINED}) still gets an element to match its future steps by
     * type, borrowed rather than invented.
     */
    private static void unboundedFlow(PactDslJsonBody target, String name, Shape flowTemplate, String path, Operation op) {
        PactDslJsonBody template = target.minArrayLike(name, 0, 1);
        fillObject(template, flowTemplate, path + "[*]", op, null);
        DslPart closed = template.closeObject();
        ((PactDslJsonArray) closed).closeArray();
    }

    /**
     * The shape of one flow step, merged ({@link Shape#merge}) from every step {@code
     * phases.*.flow} carries anywhere in {@code recorded} — both archetypes today, every status.
     * {@link #unboundedFlow} reuses the one result as the template for every flow array, which is
     * what lets an empty one (REFINED's, today) match a step it has never recorded: the merge
     * widens a field that is null in one step and a value in another (REPORTED's {@code refine}
     * step holds no {@code enters}; READY_FOR_DEV's {@code implement} step does) to type-or-null,
     * same as {@link #leaf} does for any other field, rather than pinning it to the one recording
     * happens to hold.
     */
    private static Shape canonicalFlowStep(Operation op, JsonNode recorded) {
        Shape merged = null;
        for (JsonNode archetype : recorded.path("archetypes")) {
            for (JsonNode phase : archetype.path("phases")) {
                for (JsonNode step : phase.path("flow")) {
                    merged = merged == null ? Shape.of(step) : Shape.merge(merged, Shape.of(step));
                }
            }
        }
        if (merged == null) {
            throw unsupported(op, "$.archetypes[*].phases.*.flow",
                    "empty in every status of every archetype, leaving no flow step to borrow a shape from");
        }
        return merged;
    }

    /**
     * Whether one merged template can stand for every element: every key present in every element
     * (a key some element lacks cannot sit in a template that pact requires of each), nothing that is
     * an object or an array in one element and null in another, and every array beneath mergeable
     * itself.
     */
    private static boolean expressible(Shape shape) {
        return switch (shape.kind) {
            case OBJECT -> shape.fields.values().stream().allMatch(f -> !f.absent
                    && !((f.kind == Shape.Kind.OBJECT || f.kind == Shape.Kind.ARRAY) && f.nullable) && expressible(f));
            case ARRAY -> shape.mergeable && (shape.element == null || expressible(shape.element));
            default -> true;
        };
    }

    /** Each element in its place, a leaf by type (or by the index's matcher), as recorded. */
    private static void positional(
            PactDslJsonArray target, List<Shape> items, String path, Operation op, Shape flowTemplate) {
        for (Shape item : items) {
            switch (item.kind) {
                case NULL -> target.nullValue();
                case LEAF -> {
                    JsonNode example = item.example;
                    if (op.ids().contains(path)) {
                        requireText(example, path, op, "ids");
                        target.uuid(example.asText());
                    } else if (op.instants().contains(path)) {
                        requireText(example, path, op, "instants");
                        target.stringMatcher(ISO_INSTANT, example.asText());
                    } else if (example.isTextual()) {
                        target.stringType(example.asText());
                    } else if (example.isNumber()) {
                        target.numberType(example.numberValue());
                    } else if (example.isBoolean()) {
                        target.booleanType(example.asBoolean());
                    } else {
                        throw unsupported(op, path, "a " + example.getNodeType() + " array element");
                    }
                }
                case OBJECT -> {
                    PactDslJsonBody object = target.object();
                    fillObject(object, item, path, op, flowTemplate);
                    object.closeObject();
                }
                case ARRAY -> {
                    PactDslJsonArray inner = target.array();
                    positional(inner, item.items, path + "[*]", op, flowTemplate);
                    inner.closeArray();
                }
                default -> throw unsupported(op, path, "a " + item.kind);
            }
        }
    }

    private static PactDslJsonRootValue rootLeaf(Shape leaf, String path, Operation op) {
        if (leaf.nullable) {
            throw unsupported(op, path, "an array holding nulls");
        }
        JsonNode example = leaf.example;
        if (op.ids().contains(path)) {
            requireText(example, path, op, "ids");
            return PactDslJsonRootValue.uuid(example.asText());
        }
        if (op.instants().contains(path)) {
            requireText(example, path, op, "instants");
            return PactDslJsonRootValue.stringMatcher(ISO_INSTANT, example.asText());
        }
        if (example.isTextual()) {
            return PactDslJsonRootValue.stringType(example.asText());
        }
        if (example.isNumber()) {
            return PactDslJsonRootValue.numberType(example.numberValue());
        }
        if (example.isBoolean()) {
            return PactDslJsonRootValue.booleanType(example.asBoolean());
        }
        throw unsupported(op, path, "a " + example.getNodeType() + " array element");
    }

    private static Object scalar(JsonNode example) {
        if (example.isTextual()) {
            return example.asText();
        }
        if (example.isNumber()) {
            return example.numberValue();
        }
        if (example.isBoolean()) {
            return example.asBoolean();
        }
        throw new IllegalStateException("not a scalar: " + example);
    }

    private static void requireText(JsonNode example, String path, Operation op, String list) {
        if (!example.isTextual()) {
            throw new IllegalStateException("golden master " + op.state() + "/" + op.operationId() + ": frozen." + list
                    + " names " + path + ", which holds " + example.getNodeType() + ", not a string");
        }
    }

    private static IllegalStateException unsupported(Operation op, String path, String what) {
        return new IllegalStateException("golden master " + op.state() + "/" + op.operationId() + ": " + path + " is "
                + what + ", which GoldenMasters cannot express as a pact matcher yet");
    }

    /**
     * The structure of a recorded value, with an array's elements MERGED into one template: field
     * union, a leaf's first non-null example, and {@code nullable} wherever any element held null
     * (or lacked the field). Used for structure and examples only — which matcher a leaf gets is the
     * index's decision, by path.
     */
    private static final class Shape {
        enum Kind {
            NULL,
            LEAF,
            OBJECT,
            ARRAY
        }

        Kind kind;
        boolean nullable;
        /** A merged object field that some element does not carry at all: absent, not null. */
        boolean absent;
        JsonNode example;
        final LinkedHashMap<String, Shape> fields = new LinkedHashMap<>();
        Shape element;
        /** An array's length; once merged, the SHORTEST any element recorded. */
        int length;
        /** Once merged, the LONGEST length any element recorded; differs from {@link #length} only then. */
        int maxLength;
        /** An array's elements, each its own shape, unmerged: the positional fallback's input. */
        final List<Shape> items = new ArrayList<>();
        /** Whether an array's elements merge into one template at all (no OBJECT beside a LEAF). */
        boolean mergeable = true;

        static Shape of(JsonNode node) {
            Shape shape = new Shape();
            if (node == null || node.isNull() || node.isMissingNode()) {
                shape.kind = Kind.NULL;
                shape.nullable = true;
            } else if (node.isObject()) {
                shape.kind = Kind.OBJECT;
                Iterator<Map.Entry<String, JsonNode>> it = node.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    shape.fields.put(e.getKey(), of(e.getValue()));
                }
            } else if (node.isArray()) {
                shape.kind = Kind.ARRAY;
                shape.length = node.size();
                shape.maxLength = node.size();
                for (JsonNode e : node) {
                    shape.items.add(of(e));
                    if (shape.mergeable) {
                        try {
                            shape.element = shape.element == null ? of(e) : merge(shape.element, of(e));
                        } catch (IllegalStateException disagree) {
                            shape.mergeable = false;
                            shape.element = null;
                        }
                    }
                }
            } else {
                shape.kind = Kind.LEAF;
                shape.example = node;
            }
            return shape;
        }

        static Shape merge(Shape a, Shape b) {
            if (a.kind == Kind.NULL) {
                b.nullable = true;
                b.absent |= a.absent;
                return b;
            }
            if (b.kind == Kind.NULL) {
                a.nullable = true;
                a.absent |= b.absent;
                return a;
            }
            if (a.kind != b.kind) {
                throw new IllegalStateException("golden master array elements disagree: " + a.kind + " and " + b.kind);
            }
            a.nullable |= b.nullable;
            a.absent |= b.absent;
            switch (a.kind) {
                case OBJECT -> {
                    List<String> keys = new ArrayList<>(a.fields.keySet());
                    for (String key : b.fields.keySet()) {
                        if (!keys.contains(key)) {
                            keys.add(key);
                        }
                    }
                    LinkedHashMap<String, Shape> merged = new LinkedHashMap<>();
                    for (String key : keys) {
                        Shape left = a.fields.get(key);
                        Shape right = b.fields.get(key);
                        Shape field = left == null ? merge(of(null), right)
                                : right == null ? merge(left, of(null)) : merge(left, right);
                        field.absent |= left == null || right == null;
                        merged.put(key, field);
                    }
                    a.fields.clear();
                    a.fields.putAll(merged);
                }
                case ARRAY -> {
                    a.length = Math.min(a.length, b.length);
                    a.maxLength = Math.max(a.maxLength, b.maxLength);
                    a.mergeable &= b.mergeable;
                    a.element = !a.mergeable ? null
                            : a.element == null ? b.element : b.element == null ? a.element : merge(a.element, b.element);
                }
                default -> {
                    // LEAF: keep a's example; the matcher is a type match, so one example stands for all.
                }
            }
            return a;
        }
    }

    // --- reading the jar ------------------------------------------------------------------------

    private static JsonNode index() {
        JsonNode loaded = index;
        if (loaded == null) {
            try {
                loaded = MAPPER.readTree(resource(ROOT + "index.json"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (loaded.path("formatVersion").asInt() != 1) {
                throw new IllegalStateException("golden-masters/index.json is formatVersion "
                        + loaded.path("formatVersion") + "; GoldenMasters reads formatVersion 1");
            }
            if (!INDEX_PROVIDER.equals(loaded.path("provider").asText())) {
                throw new IllegalStateException("golden-masters/index.json is " + loaded.path("provider")
                        + "'s, not " + INDEX_PROVIDER + "'s");
            }
            index = loaded;
        }
        return loaded;
    }

    private static JsonNode stateNode(String state) {
        for (JsonNode node : index().path("states")) {
            if (state.equals(node.path("name").asText())) {
                return node;
            }
        }
        throw new IllegalArgumentException("qits-projects' golden masters record no state '" + state + "'");
    }

    private static Set<String> strings(JsonNode array) {
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode e : array) {
            out.add(e.asText());
        }
        return Set.copyOf(out);
    }

    private static String resource(String name) {
        ClassLoader loader = GoldenMasters.class.getClassLoader();
        try (InputStream in = loader == null ? null : loader.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException(name + " is not on the test classpath — is"
                        + " eu.wohlben.qits:qits-projects-golden-masters a test dependency of this module?");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
