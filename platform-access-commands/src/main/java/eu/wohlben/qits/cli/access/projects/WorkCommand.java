package eu.wohlben.qits.cli.access.projects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.complete.ProjectSource;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.tui.api.Completes;
import eu.wohlben.qits.cli.tui.api.Input;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

/**
 * {@code qits work}: any work entity — ticket, epic, feature, task or campaign — named once with
 * {@code --entity}, whatever its kind. One group for every archetype: the service serves the
 * archetype-agnostic doors and, per archetype and door, the payload's schema, so nothing here knows a
 * route per kind or a field of any kind.
 * <p>
 * Every write reads its payload, a JSON object, on stdin ({@link WorkPayload}); with none, it prints
 * its usage and the schema the service serves, and sends nothing.
 * <p>
 * A title, a description and a comment are written by people and agents, so every value a table
 * shows goes through {@link SafeText}, and the JSON form through {@link SafeJson}.
 */
@CommandLine.Command(name = "work", mixinStandardHelpOptions = true,
        subcommands = {WorkCommand.ListCommand.class, WorkCommand.DetailsCommand.class, WorkCommand.CreateCommand.class,
                WorkCommand.UpdateCommand.class, WorkCommand.TransitionCommand.class, WorkCommand.StatusCommand.class,
                WorkCommentCommand.class},
        description = {"Work items of every archetype - epics, tickets, features, tasks and campaigns - and their "
                        + "comment threads. list shows a project's items, details shows one with its comments and "
                        + "children, create files one, update edits its fields, transition reshapes it into "
                        + "another archetype, status moves it along its lifecycle, and comment writes to its thread.",
                "A write reads its payload, a JSON document, on stdin. With nothing on stdin (a terminal, or "
                        + "empty) it sends nothing and prints its usage and the payload's JSON schema instead, "
                        + "served by the service for that archetype.",
                "Statuses (epics, tickets and campaigns; features and tasks have none): REPORTED, REFINED, "
                        + "IMPLEMENTING, IMPLEMENTED, VERIFYING, VERIFIED, DONE, and DROPPED for work a decision "
                        + "was taken not to do. IMPLEMENTING sits between REFINED and IMPLEMENTED, and VERIFYING "
                        + "between IMPLEMENTED and VERIFIED, for epics and tickets; campaigns never enter either. "
                        + "A SKIP transition lets REFINED move straight to IMPLEMENTED, bypassing IMPLEMENTING, "
                        + "and IMPLEMENTED move straight to VERIFIED, bypassing VERIFYING. `status` names the "
                        + "moves open from where an item stands."},
        footerHeading = "%nNotes:%n",
        footer = {
                "- --entity, --output and --projects-url may come before or after the command.",
                "- --entity is the item's id or its qualified id (qits-100); the service resolves either. update "
                        + "and transition look the item up first and send its id.",
                "- Reading takes qits:admin or qits:agent; so does writing, except an epic's status move, which "
                        + "takes qits:admin. An agent writes only in its own project."})
public class WorkCommand implements Runnable {

    static final String NAME_THE_ENTITY = "Name the entity: --entity <id or qualified id, like qits-100>.";
    static final String NAME_THE_ARCHETYPE = "Name the archetype: --archetype <EPIC, TICKET, FEATURE, TASK or CAMPAIGN>.";

    @CommandLine.Mixin
    ProjectsOptions options;

    @CommandLine.Option(names = "--entity", paramLabel = "<entity>", scope = CommandLine.ScopeType.INHERIT,
            description = "The work entity: its id or its qualified id (qits-100), passed to the service as it is.")
    String entity;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    /** The entity as given, without surrounding blanks; a usage error without one. */
    String entity() throws CliFailure {
        return RepositoriesCommand.required(entity, NAME_THE_ENTITY);
    }

    ProjectsApi api(CliContext context) throws CliFailure, InterruptedException {
        return ProjectsApi.connect(context, options.projectsUrl);
    }

    /** The service reads archetype and status names in capitals. */
    static String upper(String value) {
        return value == null || value.isBlank() ? null : value.strip().toUpperCase(Locale.ROOT);
    }

    static String archetype(String given) throws CliFailure {
        return upper(RepositoriesCommand.required(given, NAME_THE_ARCHETYPE));
    }

    /**
     * The usage of the command, then the schema: what a person running a write with nothing piped in
     * needs. The JSON form is the bare schema, for a program.
     */
    static void usageAndSchema(CliContext context, CommandLine.Model.CommandSpec spec, boolean json, JsonNode schema,
            String from, List<String> notes) throws CliFailure {
        PrintStream out = context.out();
        if (!json) {
            spec.commandLine().usage(out, CommandLine.Help.Ansi.OFF);
            out.println();
        }
        WorkPayload.printSchema(out, json, schema, from, notes);
    }

    /** The schema one archetype's door takes, from the service. */
    static JsonNode schema(ProjectsApi api, String archetype, String door) throws CliFailure, InterruptedException {
        return WorkPayload.served(ProjectsApi.schemaPath(archetype, door), () -> api.archetypeSchema(archetype, door));
    }

    static String from(String archetype, String door) {
        return "From the service: GET " + ProjectsApi.schemaPath(archetype, door);
    }

    // --- list ---

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = {"A project's work items, every archetype, in the service's order: id (qualified), "
                    + "archetype, status, title and when it last changed. BLOCKED appears when a ticket is.",
                    "Each filter narrows the list; without one it shows everything."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits work list --project qits",
                    "  qits work list --project qits --archetype epic --status REFINED",
                    "  qits work list --project qits --parent qits-120 -o json",
                    "",
                    "- --archetype and --status are case-insensitive. --parent lists the children of one item."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The platform refused (for example an unknown archetype or status, HTTP 400, or a project it "
                            + "does not know, HTTP 404), or cannot be reached.",
                    "2:Used wrongly (for example no --project), not signed in, or the session ended."})
    public static class ListCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkCommand work;

        @Completes(ProjectSource.class)
        @CommandLine.Option(names = "--project", paramLabel = "<project>",
                description = "The project (required): its id or its slug.")
        String project;

        @CommandLine.Option(names = "--archetype", paramLabel = "<archetype>",
                description = "Only items of this archetype: EPIC, TICKET, FEATURE, TASK or CAMPAIGN.")
        String archetype;

        @CommandLine.Option(names = "--status", paramLabel = "<status>",
                description = "Only items in this status, such as REFINED.")
        String status;

        @CommandLine.Option(names = "--parent", paramLabel = "<entity>",
                description = "Only the children of this item: its id or its qualified id.")
        String parent;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(work.options.output);
            String wanted = RepositoriesCommand.required(project, RepositoriesCommand.NAME_THE_PROJECT);
            ProjectsApi api = work.api(context);
            String parentId = parent == null || parent.isBlank() ? null : WorkEntities.uuid(api, parent.strip());
            JsonNode answer = api.entities(wanted, upper(archetype), upper(status), parentId);
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            List<JsonNode> entities = WorkEntities.list(answer);
            if (entities.isEmpty()) {
                context.out().println("No work items match.");
                return 0;
            }
            WorkEntities.printRows(context.out(), "", entities);
            return 0;
        }
    }

    // --- details ---

    @CommandLine.Command(name = "details", mixinStandardHelpOptions = true,
            description = {"One work item: its fields, its description, its comment thread and its children."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits work --entity qits-100 details",
                    "  qits work details --entity 45a14f8e-f550-45bb-a117-6b34d8c472e3 -o json",
                    "",
                    "- -o json prints one object: the item as the service answers it, its comments and its "
                            + "children. Control characters are written as escapes."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The platform refused (for example an item it does not know, HTTP 404), or cannot be reached.",
                    "2:Used wrongly (for example no --entity), not signed in, or the session ended."})
    public static class DetailsCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkCommand work;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(work.options.output);
            String wanted = work.entity();
            ProjectsApi api = work.api(context);
            JsonNode entity = api.entity(wanted);
            String id = text(entity, "id");
            List<JsonNode> comments = ProjectsApi.entries(api.entityComments(id), "comment");
            List<JsonNode> children = WorkEntities.list(api.entities(text(entity, "projectId"), null, null, id));
            if (json) {
                ObjectNode all = JsonNodeFactory.instance.objectNode();
                all.set("entity", entity);
                ArrayNode thread = all.putArray("comments");
                comments.forEach(thread::add);
                ArrayNode kids = all.putArray("children");
                children.forEach(kids::add);
                SafeJson.print(context.out(), all);
                return 0;
            }
            WorkEntities.printDetails(context.out(), entity, comments, children);
            return 0;
        }
    }

    // --- create ---

    @CommandLine.Command(name = "create", mixinStandardHelpOptions = true,
            description = {"File a new work item of an archetype.",
                    "Reads the payload, a JSON object, on stdin, sets its \"archetype\" from --archetype, and sends "
                            + "it to POST /projects/api/entities. A root item names its \"project\" (id or slug), a "
                            + "child its \"parent\" (id or qualified id). The item starts REPORTED if its archetype "
                            + "has a lifecycle. The command prints the new item, its qualified id first.",
                    "With nothing on stdin (a terminal, or empty) it sends nothing, prints this usage and the "
                            + "payload's JSON schema, served by the service at "
                            + "GET /projects/api/entities/archetypes/{archetype}/schemas/create, and exits with 0."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits work create --archetype ticket </dev/null",
                    "  echo '{\"project\":\"qits\",\"title\":\"The log view stops at 64 KiB\",\"ticketType\":\"BUG\","
                            + "\"impetus\":\"A long run's log is cut.\"}' | qits work create --archetype ticket",
                    "  jq -n --rawfile d plan.md '{parent:\"qits-120\",title:\"Retry\",description:$d}' "
                            + "| qits work create --archetype feature -o json",
                    "",
                    "- The first one prints the schema: nothing is sent."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The item is filed, or nothing was put in and the schema is printed.",
                    "1:The platform refused (for example a payload it does not take, HTTP 400, your roles or "
                            + "another project, HTTP 403, or an archetype it does not know, HTTP 404), or cannot be "
                            + "reached.",
                    "2:Used wrongly (for example no --archetype, or a payload that is not a JSON object), not signed "
                            + "in, or the session ended."})
    @TuiCommand(input = Input.PAYLOAD)
    public static class CreateCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkCommand work;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = "--archetype", paramLabel = "<archetype>",
                description = "The new item's archetype (required): EPIC, TICKET, FEATURE, TASK or CAMPAIGN. "
                        + "Case-insensitive.")
        String archetype;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(work.options.output);
            ObjectNode payload = WorkPayload.read(context);
            String kind = archetype(archetype);
            ProjectsApi api = work.api(context);
            if (payload == null) {
                usageAndSchema(context, spec, json, schema(api, kind, "create"), from(kind, "create"), List.of());
                return 0;
            }
            JsonNode stated = payload.get("archetype");
            if (stated != null && !stated.isNull() && !kind.equals(upper(stated.asText()))) {
                throw new CliFailure("The payload says \"archetype\": " + SafeText.line(stated.toString())
                        + ", and --archetype says " + kind + ". Nothing was sent.", CliFailure.USAGE);
            }
            ObjectNode body = payload.deepCopy();
            body.put("archetype", kind);
            JsonNode answer = api.createEntity(body);
            WorkEntities.printAnswer(context.out(), json, answer);
            return 0;
        }
    }

    // --- update ---

    @CommandLine.Command(name = "update", mixinStandardHelpOptions = true,
            description = {"Edit a work item's fields.",
                    "Looks the item up (for its id and archetype), then reads a JSON merge patch on stdin and sends "
                            + "it unchanged to PATCH /projects/api/entities/{id} as application/merge-patch+json: a "
                            + "property left out stays as it is, null clears it. Status and archetype are not "
                            + "edited here; see status and transition.",
                    "With nothing on stdin (a terminal, or empty) it sends nothing, prints this usage and the "
                            + "patch's JSON schema for the item's archetype, served by the service at "
                            + "GET /projects/api/entities/archetypes/{archetype}/schemas/update, and exits with 0."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits work --entity qits-100 update </dev/null",
                    "  echo '{\"title\":\"The log view stops at 64 KiB\"}' | qits work --entity qits-100 update",
                    "  echo '{\"assignee\":null}' | qits work update --entity qits-100 -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The item is edited, or nothing was put in and the schema is printed.",
                    "1:The platform refused (for example a patch it does not take, HTTP 400, your roles or another "
                            + "project, HTTP 403, or an item it does not know, HTTP 404), or cannot be reached.",
                    "2:Used wrongly (for example no --entity, or a patch that is not a JSON object), not signed in, "
                            + "or the session ended."})
    @TuiCommand(input = Input.PAYLOAD)
    public static class UpdateCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkCommand work;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(work.options.output);
            ObjectNode patch = WorkPayload.read(context);
            String wanted = work.entity();
            ProjectsApi api = work.api(context);
            JsonNode entity = api.entity(wanted);
            if (patch == null) {
                String kind = text(entity, "archetype");
                usageAndSchema(context, spec, json, schema(api, kind, "update"), from(kind, "update"), List.of());
                return 0;
            }
            JsonNode answer = api.patchEntity(text(entity, "id"), patch);
            WorkEntities.printAnswer(context.out(), json, answer);
            return 0;
        }
    }

    // --- transition ---

    @CommandLine.Command(name = "transition", mixinStandardHelpOptions = true,
            description = {"Reshape a work item into another archetype (a ticket into an epic, a feature into a "
                    + "task, ...), keeping its id, its number and its thread.",
                    "The door is full-state: what the request leaves out is cleared. So the command starts from the "
                            + "item as it stands (title, description, status, ticketType, impetus, assignee, "
                            + "supersededBy, repositoryId, implementedAt, dependsOn, and membership {parent, "
                            + "position} if it has a parent), merges the JSON object on stdin over it as a merge "
                            + "patch (null clears), sets \"archetype\", and drops every property the target "
                            + "archetype's schema has no slot for, naming them on stderr. It sends the result to "
                            + "POST /projects/api/entities/transition.",
                    "With nothing on stdin (a terminal, or empty) it sends nothing, prints this usage and the target's "
                            + "JSON schema, served at GET /projects/api/entities/archetypes/{archetype}/schemas/"
                            + "transition, names the required properties the item does not carry yet and the ones "
                            + "that would be dropped, and exits with 0."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits work --entity qits-100 transition --archetype epic </dev/null",
                    "  echo '{}' | qits work --entity qits-100 transition --archetype epic",
                    "  echo '{\"membership\":{\"parent\":\"6f0c2d1e-0000-4000-8000-000000000001\"}}' "
                            + "| qits work --entity qits-100 transition --archetype feature",
                    "",
                    "- {} carries the item over as it stands. membership.parent is the parent's id, not its "
                            + "qualified id."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The item is reshaped, or nothing was put in and the schema is printed.",
                    "1:The platform refused (for example a state it does not take, HTTP 400, your roles or another "
                            + "project, HTTP 403, an item or archetype it does not know, HTTP 404, or a conflict, "
                            + "HTTP 409), or cannot be reached.",
                    "2:Used wrongly (for example no --entity or --archetype, or a payload that is not a JSON object), "
                            + "not signed in, or the session ended."})
    @TuiCommand(input = Input.PAYLOAD)
    public static class TransitionCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkCommand work;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(names = "--archetype", paramLabel = "<archetype>",
                description = "The archetype to turn the item into (required): EPIC, TICKET, FEATURE, TASK or "
                        + "CAMPAIGN. Case-insensitive.")
        String archetype;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(work.options.output);
            ObjectNode payload = WorkPayload.read(context);
            String wanted = work.entity();
            String target = archetype(archetype);
            ProjectsApi api = work.api(context);
            JsonNode entity = api.entity(wanted);
            JsonNode schema = schema(api, target, "transition");
            ObjectNode current = WorkEntities.currentState(entity);
            if (payload == null) {
                List<String> notes = new ArrayList<>();
                List<String> missing = WorkEntities.missing(schema, current);
                notes.add("Required and missing (the item does not carry them yet): "
                        + (missing.isEmpty() ? "nothing" : String.join(", ", missing)) + ".");
                List<String> dropped = WorkEntities.unslotted(schema, current);
                if (!dropped.isEmpty()) {
                    notes.add("Not carried: " + String.join(", ", dropped) + " "
                            + (dropped.size() == 1 ? "has" : "have") + " no slot on " + target + ".");
                }
                usageAndSchema(context, spec, json, schema, from(target, "transition"), notes);
                return 0;
            }
            ObjectNode state = (ObjectNode) WorkEntities.mergePatch(current, payload);
            state.remove("archetype");
            List<String> dropped = WorkEntities.unslotted(schema, state);
            if (!dropped.isEmpty()) {
                context.err().println(String.join(", ", dropped) + " " + (dropped.size() == 1 ? "has" : "have")
                        + " no slot on " + target + " and " + (dropped.size() == 1 ? "is" : "are") + " not carried.");
                dropped.forEach(state::remove);
            }
            state.put("archetype", target);
            String id = text(entity, "id");
            ObjectNode body = JsonNodeFactory.instance.objectNode();
            body.set(id, state);
            JsonNode answer = api.transitionEntities(body);
            WorkEntities.printAnswer(context.out(), json, json ? answer : answer.path(id));
            return 0;
        }
    }

    // --- status ---

    @CommandLine.Command(name = "status", mixinStandardHelpOptions = true,
            description = {"Move a work item along its lifecycle: REPORTED, REFINED, IMPLEMENTING, IMPLEMENTED, "
                    + "VERIFYING, VERIFIED, DONE, or DROPPED. Features and tasks have no lifecycle, and so no "
                    + "status. IMPLEMENTING sits between REFINED and IMPLEMENTED, and VERIFYING between "
                    + "IMPLEMENTED and VERIFIED, for epics and tickets; campaigns never enter either. A SKIP "
                    + "transition lets REFINED move straight to IMPLEMENTED, bypassing IMPLEMENTING, and "
                    + "IMPLEMENTED move straight to VERIFIED, bypassing VERIFYING.",
                    "Reads {\"target\":\"<STATUS>\"} on stdin and sends it unchanged to POST "
                            + "/projects/api/entities/{id}/status. The service refuses a move its lifecycle does not "
                            + "allow (HTTP 409).",
                    "With nothing on stdin (a terminal, or empty) it sends nothing, prints this usage and the "
                            + "payload's schema, whose target enum is the moves open from the item's current status "
                            + "as the service's archetype registry (GET /projects/api/entities/archetypes) states "
                            + "them, and exits with 0."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits work --entity qits-100 status </dev/null",
                    "  echo '{\"target\":\"REFINED\"}' | qits work --entity qits-100 status",
                    "",
                    "- An epic's status move takes qits:admin; an agent is answered HTTP 403."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The item moved, or nothing was put in and the legal targets are printed.",
                    "1:The platform refused (for example a move the lifecycle does not allow, HTTP 409, your roles "
                            + "or another project, HTTP 403, or an item it does not know, HTTP 404), or cannot be "
                            + "reached.",
                    "2:Used wrongly (for example no --entity, an item whose archetype has no lifecycle, or a payload "
                            + "that is not a JSON object), not signed in, or the session ended."})
    @TuiCommand(input = Input.PAYLOAD)
    public static class StatusCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkCommand work;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(work.options.output);
            ObjectNode payload = WorkPayload.read(context);
            String wanted = work.entity();
            ProjectsApi api = work.api(context);
            JsonNode entity = api.entity(wanted);
            String kind = text(entity, "archetype");
            JsonNode declared = WorkEntities.declared(api.archetypes(), kind);
            if (declared == null || !declared.path("lifecycle").isArray() || declared.path("lifecycle").isEmpty()) {
                throw new CliFailure(SafeText.line(text(entity, "qualifiedId").isEmpty() ? wanted
                        : text(entity, "qualifiedId")) + " is a " + SafeText.line(kind)
                        + ", and that archetype has no lifecycle, so no status. Nothing was sent.", CliFailure.USAGE);
            }
            if (payload == null) {
                String current = text(entity, "status");
                ArrayNode targets = JsonNodeFactory.instance.arrayNode();
                for (JsonNode move : declared.path("transitions").path(current)) {
                    targets.add(move.isTextual() ? move.asText() : text(move, "to"));
                }
                ObjectNode schema = JsonNodeFactory.instance.objectNode();
                schema.put("title", kind + " status move from " + current);
                schema.put("type", "object");
                schema.putObject("properties").putObject("target").set("enum", targets);
                schema.putArray("required").add("target");
                schema.put("additionalProperties", false);
                usageAndSchema(context, spec, json, schema,
                        "From the service: GET " + ProjectsApi.ARCHETYPES + ", the moves of " + SafeText.line(kind)
                                + " from " + SafeText.line(current) + ".",
                        List.of("Current status: " + SafeText.line(current) + ". Open moves: "
                                + (targets.isEmpty() ? "none" : String.join(", ",
                                        WorkEntities.texts(targets))) + "."));
                return 0;
            }
            JsonNode answer = api.moveStatus(text(entity, "id"), payload);
            WorkEntities.printAnswer(context.out(), json, answer);
            return 0;
        }
    }
}
