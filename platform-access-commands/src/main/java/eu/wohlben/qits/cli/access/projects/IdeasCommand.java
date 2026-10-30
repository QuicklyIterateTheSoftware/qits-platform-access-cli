package eu.wohlben.qits.cli.access.projects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.complete.ProjectSource;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.Table;
import eu.wohlben.qits.cli.tui.api.Completes;
import eu.wohlben.qits.cli.tui.api.Effect;
import eu.wohlben.qits.cli.tui.api.Input;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

/**
 * {@code qits ideas}: a project's ideas (epic qits-888), the short notes an epic, a ticket or a
 * campaign names as its {@code inspiredBy}. An idea is not a work archetype: no lifecycle a caller
 * moves, no thread, no children, no delete. Its status is derived by the service from the work
 * naming it, and an idea is archived with a reason instead of deleted.
 * <p>
 * The writes read their payload on stdin as {@code qits work} does ({@link WorkPayload}), and with
 * none print the payload's schema from the service's OpenAPI document. {@code spawn} files a work
 * item through the same door {@code qits work create} sends to, pre-filled from the idea.
 * <p>
 * Titles and descriptions are written by people and agents, so every value a table shows goes
 * through {@link SafeText}, and the JSON form through {@link SafeJson}.
 */
@CommandLine.Command(name = "ideas", mixinStandardHelpOptions = true,
        subcommands = {IdeasCommand.ListCommand.class, IdeasCommand.DetailsCommand.class,
                IdeasCommand.CreateCommand.class, IdeasCommand.UpdateCommand.class, IdeasCommand.ArchiveCommand.class,
                IdeasCommand.UnarchiveCommand.class, IdeasCommand.SpawnCommand.class},
        description = {"A project's ideas: short, titled notes that an epic, a ticket or a campaign names as its "
                        + "inspiration (\"inspiredBy\"). list shows a project's ideas, details shows one with the "
                        + "work it inspired and the ideas it enables, create writes one, update edits it, archive "
                        + "and unarchive hide it from the list and bring it back, and spawn files an epic or a "
                        + "ticket from it.",
                "An idea is not a work item: it has no lifecycle you move, no comment thread, no children and no "
                        + "delete. Its status is derived from the work naming it: PLANNED (no counted work names "
                        + "it), QUEUED (some does, not all DONE) or DONE (every counted item DONE); DROPPED work is "
                        + "not counted. One idea may name another of its project as \"enabledBy\".",
                "A write reads its payload, a JSON document, on stdin. With nothing on stdin (a terminal, or "
                        + "empty) it sends nothing and prints its usage and the payload's JSON schema instead, from "
                        + "the service's OpenAPI document."},
        footerHeading = "%nNotes:%n",
        footer = {
                "- --entity, --output and --projects-url may come before or after the command.",
                "- --entity is the idea's id, as list and create print it.",
                "- Reading takes qits:admin or qits:agent; so does writing. An agent writes only in its own project."})
public class IdeasCommand implements Runnable {

    static final String NAME_THE_IDEA = "Name the idea: --entity <id>.";

    /** The archetypes spawn files: the work kinds an idea inspires, less a campaign's own set-up. */
    static final List<String> SPAWNS = List.of("EPIC", "TICKET");

    @CommandLine.Mixin
    ProjectsOptions options;

    @CommandLine.Option(names = "--entity", paramLabel = "<idea>", scope = CommandLine.ScopeType.INHERIT,
            description = "The idea: its id, passed to the service as it is.")
    String entity;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    String entity() throws CliFailure {
        return RepositoriesCommand.required(entity, NAME_THE_IDEA);
    }

    ProjectsApi api(CliContext context) throws CliFailure, InterruptedException {
        return ProjectsApi.connect(context, options.projectsUrl);
    }

    // --- list ---

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = {"A project's ideas, oldest first: id, status, whether archived, title and when it last "
                    + "changed. The archived ones are left out unless --archived is given, which adds them beside "
                    + "the rest."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits ideas list --project qits",
                    "  qits ideas list --project qits --archived --status planned -o json",
                    "",
                    "- --status is PLANNED, QUEUED or DONE, case-insensitive."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The platform refused (for example a status it does not know, HTTP 400, or a project it does "
                            + "not know, HTTP 404), or cannot be reached.",
                    "2:Used wrongly (for example no --project), not signed in, or the session ended."})
    @TuiCommand(effect = Effect.READ)
    public static class ListCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        IdeasCommand ideas;

        @Completes(ProjectSource.class)
        @CommandLine.Option(names = "--project", paramLabel = "<project>",
                description = "The project (required): its id or its slug.")
        String project;

        @CommandLine.Option(names = "--archived",
                description = "Show the archived ideas too, beside the others.")
        boolean archived;

        @CommandLine.Option(names = "--status", paramLabel = "<status>",
                description = "Only ideas at this status: PLANNED, QUEUED or DONE.")
        String status;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(ideas.options.output);
            String wanted = RepositoriesCommand.required(project, RepositoriesCommand.NAME_THE_PROJECT);
            JsonNode answer = ideas.api(context).ideas(wanted, archived, WorkCommand.upper(status));
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            List<JsonNode> all = objects(answer.path("ideas"));
            if (all.isEmpty()) {
                context.out().println("No ideas match.");
                return 0;
            }
            printRows(context.out(), "", all);
            return 0;
        }
    }

    // --- details ---

    @CommandLine.Command(name = "details", mixinStandardHelpOptions = true,
            description = {"One idea: its title, status, whether it is archived and why, the idea enabling it, its "
                    + "description, the ideas it enables, and the work it inspired (every work item naming it as "
                    + "inspiredBy, DROPPED ones included, with their status). When the idea enabling it is "
                    + "archived, a hint line says so."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits ideas details --entity 45a14f8e-f550-45bb-a117-6b34d8c472e3",
                    "  qits ideas --entity 45a14f8e-f550-45bb-a117-6b34d8c472e3 details -o json",
                    "",
                    "- -o json prints the service's answer: the idea with inspired, enables and enabledByIdea. "
                            + "Control characters are written as escapes."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The platform refused (for example an idea it does not know, HTTP 404), or cannot be reached.",
                    "2:Used wrongly (for example no --entity), not signed in, or the session ended."})
    @TuiCommand(effect = Effect.READ)
    public static class DetailsCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        IdeasCommand ideas;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(ideas.options.output);
            JsonNode idea = ideas.api(context).idea(ideas.entity());
            if (json) {
                SafeJson.print(context.out(), idea);
                return 0;
            }
            printDetails(context.out(), idea);
            return 0;
        }
    }

    // --- create ---

    @CommandLine.Command(name = "create", mixinStandardHelpOptions = true,
            description = {"Write a new idea of a project.",
                    "Reads the payload, a JSON object such as {\"project\":\"qits\",\"title\":\"...\"}, on stdin "
                            + "and sends it unchanged to POST /projects/api/ideas. \"project\" is the project's id or "
                            + "slug. The title is one line (no line break, at most one '.', fewer than 20 whitespace "
                            + "characters); the description at most 3 paragraphs and 600 characters; \"enabledBy\" "
                            + "names one idea of the same project that is not itself enabled by another. The idea "
                            + "starts PLANNED. The command prints it, its id first.",
                    "With nothing on stdin (a terminal, or empty) it sends nothing, prints this usage and the "
                            + "payload's JSON schema from the service's OpenAPI document (/projects/q/openapi), and "
                            + "exits with 0."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits ideas create </dev/null",
                    "  echo '{\"project\":\"qits\",\"title\":\"Faster builds\"}' | qits ideas create",
                    "  jq -n --rawfile d idea.md '{project:\"qits\",title:\"Share the build cache\",description:$d}' "
                            + "| qits ideas create -o json",
                    "",
                    "- The first one prints the schema: nothing is sent."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The idea is written, or nothing was put in and the schema is printed.",
                    "1:The platform refused (for example a title, description or enabledBy breaking its rule, HTTP "
                            + "400, another project, HTTP 403, or a project it does not know, HTTP 404), or cannot "
                            + "be reached.",
                    "2:Used wrongly (for example a payload that is not a JSON object), not signed in, or the session "
                            + "ended."})
    @TuiCommand(input = Input.PAYLOAD)
    public static class CreateCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        IdeasCommand ideas;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(ideas.options.output);
            ObjectNode payload = WorkPayload.read(context);
            ProjectsApi api = ideas.api(context);
            if (payload == null) {
                WorkPayload.printSchema(context, api, json, "POST", ProjectsApi.IDEAS, List.of("application/json"));
                return 0;
            }
            printAnswer(context.out(), json, api.createIdea(payload));
            return 0;
        }
    }

    // --- update ---

    @CommandLine.Command(name = "update", mixinStandardHelpOptions = true,
            description = {"Edit an idea's title, description or enabledBy.",
                    "Reads a JSON merge patch on stdin and sends it unchanged to PATCH /projects/api/ideas/{idea} as "
                            + "application/merge-patch+json: a property left out stays as it is, null clears "
                            + "description or enabledBy. The rules are the create's. The status is derived and the "
                            + "archive has its own commands.",
                    "With nothing on stdin (a terminal, or empty) it sends nothing, prints this usage and the patch's "
                            + "JSON schema from the service's OpenAPI document (/projects/q/openapi), and exits with "
                            + "0."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits ideas update </dev/null",
                    "  echo '{\"title\":\"Share the build cache across runners\"}' "
                            + "| qits ideas --entity 45a14f8e-f550-45bb-a117-6b34d8c472e3 update",
                    "  echo '{\"enabledBy\":null}' | qits ideas update --entity 45a14f8e-f550-45bb-a117-6b34d8c472e3"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The idea is edited, or nothing was put in and the schema is printed.",
                    "1:The platform refused (for example a patch it does not take, HTTP 400, another project, HTTP "
                            + "403, or an idea it does not know, HTTP 404), or cannot be reached.",
                    "2:Used wrongly (for example no --entity, or a patch that is not a JSON object), not signed in, "
                            + "or the session ended."})
    @TuiCommand(input = Input.PAYLOAD)
    public static class UpdateCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        IdeasCommand ideas;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(ideas.options.output);
            ObjectNode patch = WorkPayload.read(context);
            if (patch == null) {
                WorkPayload.printSchema(context, ideas.api(context), json, "PATCH", ProjectsApi.IDEAS + "/{id}",
                        List.of(ProjectsApi.MERGE_PATCH, "application/json"));
                return 0;
            }
            String wanted = ideas.entity();
            printAnswer(context.out(), json, ideas.api(context).patchIdea(wanted, patch));
            return 0;
        }
    }

    // --- archive and unarchive ---

    @CommandLine.Command(name = "archive", mixinStandardHelpOptions = true,
            description = {"Archive an idea: hide it from the list, and say why. Ideas are never deleted.",
                    "Sends {\"reason\":\"...\"} to POST /projects/api/ideas/{idea}/archive. Allowed at every status, "
                            + "and the status does not change. Archiving an archived idea states the reason again."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits ideas --entity 45a14f8e-f550-45bb-a117-6b34d8c472e3 archive "
                    + "--reason 'Folded into the cache epic.'"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The idea is archived.",
                    "1:The platform refused (for example another project, HTTP 403, or an idea it does not know, HTTP "
                            + "404), or cannot be reached.",
                    "2:Used wrongly (for example no --entity or no --reason), not signed in, or the session ended."})
    public static class ArchiveCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        IdeasCommand ideas;

        @CommandLine.Option(names = "--reason", paramLabel = "<text>",
                description = "Why the idea is archived (required).")
        String reason;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(ideas.options.output);
            String wanted = ideas.entity();
            String why = RepositoriesCommand.required(reason, "Say why the idea is archived: --reason <text>.");
            printAnswer(context.out(), json, ideas.api(context).archiveIdea(wanted, why));
            return 0;
        }
    }

    @CommandLine.Command(name = "unarchive", mixinStandardHelpOptions = true,
            description = {"Take an idea out of the archive, so the list shows it again.",
                    "Sends POST /projects/api/ideas/{idea}/unarchive, which clears the archive and its reason. An "
                            + "idea that is not archived is answered as it is."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits ideas --entity 45a14f8e-f550-45bb-a117-6b34d8c472e3 unarchive"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The idea is not archived.",
                    "1:The platform refused (for example another project, HTTP 403, or an idea it does not know, HTTP "
                            + "404), or cannot be reached.",
                    "2:Used wrongly (for example no --entity), not signed in, or the session ended."})
    public static class UnarchiveCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        IdeasCommand ideas;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(ideas.options.output);
            String wanted = ideas.entity();
            printAnswer(context.out(), json, ideas.api(context).unarchiveIdea(wanted));
            return 0;
        }
    }

    // --- spawn ---

    @CommandLine.Command(name = "spawn", mixinStandardHelpOptions = true,
            description = {"File an epic or a ticket inspired by an idea.",
                    "Reads the idea, and starts the new item from it: \"project\" its project, \"title\" its title, "
                            + "\"description\" its description when it has one, and \"inspiredBy\" its id. The JSON "
                            + "object on stdin is merged over that as a merge patch (null removes a property), "
                            + "\"archetype\" is set from --archetype, and the result goes to POST /projects/api/work, "
                            + "the door `qits work create` sends to. The command prints the new item, its qualified "
                            + "id first. A ticket also needs its \"ticketType\" and \"impetus\" on stdin.",
                    "With nothing on stdin (a terminal, or empty) it sends nothing, prints this usage, what the idea "
                            + "fills in, the required properties still missing, and the archetype's create schema "
                            + "served at GET /projects/api/work/archetypes/{archetype}/schemas/create, and exits "
                            + "with 0. Pipe in {} to file the item as the idea fills it."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits ideas --entity 45a14f8e-f550-45bb-a117-6b34d8c472e3 spawn --archetype epic </dev/null",
                    "  echo '{}' | qits ideas --entity 45a14f8e-f550-45bb-a117-6b34d8c472e3 spawn --archetype epic",
                    "  echo '{\"ticketType\":\"IMPROVEMENT\",\"impetus\":\"Builds wait on downloads.\"}' "
                            + "| qits ideas spawn --entity 45a14f8e-f550-45bb-a117-6b34d8c472e3 --archetype ticket",
                    "",
                    "- The first one prints the schema: nothing is sent."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The item is filed, or nothing was put in and the schema is printed.",
                    "1:The platform refused (for example an idea it does not know, HTTP 404, a payload it does not "
                            + "take, HTTP 400, or another project, HTTP 403), or cannot be reached.",
                    "2:Used wrongly (for example no --entity, an --archetype that is neither EPIC nor TICKET, or a "
                            + "payload that is not a JSON object), not signed in, or the session ended."})
    @TuiCommand(input = Input.PAYLOAD)
    public static class SpawnCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        IdeasCommand ideas;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = "--archetype", paramLabel = "<archetype>",
                description = "The new item's archetype (required): EPIC or TICKET. Case-insensitive.")
        String archetype;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(ideas.options.output);
            ObjectNode payload = WorkPayload.read(context);
            String wanted = ideas.entity();
            String kind = WorkCommand.upper(RepositoriesCommand.required(archetype,
                    "Name the archetype: --archetype <EPIC or TICKET>."));
            if (!SPAWNS.contains(kind)) {
                throw new CliFailure("--archetype is EPIC or TICKET, not " + SafeText.line(archetype.strip())
                        + ". Nothing was sent.", CliFailure.USAGE);
            }
            JsonNode stated = payload == null ? null : payload.get("archetype");
            if (stated != null && !stated.isNull() && !kind.equals(WorkCommand.upper(stated.asText()))) {
                throw new CliFailure("The payload says \"archetype\": " + SafeText.line(stated.toString())
                        + ", and --archetype says " + kind + ". Nothing was sent.", CliFailure.USAGE);
            }
            ProjectsApi api = ideas.api(context);
            ObjectNode prefill = prefill(api.idea(wanted));
            if (payload == null) {
                JsonNode schema = WorkCommand.schema(api, kind, "create");
                List<String> notes = new ArrayList<>();
                List<String> filled = new ArrayList<>();
                prefill.fieldNames().forEachRemaining(filled::add);
                notes.add("Filled in from the idea: " + String.join(", ", filled) + ".");
                List<String> missing = WorkEntities.missing(schema, prefill);
                notes.add("Required and missing (put them on stdin): "
                        + (missing.isEmpty() ? "nothing" : String.join(", ", missing)) + ".");
                WorkCommand.usageAndSchema(context, spec, json, schema, WorkCommand.from(kind, "create"), notes);
                return 0;
            }
            ObjectNode body = (ObjectNode) WorkEntities.mergePatch(prefill, payload);
            body.put("archetype", kind);
            WorkEntities.printAnswer(context.out(), json, api.createWork(body));
            return 0;
        }

        /** What the idea gives the new item: its project, title, description (when it has one) and its id. */
        static ObjectNode prefill(JsonNode idea) {
            ObjectNode prefill = JsonNodeFactory.instance.objectNode();
            prefill.put("project", text(idea, "projectId"));
            prefill.put("title", text(idea, "title"));
            String description = text(idea, "description");
            if (!description.isBlank()) {
                prefill.put("description", description);
            }
            prefill.put("inspiredBy", text(idea, "id"));
            return prefill;
        }
    }

    // --- the tables ---

    /** A write's answer: as JSON, or the one row the idea has in a list. */
    static void printAnswer(PrintStream out, boolean json, JsonNode answer) throws CliFailure {
        if (json) {
            SafeJson.print(out, answer);
            return;
        }
        printRows(out, "", List.of(answer));
    }

    /** ID, STATUS, ARCHIVED, TITLE, UPDATED. */
    static void printRows(PrintStream out, String indent, List<JsonNode> ideas) {
        Table.print(out, indent, List.of("ID", "STATUS", "ARCHIVED", "TITLE", "UPDATED"), ideas.stream()
                .map(i -> List.of(WorkEntities.cell(text(i, "id"), 40), WorkEntities.cell(text(i, "status"), 8),
                        archivedCell(i), WorkEntities.cell(text(i, "title"), 70), Table.time(text(i, "updatedAt"))))
                .toList());
    }

    private static String archivedCell(JsonNode idea) {
        return idea.path("archived").asBoolean(false) ? "yes" : "-";
    }

    /** One idea in full: its fields, a hint when its enabler is archived, its description, what it enables and inspired. */
    static void printDetails(PrintStream out, JsonNode idea) {
        out.println("IDEA " + SafeText.line(text(idea, "id")));
        List<List<String>> rows = new ArrayList<>();
        rows.add(row("title", text(idea, "title")));
        rows.add(row("status", text(idea, "status")));
        rows.add(row("archived", archived(idea)));
        JsonNode enabler = idea.path("enabledByIdea");
        String enabledBy = text(idea, "enabledBy");
        if (!enabledBy.isEmpty()) {
            String title = text(enabler, "title");
            rows.add(row("enabled by", title.isEmpty() ? enabledBy : enabledBy + " (" + title + ")"));
        }
        rows.add(row("project", text(idea, "projectId")));
        rows.add(List.of("created", Table.time(text(idea, "createdAt"))));
        rows.add(List.of("updated", Table.time(text(idea, "updatedAt"))));
        Table.print(out, "  ", null, rows);
        if (!enabledBy.isEmpty() && enabler.path("archived").asBoolean(false)) {
            out.println("hint: enabled by an archived idea " + SafeText.line(enabledBy)
                    + "; unarchive it or clear enabledBy.");
        }
        out.println("Description:");
        String description = text(idea, "description");
        if (description.isBlank()) {
            out.println("  (none)");
        } else {
            WorkEntities.lines(out, "  ", description);
        }
        List<JsonNode> enables = objects(idea.path("enables"));
        if (enables.isEmpty()) {
            out.println("Enables: none.");
        } else {
            out.println("Enables (" + enables.size() + "), the ideas naming this one as their enabledBy:");
            Table.print(out, "  ", List.of("ID", "STATUS", "ARCHIVED", "TITLE"), enables.stream()
                    .map(i -> List.of(WorkEntities.cell(text(i, "id"), 40), WorkEntities.cell(text(i, "status"), 8),
                            archivedCell(i), WorkEntities.cell(text(i, "title"), 70)))
                    .toList());
        }
        List<JsonNode> inspired = objects(idea.path("inspired"));
        if (inspired.isEmpty()) {
            out.println("Inspired: none.");
        } else {
            out.println("Inspired (" + inspired.size() + "), the work naming this idea as its inspiredBy:");
            Table.print(out, "  ", List.of("ID", "ARCHETYPE", "STATUS", "TITLE"), inspired.stream()
                    .map(w -> List.of(WorkEntities.cell(label(w), 40), WorkEntities.cell(text(w, "archetype"), 9),
                            WorkEntities.cell(text(w, "status"), 11), WorkEntities.cell(text(w, "title"), 70)))
                    .toList());
        }
    }

    /** no, or yes with the reason and when, whichever the service sent. */
    private static String archived(JsonNode idea) {
        if (!idea.path("archived").asBoolean(false)) {
            return "no";
        }
        StringBuilder archived = new StringBuilder("yes");
        String reason = text(idea, "archiveReason");
        if (!reason.isEmpty()) {
            archived.append(": ").append(reason);
        }
        String at = text(idea, "archivedAt");
        if (!at.isEmpty()) {
            archived.append(" (").append(Table.time(at)).append(")");
        }
        return archived.toString();
    }

    /** The qualified id, which is what a person types; the id when the service sent none. */
    private static String label(JsonNode work) {
        String qualified = text(work, "qualifiedId");
        return qualified.isEmpty() ? text(work, "id") : qualified;
    }

    private static List<String> row(String name, String value) {
        return List.of(name, WorkEntities.cell(value, 600));
    }

    private static List<JsonNode> objects(JsonNode array) {
        List<JsonNode> all = new ArrayList<>();
        array.forEach(e -> {
            if (e.isObject()) {
                all.add(e);
            }
        });
        return all;
    }
}
