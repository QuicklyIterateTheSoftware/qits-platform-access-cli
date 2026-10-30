package eu.wohlben.qits.cli.access.deployments;

import picocli.CommandLine;

/**
 * {@code qits deployments}: the deployer's doors. {@code settings} is its only group today (epic
 * qits-765).
 */
@CommandLine.Command(name = "deployments", mixinStandardHelpOptions = true,
        subcommands = {SettingsCommand.class},
        description = "The deployer, qits-deployments. `settings` reads and writes the values it hands each "
                + "application in each environment.")
public class DeploymentsCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }
}
