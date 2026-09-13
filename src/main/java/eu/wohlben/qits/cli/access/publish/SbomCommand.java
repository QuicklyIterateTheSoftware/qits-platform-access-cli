package eu.wohlben.qits.cli.access.publish;

import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** {@code qits artifacts publish sbom} — a CycloneDX document, submitted as-is or built from a Dockerfile. */
@CommandLine.Command(name = "sbom", mixinStandardHelpOptions = true,
        subcommands = {SbomCommand.SubmitCommand.class, SbomCommand.FromDockerfileCommand.class},
        description = "An SBOM: submit a document that already exists, or build one from a Dockerfile's FROM "
                + "lines.")
public class SbomCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    @CommandLine.Command(name = "submit",
            // Not mixinStandardHelpOptions: qits-publish's own --version flag names the artifact
            // version, and the mixin's -V/--version (print the qits binary's own version) would
            // collide with it by name, and picocli then recognises neither. -h/--help alone is safe.
            description = "Publish a CycloneDX document at (packageType, name, version).",
            footerHeading = "%nExamples:%n",
            footer = "  qits artifacts publish sbom submit --type docker --name qits/qits-ci --version 2026.906.1 "
                    + "--file sbom.json",
            exitCodeListHeading = "%nExit codes:%n",
            exitCodeList = {
                    "0:Published, or already published with the same bytes.",
                    "1:Refused: bad arguments, a 4xx, or the coordinate already holds different bytes.",
                    "2:Could not ask: no store configured, an I/O failure, or a 5xx."})
    public static class SubmitCommand extends AbstractPublishCommand {

        @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true,
                description = "Show this help message and exit.")
        boolean help;

        @CommandLine.Option(names = "--type", paramLabel = "<npm|maven|docker|daemon>",
                description = "The package type the sbom store files this under.")
        List<String> type = new ArrayList<>();

        @CommandLine.Option(names = "--name", paramLabel = "<name>", description = "The package name.")
        List<String> name = new ArrayList<>();

        @CommandLine.Option(names = "--version", paramLabel = "<version>", description = "The version.")
        List<String> version = new ArrayList<>();

        @CommandLine.Option(names = "--file", paramLabel = "<path>",
                description = "The CycloneDX document to publish.")
        List<String> file = new ArrayList<>();

        @CommandLine.Unmatched
        List<String> unmatched = new ArrayList<>();

        @Override
        protected int run(Env env, Console console) {
            PublishArgs.noExtras(unmatched);
            String type = PublishArgs.requiredOnce(this.type, "--type");
            String name = PublishArgs.requiredOnce(this.name, "--name");
            String version = PublishArgs.requiredOnce(this.version, "--version");
            String file = PublishArgs.requiredOnce(this.file, "--file");
            Publisher publisher = new Publisher(new Http(), Store.from(env, console), console);
            return publisher.sbomSubmit(Store.normalizeType(type), name, version, Path.of(file));
        }
    }

    @CommandLine.Command(name = "from-dockerfile", mixinStandardHelpOptions = true,
            description = {"Build a CycloneDX document from a Dockerfile's FROM lines: one component per "
                    + "distinct upstream image. Publishes nothing itself: write the file with -o, then "
                    + "`sbom submit` it.",
                    "${BUILDER_IMAGE}-style variables in a FROM resolve from the file's own ARG default, or "
                            + "from --build-arg, in the precedence a real build has."},
            footerHeading = "%nExamples:%n",
            footer = {
                    "  qits artifacts publish sbom from-dockerfile --root-name qits/qits-ci --root-version 2026.906.1 "
                            + "-o sbom.json",
                    "  qits artifacts publish sbom from-dockerfile --root-name x --root-version 1 "
                            + "--dockerfile a.Dockerfile --dockerfile b.Dockerfile "
                            + "--build-arg BASE=alpine:3.20 -o sbom.json"},
            exitCodeListHeading = "%nExit codes:%n",
            exitCodeList = {
                    "0:Wrote the document.",
                    "1:Refused: bad arguments, a Dockerfile with no FROM line, or a variable nothing resolves."})
    public static class FromDockerfileCommand extends AbstractPublishCommand {

        @CommandLine.Option(names = "--root-name", paramLabel = "<name>",
                description = "The image's own name, for the document's root component.")
        List<String> rootName = new ArrayList<>();

        @CommandLine.Option(names = "--root-version", paramLabel = "<version>",
                description = "The image's own version.")
        List<String> rootVersion = new ArrayList<>();

        @CommandLine.Option(names = "--dockerfile", paramLabel = "<path>",
                description = "A Dockerfile to read FROM lines from. Repeatable. Default: Dockerfile.")
        List<String> dockerfile = new ArrayList<>();

        @CommandLine.Option(names = "--build-arg", paramLabel = "<NAME=value>",
                description = "A build argument, for a FROM that names one. Repeatable.")
        List<String> buildArg = new ArrayList<>();

        @CommandLine.Option(names = {"-o", "--output"}, paramLabel = "<path>",
                description = "Where to write the document. Required, exactly once.")
        List<String> output = new ArrayList<>();

        @CommandLine.Unmatched
        List<String> unmatched = new ArrayList<>();

        @Override
        protected int run(Env env, Console console) {
            PublishArgs.noExtras(unmatched);
            String rootName = PublishArgs.requiredOnce(this.rootName, "--root-name");
            String rootVersion = PublishArgs.requiredOnce(this.rootVersion, "--root-version");
            Map<String, String> buildArgs = DockerfileSbom.buildArgs(this.buildArg);
            if (this.output.size() != 1) {
                throw CliException.policy("-o is required exactly once — name the document to write");
            }
            String output = this.output.get(0);
            List<Path> files = new ArrayList<>();
            for (String path : this.dockerfile.isEmpty() ? List.of("Dockerfile") : this.dockerfile) {
                files.add(Path.of(path));
            }
            List<DockerfileSbom.Base> bases = DockerfileSbom.bases(files, buildArgs);
            write(Path.of(output), DockerfileSbom.document(rootName, rootVersion, bases));
            console.info("wrote " + output + ": " + bases.size() + " base image(s) from " + files.size()
                    + " Dockerfile(s)");
            return ExitCode.OK;
        }

        private static void write(Path path, String content) {
            try {
                Path parent = path.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(path, content, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw CliException.transport("cannot write " + path + ": " + e.getMessage(), e);
            }
        }
    }
}
