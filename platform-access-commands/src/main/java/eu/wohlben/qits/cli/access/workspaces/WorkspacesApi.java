package eu.wohlben.qits.cli.access.workspaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * qits-workspaces as the landing app's workspace pages call it (epic qits-1230, qits-1261): its JSON
 * API under {@code /workspaces/api}, and a slot's workspace-daemon through the container proxy,
 * {@code /workspaces/container/{slot row id}/…}, whose paths below the prefix are the daemon's own.
 * <p>
 * Each method is one door, with the method, path, query and body the landing app sends, so a command
 * and the page that offers the same action are one call apart, never two shapes. The answers are
 * read as JSON trees.
 */
final class WorkspacesApi {

    static final String API = "/workspaces/api";
    static final String CONTAINER = "/workspaces/container/";

    private final PlatformClient client;
    private final String base;

    WorkspacesApi(PlatformClient client, String base) {
        this.client = client;
        this.base = base;
    }

    // --- slots ---

    /** {@code listWorkspaces}: the ACTIVE slots, of one repository when it is given. */
    JsonNode workspaces(String repositoryId) throws CliFailure, InterruptedException {
        return client.get(uri(API + "/workspaces" + query("repositoryId", repositoryId)));
    }

    /** {@code getWorkspace}. */
    JsonNode workspace(long slot) throws CliFailure, InterruptedException {
        return client.get(uri(API + "/workspaces/" + slot));
    }

    /** {@code getWorkspaceActiveProcess}: the start running against the slot, if one is live. */
    JsonNode activeProcess(long slot) throws CliFailure, InterruptedException {
        return client.get(uri(API + "/workspaces/" + slot + "/active-process"));
    }

    /** {@code ensureWorkspaceContainer}, {@code stopWorkspaceContainer}, {@code recreateWorkspaceContainer}: no body. */
    JsonNode containerVerb(long slot, String verb) throws CliFailure, InterruptedException {
        return client.post(uri(API + "/workspaces/" + slot + "/" + verb));
    }

    /** {@code listOpenWorkspaces}: the ACTIVE workspaces bound to a work item. */
    JsonNode openWorkspaces() throws CliFailure, InterruptedException {
        return client.get(uri(API + "/work/workspaces"));
    }

    /** {@code listWorkItemWorkspaces}: one item's workspaces in every state, newest first. */
    JsonNode workItemWorkspaces(String workRef) throws CliFailure, InterruptedException {
        return client.get(uri(API + "/work/" + segment(workRef) + "/workspaces"));
    }

    /** The replayable log stream of one technical process (Server-Sent Events). */
    URI processEvents(String processId) throws CliFailure {
        return uri(API + "/technical-processes/" + segment(processId) + "/events");
    }

    // --- agents ---

    /** {@code listAgents}: newest first; each filter is left out when it is null. */
    JsonNode agents(String workId, Long slot, String state) throws CliFailure, InterruptedException {
        List<String> query = new ArrayList<>();
        add(query, "workId", workId);
        add(query, "workspaceId", slot == null ? null : String.valueOf(slot));
        add(query, "state", state);
        return client.get(uri(API + "/agents" + (query.isEmpty() ? "" : "?" + String.join("&", query))));
    }

    /** {@code createAgent}. */
    JsonNode createAgent(JsonNode body) throws CliFailure, InterruptedException {
        return client.post(uri(API + "/agents"), body);
    }

    /** {@code discardAgent}: {@code {"force": …}}, sent either way. */
    JsonNode discardAgent(String agentId, boolean force) throws CliFailure, InterruptedException {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("force", force);
        return client.post(uri(API + "/agents/" + segment(agentId) + "/discard"), body);
    }

    /** {@code deliverAgentTurn}: {@code {workId, text, compactFirst: false}}, as the prompt box sends it. */
    JsonNode deliver(String workId, String text) throws CliFailure, InterruptedException {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("workId", workId);
        body.put("text", text);
        body.put("compactFirst", false);
        return client.post(uri(API + "/agent-dispatches/delivery"), body);
    }

    /** {@code getAgentPromptDraft}. */
    JsonNode draft(String agentId) throws CliFailure, InterruptedException {
        return client.get(uri(draftPath(agentId)));
    }

    /** {@code saveAgentPromptDraft}: {@code {content, serializedPrompt}}. */
    JsonNode saveDraft(String agentId, JsonNode body) throws CliFailure, InterruptedException {
        return client.put(uri(draftPath(agentId)), body);
    }

    /** {@code deleteAgentPromptDraft}: a 204. */
    JsonNode deleteDraft(String agentId) throws CliFailure, InterruptedException {
        return client.delete(uri(draftPath(agentId)));
    }

    private static String draftPath(String agentId) {
        return API + "/agents/" + segment(agentId) + "/prompt-draft";
    }

    // --- a slot's daemon, through the container proxy ---

    /** {@code getAgentWorktree}: the agent's worktree, with its harness's last command. */
    JsonNode agentWorktree(long slot, String agentId) throws CliFailure, InterruptedException {
        return client.get(uri(container(slot) + "/agent-worktrees/" + segment(agentId)));
    }

    /** {@code listCommands}: the container's commands. */
    JsonNode commands(long slot) throws CliFailure, InterruptedException {
        return client.get(uri(container(slot) + "/commands"));
    }

    /** {@code getCommandLog} with {@code channel=TRANSCRIPT}: the harness's own JSON lines. */
    JsonNode transcript(long slot, String commandId) throws CliFailure, InterruptedException {
        return client.get(uri(container(slot) + "/commands/" + segment(commandId) + "/log?channel=TRANSCRIPT"));
    }

    /** {@code terminateCommand}: an empty JSON object, as the Chat tab sends it. */
    JsonNode terminate(long slot, String commandId) throws CliFailure, InterruptedException {
        return client.post(uri(container(slot) + "/commands/" + segment(commandId) + "/terminate"),
                JsonNodeFactory.instance.objectNode());
    }

    /** {@code refinePrompt}: {@code {transcript, preamble?}}. */
    JsonNode refine(long slot, String transcript, String preamble) throws CliFailure, InterruptedException {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("transcript", transcript);
        if (preamble != null) {
            body.put("preamble", preamble);
        }
        return client.post(uri(container(slot) + "/prompt-refinements"), body);
    }

    /** {@code listFiles}: the whole eager tree without a path, one directory with one. */
    JsonNode files(long slot, String agentId, String path) throws CliFailure, InterruptedException {
        return client.get(uri(container(slot) + "/agent-worktrees/" + segment(agentId) + "/files"
                + query("path", path)));
    }

    /** {@code readFile}. */
    JsonNode file(long slot, String agentId, String path) throws CliFailure, InterruptedException {
        return client.get(uri(container(slot) + "/agent-worktrees/" + segment(agentId) + "/files/content"
                + query("path", path)));
    }

    /** {@code detect}: the frameworks and the source-to-test links. */
    JsonNode detect(long slot, String agentId) throws CliFailure, InterruptedException {
        return client.get(uri(container(slot) + "/agent-worktrees/" + segment(agentId) + "/detection"));
    }

    /** {@code availableAgents}: whether anybody signed each harness in. */
    JsonNode signInReport(long slot) throws CliFailure, InterruptedException {
        return client.get(uri(container(slot) + "/agents/available"));
    }

    /** {@code openSignInTerminal}: {@code {agentType}} for a harness, {@code {}} for the daemon's default. */
    JsonNode openSignIn(long slot, String harness) throws CliFailure, InterruptedException {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        if (harness != null) {
            body.put("agentType", harness);
        }
        return client.post(uri(container(slot) + "/agents/sign-in"), body);
    }

    /** {@code listPlugins}. */
    JsonNode plugins(long slot) throws CliFailure, InterruptedException {
        return client.get(uri(container(slot) + "/agent-plugins"));
    }

    /** {@code installPlugin}: an empty JSON object, as the daemon's recording and the page send it. */
    JsonNode installPlugin(long slot, String pluginId) throws CliFailure, InterruptedException {
        return client.post(uri(container(slot) + "/agent-plugins/" + segment(pluginId) + "/install"),
                JsonNodeFactory.instance.objectNode());
    }

    /**
     * The daemon's terminal socket for one command, {@code WS /terminal/commands/{id}}: the landing
     * app's {@code terminalSocketUrl}, {@code ws} for an http base and {@code wss} for https.
     */
    URI terminalSocket(long slot, String commandId) throws CliFailure {
        URI http = uri(container(slot) + "/terminal/commands/" + segment(commandId));
        String scheme = switch (http.getScheme().toLowerCase(Locale.ROOT)) {
            case "https", "wss" -> "wss";
            case "http", "ws" -> "ws";
            default -> throw new CliFailure("'" + base + "' is not a usable workspaces address. Give an http or "
                    + "https base URL.", CliFailure.USAGE);
        };
        return URI.create(scheme + http.toString().substring(http.getScheme().length()));
    }

    /** The client the doors are called with; a socket opened beside them carries the same credential. */
    PlatformClient client() {
        return client;
    }

    static String container(long slot) {
        return CONTAINER + slot;
    }

    // --- addresses ---

    URI uri(String path) throws CliFailure {
        try {
            URI uri = URI.create(base + path);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("no scheme or host");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new CliFailure("'" + base + "' is not a usable workspaces address.", CliFailure.USAGE);
        }
    }

    private static String query(String name, String value) {
        return value == null ? "" : "?" + name + "=" + segment(value);
    }

    private static void add(List<String> query, String name, String value) {
        if (value != null) {
            query.add(name + "=" + segment(value));
        }
    }

    static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
