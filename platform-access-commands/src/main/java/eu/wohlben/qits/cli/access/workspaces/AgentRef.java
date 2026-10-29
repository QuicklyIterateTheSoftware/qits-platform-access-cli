package eu.wohlben.qits.cli.access.workspaces;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

/**
 * How a {@code qits agents} verb names its agent (epic qits-1667): the agent's id, or {@code --work}
 * and the one ACTIVE agent working on that item, never both. A mixin, so every verb words it the same.
 * <p>
 * {@code --work} goes through the projects service for a qualified id ({@link WorkspacesAccess#workId}),
 * then {@code GET /workspaces/api/agents?workId=<uuid>&state=ACTIVE}. No agent, or more than one, is
 * exit 1 with what was found: the CLI never picks one of several.
 */
public class AgentRef {

    @CommandLine.Option(names = "--work", paramLabel = "<work>",
            description = "Instead of <agent>: the work item whose one active agent is meant, by its qualified id "
                    + "(qits-123) or its id. No active agent, or more than one, is refused (exit 1).")
    String work;

    /** The positionals a verb took, with the agent left out when {@code --work} names it instead. */
    record Given(String agent, String second) {
    }

    /**
     * A verb's two positionals, {@code [<agent>] <second>}: with {@code --work} and one positional, that
     * one is the second, since the agent is not given.
     */
    Given split(String first, String second) {
        if (byWork() && second == null) {
            return new Given(null, first);
        }
        return new Given(first, second);
    }

    boolean byWork() {
        return work != null && !work.isBlank();
    }

    /** The agent's id: the one given, or the one ACTIVE agent on {@code --work}. */
    String resolve(CliContext context, WorkspacesAccess access, String agent) throws CliFailure, InterruptedException {
        String given = agent == null ? "" : agent.strip();
        if (byWork() && !given.isEmpty()) {
            throw new CliFailure("Name the agent or --work, not both.", CliFailure.USAGE);
        }
        if (!byWork()) {
            if (given.isEmpty()) {
                throw new CliFailure("Name the agent: its id, as `qits agents list` shows it, or --work <qits-123> "
                        + "for the one active agent on that item.", CliFailure.USAGE);
            }
            return given;
        }
        String workId = access.workId(context, work);
        List<JsonNode> active = WorkspacesCommand.entries(access.api(context).agents(workId, null, "ACTIVE", null),
                "agents", null);
        String named = SafeText.line(work.strip());
        if (active.isEmpty()) {
            throw new CliFailure("No active agent works on " + named + ".", CliFailure.FAILED);
        }
        if (active.size() > 1) {
            List<String> found = new ArrayList<>();
            for (JsonNode each : active) {
                found.add(SafeText.line(text(each, "agentId")) + " (slot " + WorkspacesCommand.cell(each,
                        "workspaceRowId", 12) + ", " + WorkspacesCommand.cell(each, "runState", 12) + ")");
            }
            throw new CliFailure(active.size() + " active agents work on " + named + ": " + String.join(", ", found)
                    + ". Name one by its id.", CliFailure.FAILED);
        }
        String id = text(active.getFirst(), "agentId");
        if (id.isEmpty()) {
            throw new CliFailure("The workspaces service answered the agent on " + named + " without its id.",
                    CliFailure.FAILED);
        }
        return id;
    }
}
