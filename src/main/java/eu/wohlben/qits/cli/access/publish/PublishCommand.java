package eu.wohlben.qits.cli.access.publish;

import picocli.CommandLine;

/**
 * {@code qits publish} — the platform's publish client for qits-artifacts, folded into {@code qits}
 * from its own repository, qits-artifacts-cli. A CI release step calls one of these once per
 * coordinate; nothing here orchestrates a release or decides what runs in what order.
 */
@CommandLine.Command(name = "publish", mixinStandardHelpOptions = true,
        subcommands = {SbomCommand.class, DocsCommand.class, DaemonCommand.class, NpmCommand.class,
                ExistsCommand.class},
        description = {
                "Publish to qits-artifacts from a CI release step: an sbom, a docs bundle, a daemon binary, or "
                        + "an npm publish/replay/skip decision. This is the qits-publish client.",
                "Every publish follows one rule, for every surface: absent, PUT it and say what landed; occupied "
                        + "with the same bytes, say so and succeed (a retried or replayed step must go green); "
                        + "occupied with different bytes, fail naming both digests (a coordinate must never come "
                        + "to mean two things); occupied and not comparable, warn and skip."},
        footerHeading = "%nNotes:%n",
        footer = {
                "- This command never signs in and never reads or writes what `qits login` keeps: it runs in a "
                        + "CI step container with no person. `qits login` and `qits git-login` do not apply to it.",
                "- Started under the name `qits-publish` (its own file, or a symlink to `qits`), any command runs "
                        + "exactly as `qits publish <command>`: `qits-publish sbom submit ...` behaves as "
                        + "`qits publish sbom submit ...`. A hand-written pipeline may still call it that way.",
                "- QITS_ARTIFACTS_URL names the store; every command that talks to it needs the variable set (or "
                        + "derivable from QITS_NPM_REGISTRY_URL or QITS_MAVEN_REGISTRY_URL, with a warning). "
                        + "QITS_DOCS_URL, QITS_NPM_REGISTRY_URL and QITS_NPM_PROXY_URL name the docs root and the "
                        + "two npm registries; a CI step sets what each command needs."},
        exitCodeListHeading = "%nExit codes:%n",
        exitCodeList = {
                "0:Published, or already published with the same bytes.",
                "1:Refused, and re-running will not help: invalid arguments, a 4xx, or the coordinate already "
                        + "holds different bytes.",
                "2:Could not ask, or could not be answered: no store configured, an I/O failure, or a 5xx. A "
                        + "step may retry a 2 and must not retry a 1."})
public class PublishCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }
}
