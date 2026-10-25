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
        subcommands = {ProjectsCommand.ListCommand.class, ProjectsCommand.DeliveryCommand.class,
                ProjectsCommand.DeskCommand.class},
        description = "The platform's projects: list shows them, delivery shows one's delivery desk, desk talks to "
                + "one's front desk. Other commands take a project's id, slug or name as --project.")
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
     * {@code qits projects delivery}: the project's front desk delivery settings, as the project read
     * carries it (epic qits-1440). Read from the project list, which every {@code --project} lookup
     * reads anyway: the single-project door takes the id only. A field the service does not send — a
     * service older than the delivery desk sends no {@code delivery} at all — prints as the default the
     * service applies when a wrapper declares nothing, marked so.
     */
    @CommandLine.Command(name = "delivery", mixinStandardHelpOptions = true,
            description = {"A project's delivery desk: whether its front desk holds the delivery rights, how many "
                    + "items it keeps running at once, whether it may move a REFINED item to READY_FOR_DEV "
                    + "itself, and the approvals it may give. The desk runs only when a person prompts it.",
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
                List.of("approves", "approves", "none"));

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

    /**
     * {@code qits projects desk}: a turn typed into the project's front desk session, and that
     * session's last answer (qits-1572). The session is the one the projects daemon's front desk
     * keeper launched and keeps. Nothing types into it on a schedule (qits-1597): it runs only when a
     * person prompts it, here or from the desk panel. The answer is what an agent
     * wrote, so its table form goes through {@link SafeText} a line at a time and its JSON form
     * through {@link SafeJson}.
     */
    @CommandLine.Command(name = "desk", mixinStandardHelpOptions = true,
            subcommands = {DeskCommand.PromptCommand.class, DeskCommand.AnswerCommand.class},
            description = {"A project's front desk: prompt types a turn into its kept front desk session, answer "
                    + "shows that session's last answer.",
                    "The session is the one the project's front desk keeps running in its agent container. It runs "
                            + "only when a person prompts it; nothing prompts it on a schedule. It is not a "
                            + "session a person started from the desk panel."},
            footerHeading = "%nNotes:%n",
            footer = {"- Both take qits:admin or qits:admin-agent. An agent with qits:agent alone is refused "
                    + "(HTTP 403)."})
    public static class DeskCommand implements Runnable {

        @CommandLine.ParentCommand
        ProjectsCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
        }

        @CommandLine.Command(name = "prompt", mixinStandardHelpOptions = true,
                description = {"Type a turn into the project's front desk session. The words after the options "
                        + "are joined with spaces into the turn's text.",
                        "Sends POST /projects/api/projects/{projectId}/front-desk/prompt. When the session is "
                                + "idle the turn is typed at once (delivered). While it is busy or waiting on a "
                                + "permission prompt, the turn waits in the desk's queue and is typed in order "
                                + "once the session is idle (queued, with its position). Either way it prints the "
                                + "command id; `qits projects desk answer` shows what the desk answered."},
                footerHeading = HelpText.EXAMPLES,
                footer = {"  qits projects desk prompt --project qits What is in flight right now?",
                        "  qits projects desk prompt --project qits -o json Summarise the open release requests",
                        "  qits projects desk prompt --project qits Run one pass of the delivery loop: read "
                                + "/tmp/qits-delivery-brief.md and follow it.",
                        "",
                        "- Takes qits:admin or qits:admin-agent; an agent with qits:agent alone is refused (HTTP "
                                + "403).",
                        "- The turn goes to the session the front desk keeps. While the turn runs the session is "
                                + "busy, and a later turn waits in the queue until it is idle again.",
                        "- A delivery pass runs only when prompted, as in the last example; nothing starts one "
                                + "on a schedule (qits-1597).",
                        "- -o json prints the service's answer: commandId, state, delivered, queued, position."},
                exitCodeListHeading = HelpText.EXIT_CODES,
                exitCodeList = {"0:The desk took the turn: delivered, or queued.",
                        "1:The platform refused (for example your roles, HTTP 403, a project it does not know, "
                                + "HTTP 404, a front desk that is not running, HTTP 409, a full queue, HTTP 429, "
                                + "or no projects daemon to reach the desk, HTTP 503), or cannot be reached.",
                        "2:Used wrongly (for example no --project, one the platform does not know, or no text), "
                                + "not signed in, or the session ended."})
        @TuiCommand(effect = Effect.WRITE)
        public static class PromptCommand extends PlatformCommand {

            @CommandLine.ParentCommand
            DeskCommand desk;

            @Completes(ProjectSource.class)
            @CommandLine.Option(names = "--project", paramLabel = "<project>",
                    description = "The project (required): its id, slug or name.")
            String project;

            @CommandLine.Parameters(paramLabel = "<text>", arity = "0..*",
                    description = "The turn's text (required). The words are joined with spaces.")
            List<String> words;

            @Override
            protected int execute(CliContext context) throws CliFailure, InterruptedException {
                boolean json = json(desk.parent.options.output);
                String wanted = RepositoriesCommand.required(project, RepositoriesCommand.NAME_THE_PROJECT);
                String text = words == null ? "" : String.join(" ", words).strip();
                if (text.isEmpty()) {
                    throw new CliFailure("Name the turn's text: qits projects desk prompt --project <project> "
                            + "<text...>.", CliFailure.USAGE);
                }
                ProjectsApi api = ProjectsApi.connect(context, desk.parent.options.projectsUrl);
                JsonNode found = api.project(wanted);
                JsonNode answer = api.deskPrompt(text(found, "id"), text);
                if (json) {
                    SafeJson.print(context.out(), answer);
                    return 0;
                }
                String label = SafeText.line(ProjectsApi.projectLabel(found));
                String command = SafeText.line(text(answer, "commandId"));
                String to = command.isEmpty() ? "" : " (command " + command + ")";
                if (answer.path("delivered").asBoolean(false)) {
                    context.out().println("Project " + label + ": delivered to the front desk" + to + ".");
                } else if (answer.path("queued").asBoolean(false)) {
                    JsonNode position = answer.path("position");
                    String state = SafeText.line(text(answer, "state"));
                    context.out().println("Project " + label + ": queued at "
                            + (position.isNumber() ? "position " + position.asInt() : "the desk")
                            + to + ". The desk is " + (state.isEmpty() ? "not idle" : state)
                            + "; the turn is typed once it is idle.");
                } else {
                    context.out().println("Project " + label + ": the front desk took the turn" + to
                            + ", neither delivered nor queued by its own account.");
                }
                return 0;
            }
        }

        @CommandLine.Command(name = "answer", mixinStandardHelpOptions = true,
                description = {"Show the last answer of the project's front desk session: its text, when it "
                        + "was given, the command and session it came from, and the session's state.",
                        "Reads GET /projects/api/projects/{projectId}/front-desk/last-answer. A desk that has "
                                + "not answered yet says so and exits with 0."},
                footerHeading = HelpText.EXAMPLES,
                footer = {"  qits projects desk answer --project qits",
                        "  qits projects desk answer --project qits -o json",
                        "",
                        "- Takes qits:admin or qits:admin-agent; an agent with qits:agent alone is refused (HTTP "
                                + "403), although it changes nothing.",
                        "- The last answer is the session's, whatever turn it answered: a prompt of yours or "
                                + "somebody else's.",
                        "- -o json prints the service's answer: commandId, sessionId, state, text, at."},
                exitCodeListHeading = HelpText.EXIT_CODES,
                exitCodeList = {"0:Done, also when the desk has not answered yet.",
                        "1:The platform refused (for example your roles, HTTP 403, a project it does not know, "
                                + "HTTP 404, or no projects daemon to reach the desk, HTTP 503), or cannot be "
                                + "reached.",
                        "2:Used wrongly (for example no --project, or one the platform does not know), not "
                                + "signed in, or the session ended."})
        @TuiCommand(effect = Effect.READ)
        public static class AnswerCommand extends PlatformCommand {

            @CommandLine.ParentCommand
            DeskCommand desk;

            @Completes(ProjectSource.class)
            @CommandLine.Option(names = "--project", paramLabel = "<project>",
                    description = "The project (required): its id, slug or name.")
            String project;

            @Override
            protected int execute(CliContext context) throws CliFailure, InterruptedException {
                boolean json = json(desk.parent.options.output);
                String wanted = RepositoriesCommand.required(project, RepositoriesCommand.NAME_THE_PROJECT);
                ProjectsApi api = ProjectsApi.connect(context, desk.parent.options.projectsUrl);
                JsonNode found = api.project(wanted);
                JsonNode answer = api.deskAnswer(text(found, "id"));
                if (json) {
                    SafeJson.print(context.out(), answer);
                    return 0;
                }
                String label = SafeText.line(ProjectsApi.projectLabel(found));
                JsonNode text = answer.path("text");
                if (!text.isTextual()) {
                    context.out().println("Project " + label + ": the front desk has not answered yet.");
                    return 0;
                }
                context.out().println("Project " + label + ": the front desk's last answer");
                List<List<String>> rows = new ArrayList<>();
                for (String field : List.of("at", "state", "commandId", "sessionId")) {
                    String value = SafeText.line(text(answer, field));
                    rows.add(List.of(field, Table.cell(value.isEmpty() ? "-" : value, 80)));
                }
                Table.print(context.out(), "", List.of("FIELD", "VALUE"), rows);
                context.out().println();
                WorkEntities.lines(context.out(), "", text.asText());
                return 0;
            }
        }
    }
}
