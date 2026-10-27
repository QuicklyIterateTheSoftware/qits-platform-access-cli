package eu.wohlben.qits.cli.access.help;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.Table;
import eu.wohlben.qits.cli.access.projects.ProjectsApi;
import eu.wohlben.qits.cli.session.Mode;
import eu.wohlben.qits.cli.tui.api.Effect;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code qits help guide} (qits-1679): the platform's development guide, as qits-projects serves it
 * now ({@code GET /projects/api/guide}). Nothing of it is baked into the binary.
 * <p>
 * <b>Which variant.</b> {@code --for} names one. Without it, a workstation session asks for {@code
 * workstation}; a token home ({@code QITS_TOKEN}) or in-platform mode names none, and the service
 * picks the variant from the token's roles and claims (the desk's {@code qits:desk}, a refinement
 * room's {@code entity} claim, else an agent). The CLI does not read the token itself: a workspace
 * token is opaque, and only the service sees what it carries.
 */
@TuiCommand(effect = Effect.READ)
@CommandLine.Command(name = "guide", mixinStandardHelpOptions = true,
        description = {"Print the platform's development guide: how to develop on the qits platform, as markdown.",
                "The platform serves it, so it is always the current text. Without --for it prints the variant for "
                        + "where it runs: on a workstation signed in with `qits login`, the workstation guide (sign-in, "
                        + "Git, registries, builds, releases); inside the platform (QITS_TOKEN, or in-platform mode), "
                        + "the brief of the agent, desk or refinement room the token belongs to, with the development "
                        + "flow."},
        footerHeading = HelpText.EXAMPLES,
        footer = {"  qits help guide", "  qits help guide --list", "  qits help guide --for agent",
                "",
                "- Variants: workstation, agent, desk, refinement. --for picks one, to read what another holder reads.",
                "- Reads GET /projects/api/guide (with ?for=<variant>), and GET /projects/api/guide/variants for "
                        + "--list."},
        exitCodeListHeading = HelpText.EXIT_CODES,
        exitCodeList = {HelpText.DONE, HelpText.REFUSED,
                "2:Used wrongly (for example an unknown --for), not signed in, or the session ended."})
public class GuideCommand extends PlatformCommand {

    static final List<String> VARIANTS = List.of("workstation", "agent", "desk", "refinement");

    @CommandLine.Option(names = "--for", paramLabel = "<variant>",
            description = "The variant to print: workstation, agent, desk or refinement. Default: the one for where "
                    + "this runs.")
    String variant;

    @CommandLine.Option(names = "--list", description = "List the variants instead.")
    boolean list;

    @CommandLine.Option(names = "--projects-url", paramLabel = "<url>",
            description = "The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the "
                    + "session's idp address with `idp` swapped for `projects`.")
    String projectsUrl;

    @Override
    protected int execute(CliContext context) throws CliFailure, InterruptedException {
        if (list && variant != null) {
            throw new CliFailure("Give --list or --for, not both.", CliFailure.USAGE);
        }
        ProjectsApi api = ProjectsApi.connect(context, projectsUrl);
        if (list) {
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode one : api.developmentGuideVariants().path("variants")) {
                rows.add(List.of(ProjectsApi.text(one, "name"), ProjectsApi.text(one, "description")));
            }
            Table.print(context.out(), "", List.of("VARIANT", "WHAT IT IS FOR"), rows);
            return 0;
        }
        JsonNode answer = api.developmentGuide(wanted(context.mode()));
        String markdown = ProjectsApi.text(answer, "markdown");
        context.out().print(markdown.endsWith("\n") ? markdown : markdown + "\n");
        return 0;
    }

    /** The variant to ask for: --for, else workstation on a workstation, else none (the service picks). */
    String wanted(Mode mode) throws CliFailure {
        if (variant != null) {
            String named = variant.strip().toLowerCase(Locale.ROOT);
            if (!VARIANTS.contains(named)) {
                throw new CliFailure("--for must be one of " + String.join(", ", VARIANTS) + ", not '" + variant
                        + "'.", CliFailure.USAGE);
            }
            return named;
        }
        return mode == Mode.WORKSTATION ? "workstation" : null;
    }
}
