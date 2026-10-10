package eu.wohlben.qits.cli.access.publish;

import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

/**
 * {@code qits artifacts publish} — the platform's publish client for qits-artifacts, folded into
 * {@code qits} from its own repository, the since-retired qits-artifacts-cli. A CI release step
 * calls one of these once per coordinate; nothing here orchestrates a release or decides what runs
 * in what order.
 */
@TuiCommand(interaction = Interaction.CI_ONLY)
@CommandLine.Command(name = "publish", mixinStandardHelpOptions = true,
        subcommands = {MavenCommand.class, NpmCommand.class, ContractCommand.class, ContractDocsCommand.class,
                SbomCommand.class, DocsCommand.class, ChangelogCommand.class, DaemonCommand.class, ExistsCommand.class},
        description = {
                "Publish to qits-artifacts from a CI release step: a maven module or an npm package (built, "
                        + "hashed and uploaded here, optionally only if its content changed), a contract package, "
                        + "an sbom, a docs bundle, a release's changelog, or a daemon binary. This is the qits-publish client.",
                "Every publish follows one rule, for every surface: absent, PUT it and say what landed; occupied "
                        + "with the same bytes, say so and succeed (a retried or replayed step must go green); "
                        + "occupied with different bytes, fail naming both digests (a coordinate must never come "
                        + "to mean two things); occupied and not comparable, warn and skip."},
        footerHeading = "%nNotes:%n",
        footer = {
                "- This command never signs in and never reads or writes what `qits login` keeps: it runs in a "
                        + "CI step container with no person. `qits login` and `qits git-login` do not apply to it.",
                "- Only a CI run may publish to qits-artifacts, so every request carries a bearer. The token comes "
                        + "from the first of these the environment has: QITS_PUBLISH_TOKEN_COMMAND (an executable "
                        + "that prints a fresh token, re-run for every request), QITS_PUBLISH_TOKEN (a token), or "
                        + "QITS_COMMISSIONED_CLIENT_ID and QITS_COMMISSIONED_CLIENT_SECRET (minted at the idp). With "
                        + "none of them the request still goes out, unauthenticated, and the store answers 401: the "
                        + "store decides who may write, not this client.",
                "- Started under the name `qits-publish` (its own file, or a symlink to `qits`), any command runs "
                        + "exactly as `qits artifacts publish <command>`: `qits-publish sbom submit ...` behaves as "
                        + "`qits artifacts publish sbom submit ...`. A hand-written pipeline may still call it that way.",
                "- The store is https://registry.qits.$QITS_DOMAIN and the Maven Central cache "
                        + "https://mirror.qits.$QITS_DOMAIN; QITS_DOMAIN defaults to wohlben.eu. No other variable "
                        + "names an address: the hosted npm and maven repositories, the docs, sbom and daemon "
                        + "stores are fixed paths under the store's host."},
        exitCodeListHeading = "%nExit codes:%n",
        exitCodeList = {
                "0:Published, or already published with the same bytes.",
                "1:Refused, and re-running will not help: invalid arguments, a 4xx, or the coordinate already "
                        + "holds different bytes.",
                "2:Could not ask, or could not be answered: the store unreachable, an I/O failure, or a 5xx. A "
                        + "step may retry a 2 and must not retry a 1."})
public class PublishCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }
}
