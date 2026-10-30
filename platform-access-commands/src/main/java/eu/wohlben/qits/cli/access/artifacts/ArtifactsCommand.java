package eu.wohlben.qits.cli.access.artifacts;

import eu.wohlben.qits.cli.access.publish.PublishCommand;
import picocli.CommandLine;

/**
 * {@code qits artifacts} — the platform's artifacts store, qits-artifacts. {@code publish} is a CI
 * release step's publish client; {@code fetch} writes published artifacts to the caller's disk
 * (qits-1765: a provider's deployed consumer pacts).
 */
@CommandLine.Command(name = "artifacts", mixinStandardHelpOptions = true,
        subcommands = {PublishCommand.class, FetchCommand.class},
        description = "The platform's artifacts store. `qits artifacts publish` is a CI release step's "
                + "publish client: a maven module, an npm package, a contract package, an sbom, a docs bundle or "
                + "a daemon binary. `qits artifacts fetch` writes published artifacts to a local directory: the "
                + "consumer pacts a provider's deployed consumers were built against.")
public class ArtifactsCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }
}
