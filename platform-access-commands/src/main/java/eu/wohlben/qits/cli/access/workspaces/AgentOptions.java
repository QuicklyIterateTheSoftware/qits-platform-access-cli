package eu.wohlben.qits.cli.access.workspaces;

import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import picocli.CommandLine;

/**
 * The options every {@code qits agents} verb takes, the same three {@code qits workspaces} gives its
 * subcommands. A mixin on each verb rather than inherited from {@code qits agents}, so the Claude Code
 * commands beside them ({@code qits agents claude …}) do not carry options they never read.
 * <p>
 * No {@code defaultValue}: null means the default.
 */
public class AgentOptions implements WorkspacesAccess {

    @CommandLine.Option(names = "--workspaces-url", paramLabel = "<url>",
            description = "The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, "
                    + "else derived from the idp address, like the other services.")
    String workspacesUrl;

    @CommandLine.Option(names = "--projects-url", paramLabel = "<url>",
            description = "The projects service's base URL, without /projects, where a work item's qualified id "
                    + "(qits-123) is looked up. Default: QITS_PROJECTS_URL, else derived from the idp address.")
    String projectsUrl;

    @CommandLine.Option(names = {"-o", "--output"}, paramLabel = "table|json",
            description = "table (the default): for a person. json: the service's answer, pretty-printed, with "
                    + "control characters written as escapes.")
    String output;

    @Override
    public WorkspacesApi api(CliContext context) throws CliFailure, InterruptedException {
        return WorkspacesCommand.api(context, workspacesUrl);
    }

    @Override
    public boolean json() throws CliFailure {
        return WorkspacesCommand.Json.wanted(output);
    }

    @Override
    public String workId(CliContext context, String given) throws CliFailure, InterruptedException {
        return WorkspacesCommand.workId(context, projectsUrl, given);
    }

    @Override
    public String projectId(CliContext context, String given) throws CliFailure, InterruptedException {
        return WorkspacesCommand.projectId(context, projectsUrl, given);
    }
}
