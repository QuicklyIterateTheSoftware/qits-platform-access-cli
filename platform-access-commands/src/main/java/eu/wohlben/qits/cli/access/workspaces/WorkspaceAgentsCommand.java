package eu.wohlben.qits.cli.access.workspaces;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.Table;
import eu.wohlben.qits.cli.access.terminal.LocalTerminal;
import eu.wohlben.qits.cli.access.workspaces.WorkspacesCommand.Json;
import eu.wohlben.qits.cli.tui.api.Effect;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;
import static eu.wohlben.qits.cli.access.workspaces.WorkspacesCommand.cell;
import static eu.wohlben.qits.cli.access.workspaces.WorkspacesCommand.slot;

/**
 * {@code qits workspaces agents}: what the container's harness install needs (sign-in, plugins) and
 * the slot-addressed prompt rewrite (refine), the parts of the landing app's workspace pages that are
 * not about one agent (qits-1261). The agents' own verbs (list, show, prompt, discard, chat, files,
 * ...) moved to {@code qits agents} ({@link AgentCommands}, epic qits-1667); their deprecated aliases
 * here lived for one release and are gone.
 */
@CommandLine.Command(name = "agents", mixinStandardHelpOptions = true,
        subcommands = {WorkspaceAgentsCommand.RefineCommand.class,
                WorkspaceAgentsCommand.SignInCommand.class, WorkspaceAgentsCommand.PluginsCommand.class},
        description = {"The workspace slots' container: a harness's sign-in and the plugins on its shared agent "
                        + "home, and rewriting rough notes into a prompt. The agents themselves are `qits agents`.",
                "The commands here take the slot with --workspace, as the page does."},
        footerHeading = "%nNotes:%n",
        footer = {"- The reads are open to every credential, qits:agent included, and so is everything that "
                + "goes through the container proxy (refine, sign-in, plugins)."})
public class WorkspaceAgentsCommand implements Runnable {

    static final Set<String> HARNESSES = Set.of("CLAUDE", "KIMI");

    @CommandLine.ParentCommand
    WorkspacesCommand parent;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    static String required(String given, String what) throws CliFailure {
        return AgentViews.required(given, what);
    }

    static String harness(String given) throws CliFailure {
        if (given == null || given.isBlank()) {
            return null;
        }
        String name = given.strip().toUpperCase(Locale.ROOT);
        if (!HARNESSES.contains(name)) {
            throw new CliFailure("--harness must be CLAUDE or KIMI, not '" + given.strip() + "'.", CliFailure.USAGE);
        }
        return name;
    }

    // --- the slot's container, through the proxy ---

    /** The commands that reach a slot's container directly, through the container proxy. */
    abstract static class InContainer extends PlatformCommand {

        @CommandLine.Option(names = "--workspace", paramLabel = "<n>", required = true,
                description = "The slot: its number, as `qits workspaces list` shows it.")
        Long workspace;

        abstract WorkspacesCommand root();
    }

    @CommandLine.Command(name = "refine", mixinStandardHelpOptions = true,
            description = {"Rewrite rough notes into a prompt, in the slot's container: Refine in the prompt box. "
                    + "Prints the rewrite; nothing is sent to an agent.",
                    "POST /workspaces/container/{slot}/prompt-refinements with {transcript, preamble?}. " + Doors.PROXY},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces agents refine --workspace 1 --preamble 'Fix the login' "
                    + "'uh so the login fails when the name has spaces'"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class RefineCommand extends InContainer {

        @CommandLine.ParentCommand
        WorkspaceAgentsCommand agents;

        @CommandLine.Parameters(paramLabel = "<notes>", description = "The rough notes.")
        String notes;

        @CommandLine.Option(names = "--preamble", paramLabel = "<text>",
                description = "What the prompt is about, which the rewrite keeps in front.")
        String preamble;

        @Override
        WorkspacesCommand root() {
            return agents.parent;
        }

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = root().json();
            long slot = slot(workspace);
            String transcript = required(notes, "The notes are empty. Give the notes to rewrite.");
            String lead = preamble == null || preamble.isBlank() ? null : preamble;
            JsonNode answer = root().api(context).refine(slot, transcript, lead);
            if (json) {
                Json.print(context.out(), answer);
                return 0;
            }
            for (String line : text(answer, "prompt").split("\n", -1)) {
                context.out().println(SafeText.line(line));
            }
            return 0;
        }
    }

    // --- sign-in ---

    @CommandLine.Command(name = "sign-in", mixinStandardHelpOptions = true,
            subcommands = {SignInStatusCommand.class, SignInOpenCommand.class},
            description = {"Whether anybody signed the slot's harnesses in, on the credential volume every container "
                    + "shares, and the sign-in terminal that does it: the notice on an agent's Terminal tab."})
    public static class SignInCommand implements Runnable {

        @CommandLine.ParentCommand
        WorkspaceAgentsCommand agents;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
        }
    }

    @CommandLine.Command(name = "status", mixinStandardHelpOptions = true,
            description = {"Each harness of the slot's daemon, whether it is signed in, and the daemon's hint when it "
                    + "is not.",
                    "GET /workspaces/container/{slot}/agents/available. " + Doors.PROXY},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces agents sign-in status --workspace 1"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class SignInStatusCommand extends InContainer {

        @CommandLine.ParentCommand
        SignInCommand signIn;

        @Override
        WorkspacesCommand root() {
            return signIn.agents.parent;
        }

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = root().json();
            JsonNode answer = root().api(context).signInReport(slot(workspace));
            if (json) {
                Json.print(context.out(), answer);
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode capability : answer.path("capabilities")) {
                rows.add(List.of(cell(capability, "harness", 12),
                        capability.path("authenticated").asBoolean(false) ? "yes" : "no",
                        cell(capability, "authDetail", 160)));
            }
            if (rows.isEmpty()) {
                context.out().println("The daemon reports no harness.");
                return 0;
            }
            Table.print(context.out(), "", List.of("HARNESS", "SIGNED IN", "DETAIL"), rows);
            return 0;
        }
    }

    @CommandLine.Command(name = "open", mixinStandardHelpOptions = true,
            description = {"Start a harness's sign-in in the slot's container: Open the sign-in terminal. It is an "
                    + "interactive terminal: --attach attaches this one to it at once, or `qits workspaces attach "
                    + "<command> --workspace <n>` later, with the command id this prints. The agent's Terminal tab "
                    + "in the landing app shows the same terminal.",
                    "POST /workspaces/container/{slot}/agents/sign-in with {agentType} for --harness, else {}; the "
                            + "answer's command.id is the terminal's command. " + Doors.PROXY},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces agents sign-in open --workspace 1 --harness CLAUDE --attach",
                    "  qits workspaces agents sign-in open --workspace 1 --harness CLAUDE",
                    "",
                    "- With --attach, Ctrl-] detaches and leaves the sign-in running. --attach needs a terminal for "
                            + "typing, and the MCP service refuses it; without a terminal it streams, read-only."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:Done; with --attach, the sign-in ended or you detached.",
                    "1:The platform refused or cannot be reached; with --attach, the connection could not be kept.",
                    HelpText.USAGE})
    public static class SignInOpenCommand extends InContainer {

        @CommandLine.ParentCommand
        SignInCommand signIn;

        @CommandLine.Option(names = "--harness", paramLabel = "CLAUDE|KIMI",
                description = "The harness to sign in. Default: the daemon's.")
        String harness;

        @CommandLine.Option(names = "--attach",
                description = "Attach to the sign-in terminal once it is open, until it ends or Ctrl-] detaches.")
        boolean attach;

        /** The caller's terminal, for --attach; a test hands its own. */
        LocalTerminal.Factory terminals = LocalTerminal::of;

        @Override
        WorkspacesCommand root() {
            return signIn.agents.parent;
        }

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = root().json();
            long slot = slot(workspace);
            String chosen = harness(harness);
            if (attach) {
                // Before anything opens: a sign-in nobody can reach would only wait in the container.
                WorkspacesCommand.refuseWithoutATerminal();
            }
            WorkspacesApi api = root().api(context);
            JsonNode answer = api.openSignIn(slot, chosen);
            String commandId = text(answer.path("command"), "id");
            if (json) {
                Json.print(context.out(), answer);
            }
            if (!attach) {
                if (!json) {
                    context.out().println("Opened the sign-in terminal on slot " + slot + ": command "
                            + SafeText.line(commandId) + ". Attach to it with `qits workspaces attach "
                            + SafeText.line(commandId) + " --workspace " + slot + "`.");
                }
                return 0;
            }
            if (commandId.isEmpty()) {
                throw new CliFailure("The daemon of slot " + slot + " opened the sign-in without naming its command, "
                        + "so there is nothing to attach to.", CliFailure.FAILED);
            }
            // stdout is the terminal from here on; what was opened is said beside it.
            context.err().println("Opened the sign-in terminal on slot " + slot + ": command "
                    + SafeText.line(commandId) + ". Attaching.");
            return WorkspacesCommand.attach(context, api, slot, commandId, terminals);
        }
    }

    // --- plugins ---

    @CommandLine.Command(name = "plugins", mixinStandardHelpOptions = true,
            subcommands = {PluginsListCommand.class, PluginsInstallCommand.class},
            description = {"The plugins on the agent home every container of the slot's daemon shares: the plugins "
                    + "section of an agent's Terminal tab."})
    public static class PluginsCommand implements Runnable {

        @CommandLine.ParentCommand
        WorkspaceAgentsCommand agents;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
        }
    }

    static void printPlugins(PrintStream out, JsonNode answer) {
        List<List<String>> rows = new ArrayList<>();
        for (JsonNode plugin : answer.path("installed")) {
            rows.add(List.of(cell(plugin, "pluginId", 80), plugin.path("enabled").asBoolean(false) ? "yes" : "no"));
        }
        if (rows.isEmpty()) {
            out.println("No plugins installed.");
            return;
        }
        Table.print(out, "", List.of("PLUGIN", "ENABLED"), rows);
    }

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = {"The plugins installed, each marketplace-qualified, and whether it is switched on.",
                    "GET /workspaces/container/{slot}/agent-plugins. " + Doors.PROXY},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces agents plugins list --workspace 1"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class PluginsListCommand extends InContainer {

        @CommandLine.ParentCommand
        PluginsCommand plugins;

        @Override
        WorkspacesCommand root() {
            return plugins.agents.parent;
        }

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = root().json();
            JsonNode answer = root().api(context).plugins(slot(workspace));
            if (json) {
                Json.print(context.out(), answer);
            } else {
                printPlugins(context.out(), answer);
            }
            return 0;
        }
    }

    @CommandLine.Command(name = "install", mixinStandardHelpOptions = true,
            description = {"Install a plugin on the shared agent home: Install on a plugin not installed yet. Prints "
                    + "the installed set as it now is.",
                    "POST /workspaces/container/{slot}/agent-plugins/{pluginId}/install with {}. " + Doors.PROXY},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces agents plugins install pyright-lsp --workspace 1"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class PluginsInstallCommand extends InContainer {

        @CommandLine.ParentCommand
        PluginsCommand plugins;

        @CommandLine.Parameters(paramLabel = "<plugin>", description = "The plugin's id, as the catalogue names it.")
        String plugin;

        @Override
        WorkspacesCommand root() {
            return plugins.agents.parent;
        }

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = root().json();
            long slot = slot(workspace);
            String id = required(plugin, "Name the plugin: its id.");
            JsonNode answer = root().api(context).installPlugin(slot, id);
            if (json) {
                Json.print(context.out(), answer);
            } else {
                printPlugins(context.out(), answer);
            }
            return 0;
        }
    }
}
