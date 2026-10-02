package eu.wohlben.qits.cli.access.publish;

import picocli.CommandLine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** {@code qits artifacts publish maven} — one declared maven entry, flattened, bundled and uploaded. */
@CommandLine.Command(name = "maven",
        // Not mixinStandardHelpOptions: qits-publish's own --version flag names the artifact
        // version, and the mixin's -V/--version (print the qits binary's own version) would
        // collide with it by name, and picocli then recognises neither. -h/--help alone is safe.
        description = {"Publish one maven module of the reactor at the working directory: its jar and a "
                + "flattened pom, with the content hash. Prints exactly one line on stdout: "
                + "`published <version>` or `unchanged since <version>`; the reasoning goes to stderr.",
                "The pom uploaded is generated from the module's effective model: no parent, every version "
                        + "resolved, and only external dependencies. Every dependency on another module of the "
                        + "same reactor is bundled into the jar (classes, resources, META-INF/services merged; "
                        + "any other path present twice with different bytes is refused), unless --link names "
                        + "it: a linked sibling stays a pom dependency at the version decided for it in this "
                        + "release. A pom-packaging module is a product (a parent or BOM another repository "
                        + "consumes) and keeps its dependencyManagement.",
                "With --if-changed the module is uploaded only when its content hash differs from the newest "
                        + "published version's. No published version, no stored hash, or another algorithm "
                        + "version all count as changed. A re-run at a version already published answers "
                        + "`published <version>` without uploading."},
        footerHeading = "%nExamples:%n",
        footer = {"  qits artifacts publish maven --name eu.wohlben.qits:qits-registries-npm --path npm "
                + "--sbom npm/target/sbom.json --link eu.wohlben.qits:qits-blobstore --if-changed "
                + "--version 2026.1002.1"},
        exitCodeListHeading = "%nExit codes:%n",
        exitCodeList = {
                "0:Published, already published at this version with the same content, or unchanged.",
                "1:Refused: bad arguments, a module that is not --name at --version, a bundling conflict, a "
                        + "4xx, or this version already holds other content.",
                "2:Could not ask: no store configured, an I/O failure, a 5xx, or an unreadable answer; never "
                        + "read as unchanged."})
public class MavenCommand extends AbstractPublishCommand {

    @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
    boolean help;

    @CommandLine.Option(names = "--name", paramLabel = "<groupId:artifactId>",
            description = "The coordinate the entry declares.")
    List<String> name = new ArrayList<>();

    @CommandLine.Option(names = "--version", paramLabel = "<version>", description = "The release version.")
    List<String> version = new ArrayList<>();

    @CommandLine.Option(names = "--path", paramLabel = "<dir>",
            description = "The module's directory, relative to the reactor root. Default: `.`.")
    List<String> path = new ArrayList<>();

    @CommandLine.Option(names = "--sbom", paramLabel = "<file>",
            description = "The module's CycloneDX document, hashed with the content. Required with --if-changed.")
    List<String> sbom = new ArrayList<>();

    @CommandLine.Option(names = "--include", paramLabel = "<glob>",
            description = "Hash only the jar entries matching one of these globs. Narrows the hash, never "
                    + "what is uploaded. Repeatable.")
    List<String> include = new ArrayList<>();

    @CommandLine.Option(names = "--link", paramLabel = "<groupId:artifactId>",
            description = "A reactor sibling that stays a pom dependency instead of being bundled. Repeatable.")
    List<String> link = new ArrayList<>();

    @CommandLine.Option(names = "--if-changed",
            description = "Upload only when the content differs from the newest published version.")
    boolean ifChanged;

    @CommandLine.Option(names = "--root", paramLabel = "<dir>",
            description = "The reactor root, whose pom.xml lists the modules. Default: the working directory.")
    List<String> root = new ArrayList<>();

    @CommandLine.Unmatched
    List<String> unmatched = new ArrayList<>();

    @Override
    protected int run(Env env, Console console) {
        PublishArgs.noExtras(unmatched);
        String name = PublishArgs.requiredOnce(this.name, "--name");
        String version = PublishArgs.requiredOnce(this.version, "--version");
        String path = PublishArgs.optionalOnce(this.path, "--path", ".");
        Path root = Path.of(PublishArgs.optionalOnce(this.root, "--root", "."));
        Optional<Path> sbom = Optional.ofNullable(PublishArgs.optionalOnce(this.sbom, "--sbom", null))
                .map(root::resolve);
        PublishArgs.ifChangedNeedsSbom(ifChanged, sbom.isPresent(), include);
        Decision.Outcome outcome = new MavenPublisher(http(), Store.from(env, console), env, console)
                .publish(root, name, version, path, sbom, include, link, ifChanged);
        console.answer(outcome.line());
        return ExitCode.OK;
    }
}
