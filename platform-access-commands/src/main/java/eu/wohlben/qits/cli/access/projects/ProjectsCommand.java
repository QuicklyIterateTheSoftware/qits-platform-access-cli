package eu.wohlben.qits.cli.access.projects;

import eu.wohlben.qits.cli.tui.api.TuiCommand;
import eu.wohlben.qits.cli.tui.api.Effect;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import eu.wohlben.qits.cli.access.complete.ProjectSource;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.Table;
import eu.wohlben.qits.cli.tui.api.Completes;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

@CommandLine.Command(name = "projects", mixinStandardHelpOptions = true,
        subcommands = {ProjectsCommand.ListCommand.class, ProjectsCommand.DeliveryCommand.class},
        description = "The platform's projects: list shows them, delivery shows one's delivery desk. Other commands "
                + "take a project's id, slug or name as --project.")
public class ProjectsCommand implements Runnable {

    @CommandLine.Mixin
    ProjectsOptions options;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = "List the projects: slug, name and id.",
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits projects list", "  qits projects list -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class ListCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        ProjectsCommand parent;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.options.output);
            JsonNode answer = ProjectsApi.connect(context, parent.options.projectsUrl).projects();
            if (json) {
                ProjectsApi.printJson(context.out(), answer);
                return 0;
            }
            List<JsonNode> projects = ProjectsApi.entries(answer, "project");
            if (projects.isEmpty()) {
                context.out().println("No projects.");
                return 0;
            }
            Table.print(context.out(), "", List.of("SLUG", "NAME", "ID"), projects.stream()
                    .map(p -> List.of(Table.cell(text(p, "slug"), 40), Table.cell(text(p, "name"), 40),
                            Table.cell(text(p, "id"), 64)))
                    .toList());
            return 0;
        }
    }

    /**
     * {@code qits projects delivery}: the project's front desk delivery loop, as the project read
     * carries it (epic qits-1440). Read from the project list, which every {@code --project} lookup
     * reads anyway: the single-project door takes the id only. A field the service does not send — a
     * service older than the loop sends no {@code delivery} at all — prints as the default the
     * service applies when a wrapper declares nothing, marked so.
     */
    @CommandLine.Command(name = "delivery", mixinStandardHelpOptions = true,
            description = {"A project's delivery desk: whether its front desk runs the delivery loop, how many items "
                    + "it keeps running at once, whether it may move a REFINED item to READY_FOR_DEV itself, the "
                    + "approvals it may give, and how often the loop ticks.",
                    "The project's wrapper declares it in .config/qits/project.yml under front_desk.delivery. A "
                            + "setting it does not declare prints as the default, marked (default)."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits projects delivery --project qits", "  qits projects delivery --project qits -o json",
                    "",
                    "- -o json prints the project's delivery object as the service sent it, null when it sent none."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED,
                    "2:Used wrongly (for example no --project, or one the platform does not know), not signed in, "
                            + "or the session ended."})
    @TuiCommand(effect = Effect.READ)
    public static class DeliveryCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        ProjectsCommand parent;

        @Completes(ProjectSource.class)
        @CommandLine.Option(names = "--project", paramLabel = "<project>",
                description = "The project (required): its id, slug or name.")
        String project;

        /** What the service answers for a wrapper that declares nothing ({@code ProjectDeliveryDto}). */
        static final List<List<String>> DEFAULTS = List.of(
                List.of("enabled", "enabled", "false"),
                List.of("maxRunning", "max_running", "2"),
                List.of("startRefined", "start_refined", "false"),
                List.of("approves", "approves", "none"),
                List.of("interval", "interval", "PT10M"));

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.options.output);
            String wanted = RepositoriesCommand.required(project, RepositoriesCommand.NAME_THE_PROJECT);
            JsonNode found = ProjectsApi.connect(context, parent.options.projectsUrl).project(wanted);
            JsonNode delivery = found.path("delivery");
            if (json) {
                ProjectsApi.printJson(context.out(), delivery.isObject() ? delivery
                        : NullNode.getInstance());
                return 0;
            }
            context.out().println("Project " + SafeText.line(ProjectsApi.projectLabel(found)) + ": delivery desk");
            List<List<String>> rows = new ArrayList<>();
            for (List<String> setting : DEFAULTS) {
                JsonNode value = delivery.path(setting.get(0));
                rows.add(List.of(setting.get(1), value.isMissingNode() || value.isNull()
                        ? setting.get(2) + " (default)" : shown(value)));
            }
            Table.print(context.out(), "", List.of("SETTING", "VALUE"), rows);
            return 0;
        }

        private static String shown(JsonNode value) {
            if (value.isArray()) {
                List<String> all = new ArrayList<>();
                value.forEach(v -> all.add(v.asText()));
                return all.isEmpty() ? "none" : Table.cell(String.join(", ", all), 120);
            }
            return Table.cell(value.asText(), 60);
        }
    }
}
