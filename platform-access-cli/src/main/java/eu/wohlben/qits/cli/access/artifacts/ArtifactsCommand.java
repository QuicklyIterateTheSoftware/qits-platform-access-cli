package eu.wohlben.qits.cli.access.artifacts;

import eu.wohlben.qits.cli.access.publish.PublishCommand;
import picocli.CommandLine;

/**
 * {@code qits artifacts} — the platform's artifacts store, qits-artifacts. Its only command today
 * is {@code publish}, a CI release step's publish client; a later read command that is not part of
 * a publish step belongs here too.
 */
@CommandLine.Command(name = "artifacts", mixinStandardHelpOptions = true,
        subcommands = {PublishCommand.class},
        description = "The platform's artifacts store. `qits artifacts publish` is a CI release step's "
                + "publish client: an sbom, a docs bundle, a daemon binary, or an npm decision.")
public class ArtifactsCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }
}
