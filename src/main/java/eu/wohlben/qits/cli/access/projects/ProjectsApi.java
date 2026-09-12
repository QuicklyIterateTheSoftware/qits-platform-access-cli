package eu.wohlben.qits.cli.access.projects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.platform.AccessTokens;
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

    private final PlatformClient client;
    private final String base;

    public ProjectsApi(PlatformClient client, String base) {
        this.client = client;
        this.base = base;
    }

    /** Reads the session (refreshing it if due) and finds the service. */
    public static ProjectsApi connect(CliContext context, String projectsUrl) throws CliFailure, InterruptedException {
        AccessTokens tokens = context.tokens();
        String idpUrl = tokens.session().idpUrl();
        return new ProjectsApi(new PlatformClient(tokens), PlatformUrls.projects(projectsUrl, context.env(), idpUrl));
    }

    /** {@code {"entries":[{"project":{…}}]}}. */
    public JsonNode projects() throws CliFailure, InterruptedException {
        return client.get(uri("/projects/api/projects"));
    }

    /** {@code {"entries":[{"repository":{…},"declared":…}],"wrapper":{…}}}. */
    public JsonNode repositories(String projectId) throws CliFailure, InterruptedException {
        return client.get(uri("/projects/api/projects/" + segment(projectId) + "/repositories"));
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
