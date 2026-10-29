package eu.wohlben.qits.cli.access.agents;

import eu.wohlben.qits.cli.access.workspaces.AgentCommands;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

/**
 * {@code qits agents}: the platform's agents, one verb per thing that can be done to one (epic
 * qits-1667, {@link AgentCommands}), and what an agent harness runs on the caller's machine, one
 * group per harness ({@code claude}), so a hook says which harness it is for.
 * <p>
 * Only {@code claude} is {@code Interaction.LOCAL}: the verbs are MCP tools like any command, the
 * harness's hooks mean something only where the harness runs.
 */
@CommandLine.Command(name = "agents", mixinStandardHelpOptions = true,
        subcommands = {AgentCommands.ListCommand.class, AgentCommands.ShowCommand.class,
                AgentCommands.PromptCommand.class, AgentCommands.InterruptCommand.class,
                AgentCommands.StopCommand.class, AgentCommands.RestartCommand.class, AgentCommands.YieldCommand.class,
                AgentCommands.SessionsCommand.class, AgentCommands.TranscriptCommand.class,
                AgentCommands.ChatCommand.class, AgentCommands.AttachCommand.class, AgentCommands.FilesCommand.class,
                AgentCommands.DiscardCommand.class, AgentCommands.DraftCommand.class, AgentsCommand.ClaudeCommand.class},
        description = {"The platform's agents: list them, show one, prompt, interrupt, stop, restart or yield it, "
                        + "read its sessions, its chat and its worktree, attach to its terminal, keep its prompt "
                        + "draft, and discard it. Each on qits-workspaces' agent doors.",
                "An agent is named by its id (`qits agents list` shows it), or by --work <qits-123>: the one active "
                        + "agent working on that item; none, or several, is refused (exit 1).",
                "`qits agents claude ...` is what Claude Code runs on this machine; a person does not run those."},
        footerHeading = "%nNotes:%n",
        footer = {"- The reads are open to every credential, qits:agent included. prompt, interrupt, stop and restart "
                + "need qits:admin, qits:admin-agent, qits:desk-admin, qits:desk (within its own project) or "
                + "qits:system; yield also takes qits:agent for its own agent; discard and draft save|discard need "
                + "qits:admin, qits:admin-agent or qits:desk-admin. A refused credential is HTTP 403 by design.",
                "- chat, attach and files go through the agent proxy to the daemon of the slot the agent is placed "
                        + "in now, so none of them takes the slot. qits:agent reaches only its own agent there.",
                "- --workspaces-url, --projects-url and --output are each verb's own: they come after the verb."})
public class AgentsCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    @TuiCommand(interaction = Interaction.LOCAL)
    @CommandLine.Command(name = "claude", mixinStandardHelpOptions = true,
            subcommands = {HookCommand.class, StatuslineCommand.class},
            description = "What Claude Code runs on this machine. A person does not run these.")
    public static class ClaudeCommand implements Runnable {

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
        }
    }

    @CommandLine.Command(name = "hook", mixinStandardHelpOptions = true,
            subcommands = WorkLinksCommand.class,
            description = "Claude Code's hooks. Each is registered in Claude's settings.json; the help of each shows how.")
    public static class HookCommand implements Runnable {

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
        }
    }

    @CommandLine.Command(name = "statusline", mixinStandardHelpOptions = true,
            subcommands = WorkItemsCommand.class,
            description = "Claude Code's status lines. Each is registered in Claude's settings.json; the help of each "
                    + "shows how.")
    public static class StatuslineCommand implements Runnable {

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
        }
    }
}
