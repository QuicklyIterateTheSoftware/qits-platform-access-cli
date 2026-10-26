package eu.wohlben.qits.cli.access.projects;

import eu.wohlben.qits.cli.tui.api.Effect;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.Table;
import eu.wohlben.qits.cli.tui.api.Input;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

/**
 * {@code qits work design}: the HTML designs kept with an epic's or a ticket's refinement. The design
 * doors are keyed by the refinement, so each command first asks the item for its refinement. A design
 * is written by people and agents, so a table shows it through {@link SafeText}, and the JSON form
 * goes through {@link SafeJson}.
 */
@CommandLine.Command(name = "design", mixinStandardHelpOptions = true,
        subcommands = {WorkDesignCommand.ListCommand.class, WorkDesignCommand.GetCommand.class,
                WorkDesignCommand.PutCommand.class},
        description = {"The HTML designs of an epic's or a ticket's refinement: the documents a person sees in "
                + "the Design tab. list shows them, get reads one with its document, put adds or rewrites one.",
                "There is no draft: a design is live the moment it is written, and a person may rename or delete "
                        + "it. The gate on the plan is the item's move from REPORTED to REFINED."},
        footerHeading = "%nNotes:%n",
        footer = {
                "- --entity is the epic or the ticket, not the refinement. An item with no open refinement has no "
                        + "designs.",
                "- Reading takes qits:admin or qits:agent. Writing takes qits:admin, qits:admin-agent or "
                        + "qits:desk-admin; an agent with qits:agent alone is refused (HTTP 403)."})
public class WorkDesignCommand implements Runnable {

    @CommandLine.ParentCommand
    WorkCommand work;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    static final String ADD_PATH = "/projects/api/refinements/{id}/designs";
    static final String UPDATE_PATH = "/projects/api/refinements/{id}/designs/{designId}";

    /** The id of the item's refinement; a failure when it has none open. */
    static String refinement(ProjectsApi api, String entity) throws CliFailure, InterruptedException {
        return refinement(api, entity, ", so it has no designs");
    }

    /**
     * The id of the item's refinement; a failure when it has none open, saying {@code No refinement is
     * open for <entity>} and then {@code tail} (empty, or what that means to the caller) and a full stop.
     */
    static String refinement(ProjectsApi api, String entity, String tail) throws CliFailure, InterruptedException {
        JsonNode room = api.workRefinement(entity).path("refinement");
        String id = room.isObject() ? text(room, "id") : "";
        if (id.isEmpty()) {
            throw new CliFailure("No refinement is open for " + SafeText.line(entity) + tail + ".",
                    CliFailure.FAILED);
        }
        return id;
    }

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = {"List the designs of an epic's or a ticket's refinement, oldest first, without their "
                    + "documents: id, title, version, size, whether a dossier page shows it, and when it changed.",
                    "Reads GET /projects/api/work/{entity}/refinement for the refinement, then GET "
                            + "/projects/api/refinements/{id}/designs. Read a design with get before you rewrite it."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits work --entity qits-100 design list", "  qits work --entity qits-100 design list -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The platform refused (for example an item it does not know, HTTP 404), the item has no open "
                            + "refinement, or the platform cannot be reached.",
                    "2:Used wrongly (for example no --entity), not signed in, or the session ended."})
    @TuiCommand(effect = Effect.READ)
    public static class ListCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkDesignCommand design;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            WorkCommand work = design.work;
            boolean json = json(work.options.output);
            ProjectsApi api = work.api(context);
            JsonNode answer = api.refinementDesigns(refinement(api, work.entity()));
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode d : answer.path("designs")) {
                rows.add(List.of(WorkEntities.cell(text(d, "id"), 40), WorkEntities.cell(text(d, "title"), 60),
                        d.path("version").asText(), d.path("htmlBytes").asText(),
                        d.path("inUse").asBoolean() ? "yes" : "-", Table.time(text(d, "updatedAt"))));
            }
            if (rows.isEmpty()) {
                context.out().println("The refinement has no designs.");
                return 0;
            }
            Table.print(context.out(), "", List.of("ID", "TITLE", "VERSION", "BYTES", "IN DOSSIER", "UPDATED"), rows);
            return 0;
        }
    }

    @CommandLine.Command(name = "get", mixinStandardHelpOptions = true,
            description = {"Read one design in full: its title, its version and the whole HTML document, styles "
                    + "inline and no scripts. Send the same shape back when you rewrite it, with this version.",
                    "Reads GET /projects/api/work/{entity}/refinement for the refinement, then GET "
                            + "/projects/api/refinements/{id}/designs/{designId}."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits work --entity qits-100 design get --design 6f1c0e2a-...",
                    "  qits work --entity qits-100 design get --design 6f1c0e2a-... -o json | jq -r .html"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The platform refused (for example no such item or design, HTTP 404), the item has no open "
                            + "refinement, or the platform cannot be reached.",
                    "2:Used wrongly (for example no --entity or no --design), not signed in, or the session ended."})
    @TuiCommand(effect = Effect.READ)
    public static class GetCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkDesignCommand design;

        @CommandLine.Option(names = "--design", paramLabel = "<design>", description = "The design's id (required).")
        String designId;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            WorkCommand work = design.work;
            boolean json = json(work.options.output);
            String entity = work.entity();
            String id = RepositoriesCommand.required(designId, "Name the design: --design <id>.");
            ProjectsApi api = work.api(context);
            JsonNode answer = api.refinementDesign(refinement(api, entity), id);
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            printDesign(context.out(), answer);
            context.out().println();
            WorkEntities.lines(context.out(), "", text(answer, "html"));
            return 0;
        }
    }

    @CommandLine.Command(name = "put", mixinStandardHelpOptions = true,
            description = {"Add a design to an epic's or a ticket's refinement, or rewrite one.",
                    "Without --design it adds one: the payload is {\"title\":\"...\",\"html\":\"...\"}, sent to POST "
                            + "/projects/api/refinements/{id}/designs. With --design it rewrites that design in "
                            + "place: the payload is {\"title\":\"...\",\"html\"?:\"...\",\"version\":<n>}, sent to "
                            + "PUT /projects/api/refinements/{id}/designs/{designId}; the title is required, and "
                            + "leaving html out only renames it.",
                    "html is a COMPLETE HTML document with inline styles and no scripts, the shape get answers. "
                            + "version is the one get gave you. When somebody wrote to the design after you read "
                            + "it, the service refuses (HTTP 409): read it again, fold your change in and write "
                            + "again; never write without a version.",
                    "The refinement comes from GET /projects/api/work/{entity}/refinement. With nothing on stdin "
                            + "(a terminal, or empty) it sends nothing, prints the payload's JSON schema from the "
                            + "service's OpenAPI document (/projects/q/openapi) and exits with 0."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  jq -n --rawfile h board.html '{title:\"Board\", html:$h}' | qits work --entity qits-100 "
                            + "design put",
                    "  jq -n --rawfile h board.html '{title:\"Board\", html:$h, version:2}' | qits work --entity "
                            + "qits-100 design put --design 6f1c0e2a-...",
                    "  qits work --entity qits-100 design put </dev/null"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The design is written, or nothing was put in and the schema is printed.",
                    "1:The platform refused (for example no title, html or version, HTTP 400, your roles, HTTP "
                            + "403, no such item or design, HTTP 404, or a stale version, HTTP 409), the item has "
                            + "no open refinement, or the platform cannot be reached.",
                    "2:Used wrongly (for example no --entity, or a payload that is not a JSON object), not signed "
                            + "in, or the session ended."})
    @TuiCommand(input = Input.PAYLOAD)
    public static class PutCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkDesignCommand design;

        @CommandLine.Option(names = "--design", paramLabel = "<design>",
                description = "The design to rewrite: its id. Leave it out to add a design.")
        String designId;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            WorkCommand work = design.work;
            boolean json = json(work.options.output);
            ObjectNode payload = WorkPayload.read(context);
            boolean rewrite = designId != null && !designId.isBlank();
            ProjectsApi api = work.api(context);
            if (payload == null) {
                WorkPayload.printSchema(context, api, json, rewrite ? "PUT" : "POST",
                        rewrite ? UPDATE_PATH : ADD_PATH, List.of("application/json"));
                return 0;
            }
            String room = refinement(api, work.entity());
            JsonNode answer = rewrite
                    ? api.updateRefinementDesign(room, designId.strip(), payload)
                    : api.addRefinementDesign(room, payload);
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            printDesign(context.out(), answer);
            return 0;
        }
    }

    /** A design's fields, without its document. */
    static void printDesign(PrintStream out, JsonNode d) {
        out.println("DESIGN " + SafeText.line(text(d, "id")));
        Table.print(out, "  ", null, List.of(
                List.of("title", WorkEntities.cell(text(d, "title"), 200)),
                List.of("version", d.path("version").asText()),
                List.of("bytes", d.path("htmlBytes").asText()),
                List.of("updated", Table.time(text(d, "updatedAt")))));
    }
}
