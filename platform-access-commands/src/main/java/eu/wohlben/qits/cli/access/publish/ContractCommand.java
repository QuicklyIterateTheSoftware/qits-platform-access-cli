package eu.wohlben.qits.cli.access.publish;

import picocli.CommandLine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** {@code qits artifacts publish contract} — one contract package, packed by the platform and published if changed. */
@CommandLine.Command(name = "contract",
        // Not mixinStandardHelpOptions: see MavenCommand's --version note.
        description = {"Pack a provider's golden masters or a consumer's pacts from a directory into one "
                + "package (a maven jar or an npm tarball), and publish it if its content changed. Prints "
                + "exactly one line on stdout: `published <version>` or `unchanged since <version>`.",
                "The packages are built deterministically: the same tree gives the same bytes. Inside the "
                        + "package the tree sits under golden-masters/ or pacts/, by --kind, whatever --from "
                        + "is called; the jar carries directory entries, because a class-path pact loader asks "
                        + "for the directory. Every contract package is if-changed, and decides on its own: "
                        + "packages built from the same tree agree without any link between them.",
                "--name is the coordinate qits-ci derived from the contracts: section; this command does not "
                        + "derive coordinates."},
        footerHeading = "%nExamples:%n",
        footer = {"  qits artifacts publish contract --kind golden-masters --ecosystem maven "
                + "--name eu.wohlben.qits:qits-projects-golden-masters --application qits-projects "
                + "--from golden-masters/ --version 2026.1002.1",
                "  qits artifacts publish contract --kind pacts --ecosystem maven "
                        + "--name eu.wohlben.qits:qits-workspaces-pacts-qits-projects --application qits-workspaces "
                        + "--provider qits-projects --from pacts/ --version 2026.1002.1"},
        exitCodeListHeading = "%nExit codes:%n",
        exitCodeList = {
                "0:Published, already published at this version with the same content, or unchanged.",
                "1:Refused: bad arguments, an empty --from, a 4xx, or this version already holds other content.",
                "2:Could not ask: no store configured, an I/O failure, a 5xx, or an unreadable answer; never "
                        + "read as unchanged."})
public class ContractCommand extends AbstractPublishCommand {

    @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
    boolean help;

    @CommandLine.Option(names = "--kind", paramLabel = "<golden-masters|pacts>", description = "What the tree is.")
    List<String> kind = new ArrayList<>();

    @CommandLine.Option(names = "--ecosystem", paramLabel = "<maven|npm>", description = "Which package to build.")
    List<String> ecosystem = new ArrayList<>();

    @CommandLine.Option(names = "--name", paramLabel = "<coordinate>",
            description = "groupId:artifactId for maven, the package name for npm.")
    List<String> name = new ArrayList<>();

    @CommandLine.Option(names = "--application", paramLabel = "<application>",
            description = "The application whose contracts these are; named in the package's description.")
    List<String> application = new ArrayList<>();

    @CommandLine.Option(names = "--provider", paramLabel = "<application>",
            description = "The provider a pact is with. Required for pacts, refused for golden masters.")
    List<String> provider = new ArrayList<>();

    @CommandLine.Option(names = "--from", paramLabel = "<dir>", description = "The tree to pack.")
    List<String> from = new ArrayList<>();

    @CommandLine.Option(names = "--version", paramLabel = "<version>", description = "The release version.")
    List<String> version = new ArrayList<>();

    @CommandLine.Unmatched
    List<String> unmatched = new ArrayList<>();

    @Override
    protected int run(Env env, Console console) {
        PublishArgs.noExtras(unmatched);
        ContractPackager.Kind kind = ContractPackager.Kind.of(PublishArgs.requiredOnce(this.kind, "--kind"));
        String ecosystem = PublishArgs.requiredOnce(this.ecosystem, "--ecosystem");
        String name = PublishArgs.requiredOnce(this.name, "--name");
        String application = PublishArgs.requiredOnce(this.application, "--application");
        String provider = PublishArgs.optionalOnce(this.provider, "--provider", null);
        Path from = Path.of(PublishArgs.requiredOnce(this.from, "--from"));
        String version = PublishArgs.requiredOnce(this.version, "--version");
        if (kind == ContractPackager.Kind.PACTS && provider == null) {
            throw CliException.policy("--provider is required for pacts: a pact is between a consumer and one provider");
        }
        if (kind == ContractPackager.Kind.GOLDEN_MASTERS && provider != null) {
            throw CliException.policy("--provider means nothing for golden masters, which are the provider's own");
        }
        String description = ContractPackager.description(kind, application, provider);
        Store store = Store.from(env, console);
        Decision.Outcome outcome = switch (ecosystem) {
            case "maven" -> new MavenPublisher(http(), store, env, console)
                    .publishContract(name, version, ContractPackager.jar(from, kind), description);
            case "npm" -> new NpmPublisher(http(), store, console).publish(
                    ContractPackager.npm(from, kind, name, version, description), Optional.empty(), List.of(), true,
                    false);
            default -> throw CliException.policy("--ecosystem '" + ecosystem + "' is maven or npm");
        };
        console.answer(outcome.line());
        return ExitCode.OK;
    }
}
