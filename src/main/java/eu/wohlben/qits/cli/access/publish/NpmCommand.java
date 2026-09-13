package eu.wohlben.qits.cli.access.publish;

import picocli.CommandLine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** {@code qits artifacts publish npm} — the reasoning around an npm publish, without running npm itself. */
@CommandLine.Command(name = "npm", mixinStandardHelpOptions = true,
        subcommands = {NpmCommand.PlanCommand.class, NpmCommand.DistTagCommand.class,
                NpmCommand.RewriteLockfileOriginCommand.class},
        description = "What to do before an npm publish, what to do after it, and the lockfile edit a "
                + "step container needs. Nothing here runs `npm`; `npm publish` stays in the release step.")
public class NpmCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    @CommandLine.Command(name = "plan",
            // Not mixinStandardHelpOptions: qits-publish's own --version flag names the artifact
            // version, and the mixin's -V/--version (print the qits binary's own version) would
            // collide with it by name, and picocli then recognises neither. -h/--help alone is safe.
            description = {"Decide, in one word on stdout, what to do with package@version: publish "
                    + "(the ordinary case), skip (this exact version is already there; versions are "
                    + "immutable), or publish-replay (this version is below the registry's latest, so it "
                    + "must take a throwaway tag rather than move latest backwards).",
                    "Only the word goes to stdout; the reasoning goes to stderr, so "
                            + "`plan=$(qits artifacts publish npm plan ...)` captures just the word."},
            footerHeading = "%nExamples:%n",
            footer = "  plan=$(qits artifacts publish npm plan --package @qits/ui-components --version 2026.906.1)",
            exitCodeListHeading = "%nExit codes:%n",
            exitCodeList = {
                    "0:Decided (the word is on stdout).",
                    "1:Refused: bad arguments, or a 4xx.",
                    "2:Could not ask: no registry configured, an I/O failure, or a 5xx; never read as "
                            + "\"publish\"."})
    public static class PlanCommand extends AbstractPublishCommand {

        @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true,
                description = "Show this help message and exit.")
        boolean help;

        @CommandLine.Option(names = "--package", paramLabel = "<name>", description = "The npm package name.")
        List<String> pkg = new ArrayList<>();

        @CommandLine.Option(names = "--version", paramLabel = "<version>", description = "The version.")
        List<String> version = new ArrayList<>();

        @CommandLine.Unmatched
        List<String> unmatched = new ArrayList<>();

        @Override
        protected int run(Env env, Console console) {
            PublishArgs.noExtras(unmatched);
            String pkg = PublishArgs.requiredOnce(this.pkg, "--package");
            String version = PublishArgs.requiredOnce(this.version, "--version");
            Npm npm = new Npm(new Http(), Store.from(env, console), console);
            return npm.plan(pkg, version);
        }
    }

    @CommandLine.Command(name = "dist-tag",
            // Not mixinStandardHelpOptions: see PlanCommand's --version note above.
            description = "Point a dist-tag at a version. Always run, never guarded behind whether this "
                    + "run's publish happened: moving a tag onto the version it already names costs one "
                    + "request and succeeds.",
            footerHeading = "%nExamples:%n",
            footer = "  qits artifacts publish npm dist-tag --package @qits/ui-components --version 2026.906.1 "
                    + "--tag main",
            exitCodeListHeading = "%nExit codes:%n",
            exitCodeList = {
                    "0:The tag now names that version.",
                    "1:Refused: bad arguments, or a 4xx (for example a backwards move of latest).",
                    "2:Could not ask: no registry configured, an I/O failure, or a 5xx."})
    public static class DistTagCommand extends AbstractPublishCommand {

        @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true,
                description = "Show this help message and exit.")
        boolean help;

        @CommandLine.Option(names = "--package", paramLabel = "<name>", description = "The npm package name.")
        List<String> pkg = new ArrayList<>();

        @CommandLine.Option(names = "--version", paramLabel = "<version>", description = "The version.")
        List<String> version = new ArrayList<>();

        @CommandLine.Option(names = "--tag", paramLabel = "<tag>", description = "The dist-tag to move.")
        List<String> tag = new ArrayList<>();

        @CommandLine.Unmatched
        List<String> unmatched = new ArrayList<>();

        @Override
        protected int run(Env env, Console console) {
            PublishArgs.noExtras(unmatched);
            String pkg = PublishArgs.requiredOnce(this.pkg, "--package");
            String version = PublishArgs.requiredOnce(this.version, "--version");
            String tag = PublishArgs.requiredOnce(this.tag, "--tag");
            Npm npm = new Npm(new Http(), Store.from(env, console), console);
            return npm.distTag(pkg, version, tag);
        }
    }

    @CommandLine.Command(name = "rewrite-lockfile-origin", mixinStandardHelpOptions = true,
            description = {"Repoint every \"resolved\" URL in a lockfile at the registries this "
                    + "container can reach, keeping the path (and so the integrity hash's meaning) exactly "
                    + "as it was.",
                    "An entry under the hosted registry's own path is an @qits tarball and gets the "
                            + "hosted origin; every other entry gets the npmjs proxy's. Running this twice "
                            + "changes nothing the second time."},
            footerHeading = "%nExamples:%n",
            footer = "  qits artifacts publish npm rewrite-lockfile-origin --lockfile package-lock.json",
            exitCodeListHeading = "%nExit codes:%n",
            exitCodeList = {
                    "0:Rewritten, or already correct.",
                    "1:Refused: bad arguments.",
                    "2:Could not ask: the npm registry variables are not set, or the file cannot be "
                            + "read or written."})
    public static class RewriteLockfileOriginCommand extends AbstractPublishCommand {

        @CommandLine.Option(names = "--lockfile", paramLabel = "<path>",
                description = "The lockfile to rewrite in place. Default: package-lock.json.")
        List<String> lockfile = new ArrayList<>();

        @CommandLine.Unmatched
        List<String> unmatched = new ArrayList<>();

        @Override
        protected int run(Env env, Console console) {
            PublishArgs.noExtras(unmatched);
            String lockfile = PublishArgs.optionalOnce(this.lockfile, "--lockfile", "package-lock.json");
            Npm npm = new Npm(new Http(), Store.from(env, console), console);
            return npm.rewriteLockfileOrigin(Path.of(lockfile));
        }
    }
}
