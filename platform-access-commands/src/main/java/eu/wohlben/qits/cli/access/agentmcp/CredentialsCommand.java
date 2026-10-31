package eu.wohlben.qits.cli.access.agentmcp;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.PlatformUrls;
import eu.wohlben.qits.cli.access.platform.StdinValue;
import eu.wohlben.qits.cli.access.platform.Table;
import eu.wohlben.qits.cli.access.projects.ProjectsApi;
import eu.wohlben.qits.cli.tui.api.Effect;
import eu.wohlben.qits.cli.tui.api.Input;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

/**
 * {@code qits agent-mcp credentials}: the secrets the agent MCP catalog's servers are reached with
 * (epic qits-765, qits-1394). A value goes in on stdin and never comes back out: the service lists
 * keys only.
 */
@CommandLine.Command(name = "credentials", mixinStandardHelpOptions = true,
        subcommands = {CredentialsCommand.ListCommand.class, CredentialsCommand.SetCommand.class,
                CredentialsCommand.DeleteCommand.class},
        description = {"The credentials of the agent MCP catalog: secrets a catalog entry names by key, stored once "
                + "in qits-projects and handed to the MCP servers that need them. Only the keys are ever shown."},
        footerHeading = "%nNotes:%n",
        footer = {"- --output and --projects-url may come before or after the command.",
                "- list takes the catalog's read roles: qits:admin, qits:admin-agent, qits:desk-admin or qits:agent. "
                        + "set and delete take qits:admin or qits:admin-agent."})
public class CredentialsCommand implements Runnable {

    @CommandLine.Option(names = "--projects-url", paramLabel = "<url>", scope = CommandLine.ScopeType.INHERIT,
            description = "The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the "
                    + "session's idp address with `idp` swapped for `projects`.")
    String projectsUrl;

    @CommandLine.Option(names = {"-o", "--output"}, paramLabel = "table|json", scope = CommandLine.ScopeType.INHERIT,
            description = "table (the default): aligned columns. json: the service's answer, pretty-printed.")
    String output;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    AgentMcpApi catalog(CliContext context) throws CliFailure, InterruptedException {
        return new AgentMcpApi(new PlatformClient(context.credential()),
                PlatformUrls.projects(projectsUrl, context.env(), context.idpUrl()));
    }

    boolean json() throws CliFailure {
        if (output == null || output.isBlank() || output.equalsIgnoreCase("table")) {
            return false;
        }
        if (output.equalsIgnoreCase("json")) {
            return true;
        }
        throw new CliFailure("--output is table or json, not '" + output + "'.", CliFailure.USAGE);
    }

    static String key(String value) throws CliFailure {
        if (value == null || value.isBlank()) {
            throw new CliFailure("Name the key: --key <KEY>.", CliFailure.USAGE);
        }
        return value.strip();
    }

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = {"List the stored credentials' keys, with when and by whom each was last set. Never a value."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits agent-mcp credentials list",
                    "",
                    "- Takes qits:admin, qits:admin-agent, qits:desk-admin or qits:agent."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class ListCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        CredentialsCommand parent;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = parent.json();
            JsonNode answer = parent.catalog(context).credentials();
            if (json) {
                ProjectsApi.printJson(context.out(), answer);
                return 0;
            }
            List<JsonNode> credentials = new ArrayList<>();
            answer.path("credentials").forEach(credentials::add);
            if (credentials.isEmpty()) {
                context.out().println("No credentials are stored.");
                return 0;
            }
            Table.print(context.out(), "", List.of("KEY", "UPDATED", "UPDATED BY"),
                    credentials.stream().map(c -> List.of(
                            Table.cell(text(c, "key"), 48),
                            Table.cell(Table.time(text(c, "updatedAt")), 25),
                            Table.cell(text(c, "updatedBy"), 32))).toList());
            return 0;
        }
    }

    @CommandLine.Command(name = "set", mixinStandardHelpOptions = true,
            description = {"Store one credential under a key, replacing any stored before. The value is read on "
                    + "stdin, never from a flag, so it stays out of the shell's history; one trailing line break is "
                    + "taken off. It is never printed or sent back."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits agent-mcp credentials set --key env.GITHUB_TOKEN < token.txt",
                    "  printf '%%s' \"$TOKEN\" | qits agent-mcp credentials set --key env.GITHUB_TOKEN",
                    "",
                    "- A terminal on stdin, or nothing on it, is refused with exit 2 and nothing is sent.",
                    "- 400 (exit 1) means the key is not env.<VAR>, or the value is blank or larger than 16 KiB.",
                    "- Takes qits:admin or qits:admin-agent."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(input = Input.TEXT)
    public static class SetCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        CredentialsCommand parent;

        @CommandLine.Option(names = "--key", paramLabel = "<KEY>", required = true,
                description = "The credential's key, as catalog entries name it: env.<VAR>, such as env.GITHUB_TOKEN.")
        String key;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            String name = key(key);
            String value = StdinValue.read(context, "the credential",
                    "`... | qits agent-mcp credentials set --key " + name + "`");
            boolean json = parent.json();
            JsonNode stored = parent.catalog(context).putCredential(name, value);
            if (json && stored != null && stored.isObject()) {
                // {key, updatedAt, updatedBy}: the service never answers with the value.
                ProjectsApi.printJson(context.out(), stored);
                return 0;
            }
            context.out().println("set " + SafeText.line(name));
            return 0;
        }
    }

    @CommandLine.Command(name = "delete", mixinStandardHelpOptions = true,
            description = {"Delete the credential stored under a key."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits agent-mcp credentials delete --key env.GITHUB_TOKEN",
                    "",
                    "- 404 (exit 1) means no credential is stored under the key; 400 that the key is not env.<VAR>.",
                    "- Takes qits:admin or qits:admin-agent."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class DeleteCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        CredentialsCommand parent;

        @CommandLine.Option(names = "--key", paramLabel = "<KEY>", required = true,
                description = "The credential's key: env.GITHUB_TOKEN.")
        String key;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            parent.catalog(context).deleteCredential(key(key));
            context.out().println("deleted");
            return 0;
        }
    }
}
