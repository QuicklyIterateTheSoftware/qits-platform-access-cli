package eu.wohlben.qits.cli.access.projects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.platform.PlatformUrls;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The qits-projects REST API ({@code /projects/api}), read as JSON trees. A tree and not records:
 * the services add fields and grow their word lists, and a tree needs no reflection in the native
 * binary.
 */
public final class ProjectsApi {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Where the service's OpenAPI document is. */
    public static final String OPENAPI = "/projects/q/openapi";

    /** RFC 7396's media type, which the service's PATCH doors consume. */
    public static final String MERGE_PATCH = "application/merge-patch+json";

    private final PlatformClient client;
    private final String base;

    public ProjectsApi(PlatformClient client, String base) {
        this.client = client;
        this.base = base;
    }

    /** Takes this home's credential (refreshing a session if it is due) and finds the service. */
    public static ProjectsApi connect(CliContext context, String projectsUrl) throws CliFailure, InterruptedException {
        return new ProjectsApi(new PlatformClient(context.credential()),
                PlatformUrls.projects(projectsUrl, context.env(), context.idpUrl()));
    }

    /** {@code {"entries":[{"project":{…}}]}}. */
    public JsonNode projects() throws CliFailure, InterruptedException {
        return client.get(uri("/projects/api/projects"));
    }

    /** {@code {"entries":[{"repository":{…},"declared":…}],"wrapper":{…}}}. */
    public JsonNode repositories(String projectId) throws CliFailure, InterruptedException {
        return client.get(uri("/projects/api/projects/" + segment(projectId) + "/repositories"));
    }

    /**
     * {@code {"repository":{…},"projectId":…,"wrapperPath":…}}: a blank repository on the platform's
     * git host, and the wrapper entry that names it, which is the same statement made twice.
     * <p>
     * <b>No {@code archetype} field, and there is no flag for one.</b> The service reads the kind
     * off the name's role suffix ({@code qits-payments-daemon} is a {@code DAEMON}), which under the
     * component layout is where the kind lives. The field exists on the request and is deliberately
     * left unset: sending one would let a caller state a kind the name contradicts, and the row's
     * archetype is the one thing nothing downstream can correct afterwards.
     * <p>
     * A null component lets the wrapper's own layout decide where the entry is mounted.
     */
    public JsonNode createRepository(String projectId, String name, String component)
            throws CliFailure, InterruptedException {
        ObjectNode body = JSON.createObjectNode();
        body.put("name", name);
        if (component != null && !component.isBlank()) {
            body.put("component", component.strip());
        }
        return client.post(uri("/projects/api/projects/" + segment(projectId) + "/repositories"), body);
    }

    /** {@code {"requests":[…]}}. A null state asks for the service's default. */
    public JsonNode releaseRequests(String repoId, String state) throws CliFailure, InterruptedException {
        String query = state == null || state.isBlank() ? "" : "?state=" + URLEncoder.encode(state.strip(), StandardCharsets.UTF_8);
        return client.get(uri("/projects/api/repositories/" + segment(repoId) + "/release-requests" + query));
    }

    /** {@code {"request":{…}}}: new, or the open request that already holds or joined the branch. */
    public JsonNode createReleaseRequest(String repoId, String branch, String summary, String priority)
            throws CliFailure, InterruptedException {
        ObjectNode body = JSON.createObjectNode();
        body.put("branch", branch);
        body.put("summary", summary);
        putPriority(body, priority);
        return client.post(uri("/projects/api/repositories/" + segment(repoId) + "/release-requests"), body);
    }

    /**
     * {@code {"request":{…}}}: the request with the branch on it. A branch already on the request
     * adds nothing; a priority with it states that priority again, and none leaves it as it is.
     * No requester: the service takes the token's.
     */
    public JsonNode joinReleaseRequest(String repoId, String requestId, String branch, String priority)
            throws CliFailure, InterruptedException {
        ObjectNode body = JSON.createObjectNode();
        body.put("branch", branch);
        putPriority(body, priority);
        return client.post(uri("/projects/api/repositories/" + segment(repoId) + "/release-requests/"
                + segment(requestId) + "/sources"), body);
    }

    /**
     * {@code {"request":{…}}}: the request, now WITHDRAWN. A reason only when one is given; without
     * one the service writes its own, which names the caller.
     */
    public JsonNode withdrawReleaseRequest(String repoId, String requestId, String reason)
            throws CliFailure, InterruptedException {
        ObjectNode body = JSON.createObjectNode();
        if (reason != null && !reason.isBlank()) {
            body.put("reason", reason.strip());
        }
        return client.post(uri("/projects/api/repositories/" + segment(repoId) + "/release-requests/"
                + segment(requestId) + "/withdraw"), body);
    }

    /**
     * Where one work item is addressed: {@code /projects/api/work/{entity}}. {@code entity} is its
     * qualified id ({@code qits-100}) or its id, and goes as it was given: the service resolves
     * either, so the CLI never looks an item up to find its UUID.
     */
    static String workPath(String entity) {
        return WORK + "/" + segment(entity);
    }

    /** The work family's root: every item of every archetype, by qualified id. */
    public static final String WORK = "/projects/api/work";

    /** {@code {"entries":[{"comment":{…}}]}}, oldest first: the thread of a work item of any archetype. */
    public JsonNode workComments(String entity) throws CliFailure, InterruptedException {
        return client.get(uri(workPath(entity) + "/comments"));
    }

    /**
     * {@code {"comment":{…}}}: the new comment. The payload goes as the caller wrote it; the service
     * is the one that says what it may hold, and takes the author from the caller's identity.
     */
    public JsonNode addWorkComment(String entity, JsonNode payload) throws CliFailure, InterruptedException {
        return client.post(uri(workPath(entity) + "/comments"), payload);
    }

    /**
     * {@code {"comment":{…}}}: the comment with the merge patch applied, sent as it was written. The
     * path names the item and the comment together, so a comment on another item's thread is the
     * service's 404, the same answer as one that does not exist.
     */
    public JsonNode editWorkComment(String entity, String commentId, JsonNode mergePatch)
            throws CliFailure, InterruptedException {
        return client.patch(uri(workPath(entity) + "/comments/" + segment(commentId)), mergePatch, MERGE_PATCH);
    }

    /** Where one archetype's payload schema for one door is served; {@code door} is create, update or transition. */
    public static String schemaPath(String archetype, String door) {
        return ARCHETYPES + "/" + segment(archetype) + "/schemas/" + segment(door);
    }

    /** Where the archetype registry is served: lifecycles and the legal moves of every status. */
    public static final String ARCHETYPES = WORK + "/archetypes";

    /** A work item of any archetype, flat: {@code {id, archetype, qualifiedId, status, parent, …}}. */
    public JsonNode work(String entity) throws CliFailure, InterruptedException {
        return client.get(uri(workPath(entity)));
    }

    /** {@code {"children":[…]}}: an epic's features or a feature's tasks; empty for any other kind. */
    public JsonNode workChildren(String entity) throws CliFailure, InterruptedException {
        return client.get(uri(workPath(entity) + "/children"));
    }

    /**
     * {@code {"entities":[…]}}: a project's work items, every archetype, in the service's order.
     * {@code project} is its id or its slug, {@code parent} a qualified id or an id. Each filter is
     * left out when null.
     */
    public JsonNode projectWork(String project, String archetype, String status, String parent)
            throws CliFailure, InterruptedException {
        List<String> query = new ArrayList<>();
        addQuery(query, "archetype", archetype);
        addQuery(query, "status", status);
        addQuery(query, "parent", parent);
        return client.get(uri("/projects/api/projects/" + segment(project) + "/work"
                + (query.isEmpty() ? "" : "?" + String.join("&", query))));
    }

    /** The new item, flat. The body is the caller's payload with {@code archetype} set. */
    public JsonNode createWork(JsonNode body) throws CliFailure, InterruptedException {
        return client.post(uri(WORK), body);
    }

    /** The item with the merge patch applied, sent as it was written. */
    public JsonNode patchWork(String entity, JsonNode mergePatch) throws CliFailure, InterruptedException {
        return client.patch(uri(workPath(entity)), mergePatch, MERGE_PATCH);
    }

    /**
     * {@code {<key>: item}}, keyed exactly as the request was. The body is {@code {<key>: full
     * state}}, each key a qualified id or an id: the door is full-state, so a property the state
     * leaves out is cleared. Every id inside a state ({@code membership.parent}, {@code
     * supersededBy}, {@code dependsOn}) may be a qualified id too.
     */
    public JsonNode transitionWork(JsonNode body) throws CliFailure, InterruptedException {
        return client.post(uri(WORK + "/transition"), body);
    }

    /** The item in its new status, {@code statusBefore} filled. The body is {@code {"target": …}}. */
    public JsonNode setWorkStatus(String entity, JsonNode body) throws CliFailure, InterruptedException {
        return client.post(uri(workPath(entity) + "/status"), body);
    }

    /** A JSON Schema: what the door takes for this archetype, as the service validates it. */
    public JsonNode archetypeSchema(String archetype, String door) throws CliFailure, InterruptedException {
        return client.get(uri(schemaPath(archetype, door)));
    }

    /** {@code {"archetypes":[{archetype, lifecycle, transitions, …}]}}. */
    public JsonNode archetypes() throws CliFailure, InterruptedException {
        return client.get(uri(ARCHETYPES));
    }

    private static void addQuery(List<String> query, String name, String value) {
        if (value != null && !value.isBlank()) {
            query.add(name + "=" + URLEncoder.encode(value.strip(), StandardCharsets.UTF_8));
        }
    }

    /**
     * The service's OpenAPI document, as JSON. It is served as YAML unless asked otherwise, and
     * {@code format=json} is how SmallRye OpenAPI is asked. It lives beside the API, under {@code
     * /projects/q}, not under {@code /projects/api}.
     */
    public JsonNode openApi() throws CliFailure, InterruptedException {
        return client.get(uri(OPENAPI + "?format=json"));
    }

    /** A priority only when one is given, so the service's rule for none applies. */
    private static void putPriority(ObjectNode body, String priority) {
        if (priority != null && !priority.isBlank()) {
            body.put("priority", priority.strip().toUpperCase(Locale.ROOT));
        }
    }

    /**
     * The project whose id, slug or name is {@code wanted}. The repository path takes the id only,
     * so a slug or a name is looked up here.
     */
    public JsonNode project(String wanted) throws CliFailure, InterruptedException {
        List<JsonNode> all = entries(projects(), "project");
        List<JsonNode> matches = all.stream()
                .filter(p -> wanted.equals(text(p, "id")) || wanted.equals(text(p, "slug")) || wanted.equals(text(p, "name")))
                .toList();
        if (matches.isEmpty()) {
            String known = all.stream().map(ProjectsApi::projectLabel).collect(Collectors.joining(", "));
            throw new CliFailure("No project has the id, slug or name '" + wanted + "'."
                    + (known.isEmpty() ? " There are no projects." : " Projects: " + known + "."), CliFailure.USAGE);
        }
        if (matches.size() > 1) {
            throw new CliFailure("'" + wanted + "' names more than one project: "
                    + matches.stream().map(p -> projectLabel(p) + " (id " + text(p, "id") + ")").collect(Collectors.joining(", "))
                    + ". Name it by id.", CliFailure.USAGE);
        }
        return matches.getFirst();
    }

    /** The repository of {@code project} whose id or name is {@code wanted}. */
    public JsonNode repository(JsonNode project, String wanted) throws CliFailure, InterruptedException {
        List<JsonNode> all = entries(repositories(text(project, "id")), "repository");
        List<JsonNode> matches = all.stream()
                .filter(r -> wanted.equals(text(r, "id")) || wanted.equals(text(r, "name")))
                .toList();
        String label = projectLabel(project);
        if (matches.isEmpty()) {
            throw new CliFailure("Project " + label + " has no repository with the id or name '" + wanted
                    + "'. `qits repositories --project " + label + " list` shows them.", CliFailure.USAGE);
        }
        if (matches.size() > 1) {
            throw new CliFailure("'" + wanted + "' names more than one repository of project " + label + ": "
                    + matches.stream().map(r -> text(r, "name") + " (id " + text(r, "id") + ")").collect(Collectors.joining(", "))
                    + ". Name it by id.", CliFailure.USAGE);
        }
        return matches.getFirst();
    }

    /** The {@code field} object of every entry in {@code answer.entries}. */
    public static List<JsonNode> entries(JsonNode answer, String field) {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode entry : answer.path("entries")) {
            JsonNode value = entry.get(field);
            if (value != null && value.isObject()) {
                result.add(value);
            }
        }
        return result;
    }

    /** The field as text; empty when absent or null. */
    public static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.isMissingNode() ? "" : value.asText();
    }

    public static String projectLabel(JsonNode project) {
        String slug = text(project, "slug");
        return slug.isEmpty() ? text(project, "name") : slug;
    }

    /** The answer as the service sent it, indented. */
    public static void printJson(PrintStream out, JsonNode node) throws CliFailure {
        try {
            out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node));
        } catch (IOException impossible) {
            throw new CliFailure("cannot print the answer as JSON", CliFailure.FAILED);
        }
    }

    private URI uri(String path) throws CliFailure {
        try {
            URI uri = URI.create(base + path);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("no scheme or host");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new CliFailure("'" + base + "' is not a usable projects address.", CliFailure.USAGE);
        }
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
