package eu.wohlben.qits.cli.access.agentmcp;

import picocli.CommandLine;

/**
 * {@code qits agent-mcp}: the platform's catalog of MCP servers agents are given, in qits-projects.
 * {@code credentials} is its only group today (epic qits-765).
 */
@CommandLine.Command(name = "agent-mcp", mixinStandardHelpOptions = true,
        subcommands = {CredentialsCommand.class},
        description = "The catalog of MCP servers the platform gives its agents, in qits-projects. `credentials` "
                + "stores the secrets those servers are reached with.")
public class AgentMcpCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }
}
