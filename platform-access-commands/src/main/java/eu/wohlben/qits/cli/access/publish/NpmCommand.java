package eu.wohlben.qits.cli.access.publish;

import picocli.CommandLine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code qits artifacts publish npm} — publish one npm package: the tarball is built here, in Java,
 * and uploaded with the registry's publish document. Its subcommands are the npm reasoning that
 * stays outside a publish ({@code dist-tag}, {@code rewrite-lockfile-origin}) and {@code plan},
 * which the build-only npm-library archetype no longer calls and a later release deletes.
 */
@CommandLine.Command(name = "npm",
        // Not mixinStandardHelpOptions: this command has its own --version (the package version);
        // see PlanCommand's note.
        subcommands = {NpmCommand.PlanCommand.class, NpmCommand.DistTagCommand.class,
                NpmCommand.RewriteLockfileOriginCommand.class},
        description = {"Publish one npm package from its built directory: the tarball is packed here "
                + "(deterministic: sorted entries, fixed mode, owner and mtime) and PUT with the registry's "
                + "publish document. Prints exactly one line on stdout: `published <version>` or "
                + "`unchanged since <version>`; the reasoning goes to stderr.",
                "package.json in --path must name --name at --version and must not be private. Every regular "
                        + "file is packed except node_modules/, .git/, .npmrc and the lockfiles, narrowed by "
                        + "\"files\" when the manifest has it (package.json, README* and LICENSE* always go "
                        + "in). A .npmignore is refused.",
                "A version below the registry's latest is a replay: it is published under the tag `replay` "
                        + "and leaves the real tags alone. Otherwise the `main` dist-tag is moved onto the "
                        + "version after the publish, on a re-run too.",
                "With --if-changed the package is uploaded only when its content hash differs from the newest "
                        + "published version's (by version order, never the latest tag). The manifest's "
                        + "version field is not part of the hash."},
        footerHeading = "%nExamples:%n",
        footer = {"  qits artifacts publish npm --name @qits/ui-components --path dist/qits-spa-ui-components "
                + "--sbom sbom.json --if-changed --version 2026.1002.1"},
        exitCodeListHeading = "%nExit codes:%n",
        exitCodeList = {
                "0:Published, already published at this version with the same content, or unchanged.",
                "1:Refused: bad arguments, a package.json that is not --name at --version, a .npmignore, a "
                        + "4xx, or this version already holds other content.",
                "2:Could not ask: no registry configured, an I/O failure, a 5xx, or an unreadable answer; "
                        + "never read as unchanged."})
public class NpmCommand extends AbstractPublishCommand {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
    boolean help;

    @CommandLine.Option(names = "--name", paramLabel = "<package>", description = "The npm package name.")
    List<String> name = new ArrayList<>();

    @CommandLine.Option(names = "--version", paramLabel = "<version>", description = "The release version.")
    List<String> version = new ArrayList<>();

    @CommandLine.Option(names = "--path", paramLabel = "<dir>",
            description = "The built package's directory, holding its package.json. Default: `.`.")
    List<String> path = new ArrayList<>();

    @CommandLine.Option(names = "--sbom", paramLabel = "<file>",
            description = "The package's CycloneDX document, hashed with the content. Required with --if-changed.")
    List<String> sbom = new ArrayList<>();

    @CommandLine.Option(names = "--include", paramLabel = "<glob>",
            description = "Hash only the files matching one of these globs (paths inside the package). Narrows "
                    + "the hash, never what is uploaded. Repeatable.")
    List<String> include = new ArrayList<>();

    @CommandLine.Option(names = "--if-changed",
            description = "Upload only when the content differs from the newest published version.")
    boolean ifChanged;

    @CommandLine.Unmatched
    List<String> unmatched = new ArrayList<>();

    @Override
    protected int run(Env env, Console console) {
        if (!unmatched.isEmpty() && !unmatched.get(0).startsWith("-")) {
            // A bare word here is a subcommand nobody declared, refused the way picocli refuses one.
            throw new CommandLine.ParameterException(spec.commandLine(), "Unknown subcommand '" + unmatched.get(0)
                    + "'; name one of plan, dist-tag or rewrite-lockfile-origin, or publish with --name and --version.");
        }
        if (unmatched.isEmpty() && name.isEmpty() && version.isEmpty() && path.isEmpty() && sbom.isEmpty()
                && include.isEmpty() && !ifChanged) {
            throw new CommandLine.ParameterException(spec.commandLine(),
                    "Name a command, or publish with --name and --version.");
        }
        PublishArgs.noExtras(unmatched);
        String name = PublishArgs.requiredOnce(this.name, "--name");
        String version = PublishArgs.requiredOnce(this.version, "--version");
        Path dir = Path.of(PublishArgs.optionalOnce(this.path, "--path", "."));
        Optional<Path> sbom = Optional.ofNullable(PublishArgs.optionalOnce(this.sbom, "--sbom", null)).map(Path::of);
        PublishArgs.ifChangedNeedsSbom(ifChanged, sbom.isPresent(), include);
        NpmPublisher publisher = new NpmPublisher(http(), Store.from(env, console), console);
        Decision.Outcome outcome = publisher.publish(NpmPublisher.pack(dir, name, version), sbom, include, ifChanged,
                true);
        console.answer(outcome.line());
        return ExitCode.OK;
    }

    @CommandLine.Command(name = "plan",
            // Not mixinStandardHelpOptions: qits-publish's own --version flag names the artifact
            // version, and the mixin's -V/--version (print the qits binary's own version) would
            // collide with it by name, and picocli then recognises neither. -h/--help alone is safe.
            description = {"Retired by `qits artifacts publish npm`, which decides, builds and uploads in one call; "
                    + "kept until the build-only npm-library archetype is live everywhere.",
                    "Decide, in one word on stdout, what to do with package@version: publish "
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
            Npm npm = new Npm(http(), Store.from(env, console), console);
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
            Npm npm = new Npm(http(), Store.from(env, console), console);
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
            Npm npm = new Npm(http(), Store.from(env, console), console);
            return npm.rewriteLockfileOrigin(Path.of(lockfile));
        }
    }
}
