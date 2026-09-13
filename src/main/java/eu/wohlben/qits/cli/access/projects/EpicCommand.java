package eu.wohlben.qits.cli.access.projects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.Table;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

/**
 * The epics of one project. An epic's title, description, and its features' and tasks' titles and
 * descriptions are written by people and agents, so, like a ticket's, they are untrusted text: every
 * value goes through {@link SafeText} before a person sees it, and the JSON form writes control
 * characters as escapes.
 */
@CommandLine.Command(name = "epic", mixinStandardHelpOptions = true,
        subcommands = {EpicCommand.ListCommand.class, EpicCommand.NewCommand.class, EpicCommand.DetailsCommand.class,
                EpicCommand.UpdateCommand.class},
        description = {"The epics of one project: work that needs a plan, broken into features and tasks. list "
                        + "shows them, new files one, and details shows one with its description and, when the "
                        + "platform returns them, its features and their tasks.",
                "Statuses: REFINING (a new epic starts here, its plan still being drafted), IMPLEMENTATION (the "
                        + "plan is frozen and being built), SUPERSEDED and ABANDONED."},
        footerHeading = "%nNotes:%n",
        footer = {
                "- --project, --output and --projects-url may come before or after the command. So may --epic, "
                        + "which only `details` takes.",
                "- Reading epics needs the role qits:admin or qits:agent. Filing one needs qits:admin.",
                "- A small bug or improvement is a ticket, not an epic. qits does not move an epic to "
                        + "implementation, supersede or abandon it yet."})
public class EpicCommand implements Runnable {

    static final String NAME_THE_EPIC = "Name the epic: --epic <id, slug or the start of the id>.";

    @CommandLine.Mixin
    ProjectsOptions options;

    @CommandLine.Option(names = "--project", paramLabel = "<project>", scope = CommandLine.ScopeType.INHERIT,
            description = "The project: its id, slug or name.")
    String project;

    /** Only so that --epic may also come before `details`; list and new refuse it. */
    @CommandLine.Option(names = "--epic", paramLabel = "<epic>",
            description = "The epic, for details: its id, its slug, or the start of its id.")
    String epic;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    private record Scope(ProjectsApi api, String projectId, String label) {
    }

    /** Checks the name, then finds the project. */
    private Scope scope(CliContext context) throws CliFailure, InterruptedException {
        String wanted = RepositoriesCommand.required(project, RepositoriesCommand.NAME_THE_PROJECT);
        ProjectsApi api = ProjectsApi.connect(context, options.projectsUrl);
        JsonNode found = api.project(wanted);
        return new Scope(api, text(found, "id"), ProjectsApi.projectLabel(found));
    }

    private void noEpic(String command) throws CliFailure {
        if (epic != null) {
            throw new CliFailure("--epic is for `qits epic details`, not `qits epic " + command + "`.",
                    CliFailure.USAGE);
        }
    }

    /**
     * The project's epic whose id or slug is {@code wanted}, else whose id starts with it. It reads
     * every epic of the project. The service finds an epic by its id alone, so this lookup is also
     * what keeps the command off another project's epic.
     */
    private static JsonNode find(Scope scope, String wanted) throws CliFailure, InterruptedException {
        List<JsonNode> all = ProjectsApi.entries(scope.api().epics(scope.projectId(), null), "epic");
        List<JsonNode> matches = all.stream()
                .filter(e -> wanted.equals(text(e, "id")) || wanted.equals(text(e, "slug")))
                .toList();
        if (matches.isEmpty()) {
            String start = wanted.toLowerCase(Locale.ROOT);
            matches = all.stream().filter(e -> text(e, "id").toLowerCase(Locale.ROOT).startsWith(start)).toList();
        }
        if (matches.isEmpty()) {
            throw new CliFailure("Project " + scope.label() + " has no epic with the id or slug '" + wanted
                    + "', and no epic id starts with it. `qits epic --project " + scope.label()
                    + " list` shows them.", CliFailure.USAGE);
        }
        if (matches.size() > 1) {
            throw new CliFailure("'" + wanted + "' fits more than one epic of project " + scope.label() + ": "
                    + matches.stream().map(e -> cell(text(e, "id"), 64) + " (" + cell(text(e, "title"), 40) + ")")
                            .collect(Collectors.joining(", "))
                    + ". Give more of the id.", CliFailure.USAGE);
        }
        return matches.getFirst();
    }

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = {"List the project's epics, oldest first: id, slug, status, title, and when it last "
                    + "changed.",
                    "Without --status it lists every epic. The ID column shows the first 8 characters of the id, "
                            + "which is enough for `details`."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits epic --project qits list",
                    "  qits epic --project qits list --status REFINING",
                    "  qits epic list --project qits -o json",
                    "",
                    "- The service applies --status, and refuses a status it does not know (HTTP 400) rather than "
                            + "answer with no epics."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class ListCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        EpicCommand parent;

        @CommandLine.Option(names = "--status", paramLabel = "<STATUS>",
                description = "Only the epics in this status: REFINING, IMPLEMENTATION, SUPERSEDED or ABANDONED. "
                        + "Default: every status.")
        String status;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.options.output);
            parent.noEpic("list");
            String wantedStatus = upper(status);
            Scope scope = parent.scope(context);
            JsonNode answer;
            try {
                answer = scope.api().epics(scope.projectId(), wantedStatus);
            } catch (CliFailure refused) {
                if (refused.status() == 400 && wantedStatus != null) {
                    throw new CliFailure("No epic status is called '" + wantedStatus + "' (HTTP 400). Statuses: "
                            + "REFINING, IMPLEMENTATION, SUPERSEDED, ABANDONED.", CliFailure.FAILED);
                }
                throw refused;
            }
            if (json) {
                printJson(context.out(), answer);
                return 0;
            }
            List<JsonNode> epics = ProjectsApi.entries(answer, "epic");
            if (epics.isEmpty()) {
                String which = wantedStatus == null ? "" : wantedStatus + " ";
                context.out().println("No " + which + "epics in project " + scope.label() + ".");
                return 0;
            }
            printEpics(context.out(), epics);
            return 0;
        }
    }

    @CommandLine.Command(name = "new", mixinStandardHelpOptions = true,
            description = {"File an epic in the project: work that needs a plan.",
                    "The epic starts REFINING, its plan still being drafted. The command prints it the way "
                            + "`details` does."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits epic --project qits new --title \"Let an agent dispatch on an epic\"",
                    "  qits epic --project qits new --title \"Live telemetry\" --description \"Stream logs, spans "
                            + "and metrics as they arrive.\"",
                    "  qits epic new --project qits --title \"Live telemetry\" --description-file plan.md",
                    "  cat plan.md | qits epic --project qits new --title \"Live telemetry\" --description-file -",
                    "",
                    "- The description is Markdown. --description-file - reads it from stdin."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The epic is filed.",
                    "1:The platform refused (for example your roles, HTTP 403), or cannot be reached.",
                    "2:Used wrongly (for example an empty title, or a description file that cannot be read), not "
                            + "signed in, or the session ended."})
    public static class NewCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        EpicCommand parent;

        @CommandLine.Option(names = "--title", paramLabel = "<text>", required = true,
                description = "What the epic delivers, in a short line.")
        String title;

        @CommandLine.Option(names = "--description", paramLabel = "<text>",
                description = "The long form, in Markdown: the plan, or as much of it as there is so far.")
        String description;

        @CommandLine.Option(names = "--description-file", paramLabel = "<path>",
                description = "Read the description from this file (UTF-8), or from stdin for -. Not together with "
                        + "--description.")
        String descriptionFile;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.options.output);
            parent.noEpic("new");
            if (title.isBlank()) {
                throw new CliFailure("--title must not be empty.", CliFailure.USAGE);
            }
            String body = description(context);
            Scope scope = parent.scope(context);
            JsonNode answer = scope.api().createEpic(scope.projectId(), title.strip(), body);
            if (json) {
                printJson(context.out(), answer);
                return 0;
            }
            printEpic(context.out(), answer.path("epic"), null);
            return 0;
        }

        /** The text of --description or --description-file, without trailing blanks; null for none. */
        private String description(CliContext context) throws CliFailure {
            if (description != null && descriptionFile != null) {
                throw new CliFailure("Give --description or --description-file, not both.", CliFailure.USAGE);
            }
            String text = description;
            if (descriptionFile != null) {
                text = descriptionFile.equals("-") ? stdin(context) : file(descriptionFile);
            }
            return text == null || text.isBlank() ? null : text.stripTrailing();
        }

        private static String stdin(CliContext context) throws CliFailure {
            try {
                return new String(context.in().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new CliFailure("Cannot read the description from stdin: " + e.getMessage(), CliFailure.USAGE);
            }
        }

        private static String file(String path) throws CliFailure {
            try {
                return Files.readString(Path.of(path), StandardCharsets.UTF_8);
            } catch (NoSuchFileException e) {
                throw new CliFailure("There is no file " + path + " (--description-file).", CliFailure.USAGE);
            } catch (CharacterCodingException e) {
                throw new CliFailure("The file " + path + " is not UTF-8 text (--description-file).", CliFailure.USAGE);
            } catch (IOException | InvalidPathException e) {
                throw new CliFailure("Cannot read " + path + " (--description-file): " + e.getMessage(), CliFailure.USAGE);
            }
        }
    }

    @CommandLine.Command(name = "details", mixinStandardHelpOptions = true,
            description = {"Show one epic: id, slug, status, title, its description, and, when the platform "
                    + "returns them, its features and their tasks.",
                    "--epic takes the epic's id, its slug, or the start of its id (list shows 8 characters). "
                            + "Terminal control characters are taken out of the text."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits epic --project qits details --epic 4f2a91c0",
                    "  qits epic details --epic live-telemetry --project qits",
                    "  qits epic --project qits details --epic 4f2a91c0 -o json | jq -r .epic.description",
                    "",
                    "- -o json prints one object: the epic as the service answers it, and its features, each with "
                            + "its tasks. Control characters are written as escapes."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The platform refused (for example the epic was deleted a moment ago, HTTP 404), or cannot be "
                            + "reached.",
                    "2:Used wrongly (for example an --epic that fits no epic of the project, or more than one), not "
                            + "signed in, or the session ended."})
    public static class DetailsCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        EpicCommand parent;

        @CommandLine.Option(names = "--epic", paramLabel = "<epic>",
                description = "The epic (required, before or after details): its id, its slug, or enough of the "
                        + "start of its id to name one.")
        String epic;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.options.output);
            String wanted = RepositoriesCommand.required(epic != null ? epic : parent.epic, NAME_THE_EPIC);
            Scope scope = parent.scope(context);
            String id = text(find(scope, wanted), "id");
            JsonNode one;
            List<JsonNode> features;
            try {
                one = scope.api().epic(id);
                features = ProjectsApi.entries(scope.api().epicFeatures(id), "feature");
            } catch (CliFailure refused) {
                if (refused.status() == 404) {
                    throw new CliFailure("No such epic: " + id + " (HTTP 404). It may have been deleted a moment ago.",
                            CliFailure.FAILED);
                }
                throw refused;
            }
            List<FeatureNode> tree = new ArrayList<>();
            for (JsonNode feature : features) {
                List<JsonNode> tasks = ProjectsApi.entries(scope.api().featureTasks(text(feature, "id")), "task");
                tree.add(new FeatureNode(feature, tasks));
            }
            JsonNode found = one.has("epic") ? one.get("epic") : one;
            if (json) {
                ObjectNode both = JsonNodeFactory.instance.objectNode();
                both.set("epic", found);
                ArrayNode list = both.putArray("features");
                for (FeatureNode node : tree) {
                    ObjectNode entry = JsonNodeFactory.instance.objectNode();
                    entry.set("feature", node.feature());
                    ArrayNode taskList = entry.putArray("tasks");
                    node.tasks().forEach(task -> taskList.add(JsonNodeFactory.instance.objectNode().set("task", task)));
                    list.add(entry);
                }
                printJson(context.out(), both);
                return 0;
            }
            printEpic(context.out(), found, tree);
            return 0;
        }
    }

    @CommandLine.Command(name = "update", mixinStandardHelpOptions = true,
            description = {"Edit an epic's title or description.",
                    "Give --title, --description or --description-file, or more than one; a field you leave out "
                            + "keeps its current value. Prints the epic afterwards the way `details` does."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits epic --project qits update --epic 4f2a91c0 --title \"Live telemetry v2\"",
                    "  qits epic --project qits update --epic live-telemetry --description \"Revised plan.\"",
                    "  qits epic update --project qits --epic 4f2a91c0 --description-file plan.md",
                    "",
                    "- The service allows this only while the epic is REFINING: its scope freezes once "
                            + "implementation starts, and an edit past that point is refused (HTTP 409)."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The platform refused (for example the epic's scope is frozen, HTTP 409), or cannot be "
                            + "reached.",
                    "2:Used wrongly (for example neither --title nor a description given, an empty --title, or an "
                            + "--epic that fits no epic of the project, or more than one), not signed in, or the "
                            + "session ended."})
    public static class UpdateCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        EpicCommand parent;

        @CommandLine.Option(names = "--epic", paramLabel = "<epic>",
                description = "The epic (required, before or after update): its id, its slug, or enough of the "
                        + "start of its id to name one.")
        String epic;

        @CommandLine.Option(names = "--title", paramLabel = "<text>",
                description = "The new title. Default: unchanged.")
        String title;

        @CommandLine.Option(names = "--description", paramLabel = "<text>",
                description = "The new description, in Markdown. Default: unchanged.")
        String description;

        @CommandLine.Option(names = "--description-file", paramLabel = "<path>",
                description = "Read the new description from this file (UTF-8), or from stdin for -. Not together "
                        + "with --description. Default: unchanged.")
        String descriptionFile;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.options.output);
            String wanted = RepositoriesCommand.required(epic != null ? epic : parent.epic, NAME_THE_EPIC);
            if ((title == null || title.isBlank()) && description == null && descriptionFile == null) {
                throw new CliFailure("Give --title, --description or --description-file: there is nothing to "
                        + "change.", CliFailure.USAGE);
            }
            if (title != null && title.isBlank()) {
                throw new CliFailure("--title must not be empty.", CliFailure.USAGE);
            }
            boolean descriptionGiven = description != null || descriptionFile != null;
            String givenDescription = descriptionGiven ? description(context) : null;
            Scope scope = parent.scope(context);
            String id = text(find(scope, wanted), "id");
            // The service REPLACES title and description on a PUT, so the current value of the field
            // left out has to be read and sent back, not just the one this command is asked to change.
            JsonNode one;
            try {
                one = scope.api().epic(id);
            } catch (CliFailure refused) {
                if (refused.status() == 404) {
                    throw new CliFailure("No such epic: " + id + " (HTTP 404). It may have been deleted a moment ago.",
                            CliFailure.FAILED);
                }
                throw refused;
            }
            JsonNode current = one.has("epic") ? one.get("epic") : one;
            String newTitle = title != null ? title.strip() : text(current, "title");
            String newDescription = descriptionGiven ? givenDescription : ProjectsApi.nullableText(current, "description");
            JsonNode answer = scope.api().updateEpic(id, newTitle, newDescription);
            if (json) {
                printJson(context.out(), answer);
                return 0;
            }
            printEpic(context.out(), answer.path("epic"), null);
            return 0;
        }

        /** The text of --description or --description-file, without trailing blanks; null for none. */
        private String description(CliContext context) throws CliFailure {
            if (description != null && descriptionFile != null) {
                throw new CliFailure("Give --description or --description-file, not both.", CliFailure.USAGE);
            }
            String text = description;
            if (descriptionFile != null) {
                text = descriptionFile.equals("-") ? stdin(context) : file(descriptionFile);
            }
            return text == null || text.isBlank() ? null : text.stripTrailing();
        }

        private static String stdin(CliContext context) throws CliFailure {
            try {
                return new String(context.in().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new CliFailure("Cannot read the description from stdin: " + e.getMessage(), CliFailure.USAGE);
            }
        }

        private static String file(String path) throws CliFailure {
            try {
                return Files.readString(Path.of(path), StandardCharsets.UTF_8);
            } catch (NoSuchFileException e) {
                throw new CliFailure("There is no file " + path + " (--description-file).", CliFailure.USAGE);
            } catch (CharacterCodingException e) {
                throw new CliFailure("The file " + path + " is not UTF-8 text (--description-file).", CliFailure.USAGE);
            } catch (IOException | InvalidPathException e) {
                throw new CliFailure("Cannot read " + path + " (--description-file): " + e.getMessage(), CliFailure.USAGE);
            }
        }
    }

    /** One feature of an epic, with the tasks under it. */
    private record FeatureNode(JsonNode feature, List<JsonNode> tasks) {
    }

    static void printEpics(PrintStream out, List<JsonNode> epics) {
        Table.print(out, "", List.of("ID", "SLUG", "STATUS", "TITLE", "UPDATED"), epics.stream()
                .map(e -> List.of(
                        cell(ReleaseRequestCommand.shortId(text(e, "id")), 8),
                        cell(text(e, "slug"), 40),
                        cell(text(e, "status"), 14),
                        cell(text(e, "title"), 60),
                        Table.time(text(e, "updatedAt"))))
                .toList());
    }

    /** One epic; its features and their tasks too, unless {@code features} is null (a just-filed epic has none). */
    static void printEpic(PrintStream out, JsonNode epic, List<FeatureNode> features) {
        out.println("Epic " + SafeText.line(text(epic, "id")));
        List<List<String>> rows = new ArrayList<>(List.of(
                row("slug", text(epic, "slug")),
                row("status", text(epic, "status")),
                row("title", text(epic, "title")),
                row("superseded by", text(epic, "supersededByEpicId")),
                List.of("created", Table.time(text(epic, "createdAt"))),
                List.of("updated", Table.time(text(epic, "updatedAt")))));
        List<String> workspaces = new ArrayList<>();
        epic.path("workspaces").forEach(w -> workspaces.add(text(w, "workspaceId") + " on " + text(w, "branch")));
        if (!workspaces.isEmpty()) {
            rows.add(row("workspaces", String.join(", ", workspaces)));
        }
        Table.print(out, "  ", null, rows);
        out.println("Description:");
        String description = text(epic, "description");
        if (description.isBlank()) {
            out.println("  (none)");
        } else {
            lines(out, "  ", description);
        }
        if (features == null) {
            return;
        }
        if (features.isEmpty()) {
            out.println("Features: none.");
            return;
        }
        out.println("Features (" + features.size() + "):");
        for (FeatureNode node : features) {
            JsonNode feature = node.feature();
            out.println("  " + cell(text(feature, "slug"), 40) + "  " + cell(text(feature, "title"), 60) + "  "
                    + implemented(text(feature, "implementedOn")));
            List<JsonNode> tasks = node.tasks();
            if (tasks.isEmpty()) {
                out.println("    Tasks: none.");
                continue;
            }
            out.println("    Tasks (" + tasks.size() + "):");
            for (JsonNode task : tasks) {
                out.println("      " + cell(text(task, "slug"), 40) + "  " + cell(text(task, "title"), 60) + "  "
                        + implemented(text(task, "implementedAt")));
            }
        }
    }

    private static String implemented(String value) {
        return value.isBlank() ? "not implemented" : "implemented " + Table.time(value);
    }

    /** The text a line at a time, each line cleaned on its own and indented. */
    private static void lines(PrintStream out, String indent, String text) {
        for (String line : text.split("\\R", -1)) {
            out.println((indent + SafeText.line(line)).stripTrailing());
        }
    }

    /** The answer, indented, with every control character written as an escape. */
    static void printJson(PrintStream out, JsonNode node) throws CliFailure {
        try {
            out.println(SafeText.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node));
        } catch (JsonProcessingException impossible) {
            throw new CliFailure("cannot print the answer as JSON", CliFailure.FAILED);
        }
    }

    private static List<String> row(String name, String value) {
        return List.of(name, cell(value, 600));
    }

    private static String cell(String value, int max) {
        return Table.cell(SafeText.line(value), max);
    }

    /** The service reads status names in capitals only. */
    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.strip().toUpperCase(Locale.ROOT);
    }
}
