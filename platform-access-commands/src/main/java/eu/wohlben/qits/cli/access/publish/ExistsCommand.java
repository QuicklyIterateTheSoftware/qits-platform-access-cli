package eu.wohlben.qits.cli.access.publish;

import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;

/** {@code qits artifacts publish exists} — is a coordinate already published? Three answers, not two. */
@CommandLine.Command(name = "exists", mixinStandardHelpOptions = true,
        description = {"Ask whether a coordinate is already published: daemon, docs, npm or sbom.",
                "An sbom coordinate has two name parts, written <packageType>/<packageName>, for example "
                        + "docker/qits/qits-ci. A step that read an unreachable store as \"absent\" would "
                        + "republish on every outage, and one that read it as \"present\" would skip a publish "
                        + "that never happened, so a third exit code says \"could not ask\" instead."},
        footerHeading = "%nExamples:%n",
        footer = {
                "  qits artifacts publish exists daemon qits-platform-access-cli 2026.906.1",
                "  qits artifacts publish exists sbom docker/qits/qits-ci 2026.906.1",
                "  qits artifacts publish exists npm @qits/ui-components 2026.906.1"},
        exitCodeListHeading = "%nExit codes:%n",
        exitCodeList = {
                "0:Published.",
                "1:Not published, or the arguments are wrong.",
                "2:Could not ask: no store configured, an I/O failure, or a 5xx."})
public class ExistsCommand extends AbstractPublishCommand {

    @CommandLine.Parameters(index = "0..*", paramLabel = "<type> <name> <version>",
            description = "The type (daemon, docs, npm or sbom), the name, and the version, in that order.")
    List<String> positionals = new ArrayList<>();

    @CommandLine.Unmatched
    List<String> unmatched = new ArrayList<>();

    @Override
    protected int run(Env env, Console console) {
        PublishArgs.noExtras(unmatched);
        String type = Store.normalizeType(positional(0, "a type: daemon, docs, npm or sbom"));
        String name = positional(1, "a name");
        String version = positional(2, "a version");
        if (positionals.size() > 3) {
            throw CliException.policy("unexpected argument " + positionals.get(3));
        }
        Publisher publisher = new Publisher(http(), Store.from(env, console), console);
        return publisher.exists(type, name, version);
    }

    private String positional(int index, String what) {
        if (index >= positionals.size()) {
            throw CliException.policy("expected " + what);
        }
        return positionals.get(index);
    }
}
