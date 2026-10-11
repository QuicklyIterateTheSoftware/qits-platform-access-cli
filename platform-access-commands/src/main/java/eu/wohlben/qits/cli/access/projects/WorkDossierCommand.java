package eu.wohlben.qits.cli.access.projects;

import eu.wohlben.qits.cli.tui.api.Effect;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
import java.util.Locale;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

/**
 * {@code qits work dossier}: the pages of an epic's or a ticket's dossier. A page is written by
 * people and agents, so every value a table shows goes through {@link SafeText}, and the JSON form
 * through {@link SafeJson}.
 */
@CommandLine.Command(name = "dossier", mixinStandardHelpOptions = true,
        subcommands = {WorkDossierCommand.ListCommand.class, WorkDossierCommand.GetCommand.class,
                WorkDossierCommand.PutCommand.class, WorkDossierCommand.MoveCommand.class,
                WorkDossierCommand.DeleteCommand.class, WorkDossierCommand.InlineCommand.class},
        description = {"The dossier of an epic or a ticket: Markdown pages in reading order, for what the item's "
                + "own description cannot hold. list shows the pages, get reads one, put adds or rewrites one, "
                + "move reorders, delete removes one, and inline puts a sketch or a design into an epic's dossier.",
                "The item's description is the short form: an epic's is the pitch, a ticket's is what refinement "
                        + "concluded. The dossier is what the implementer builds from, a page per part: what "
                        + "changes and how it works, with paths, names, exact values, examples and figures."},
        footerHeading = "%nNotes:%n",
        footer = {
                "- --page is a page's slug (as list shows it) or its id.",
                "- Nobody accepts a write: a page is live the moment it lands. An epic's dossier is writable while "
                        + "the epic is REPORTED; a ticket's at every status.",
                "- Reading and writing take qits:admin or qits:agent; an agent writes only in its own project."})
public class WorkDossierCommand implements Runnable {

    static final String NAME_THE_PAGE = "Name the page: --page <slug or id>.";

    @CommandLine.ParentCommand
    WorkCommand work;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    static final String CREATE_PATH = ProjectsApi.WORK + "/{entity}/dossier";
    static final String PAGE_PATH = ProjectsApi.WORK + "/{entity}/dossier/{page}";

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = {"List the pages of an epic's or a ticket's dossier in reading order: position, slug, "
                    + "title, version and when it last changed. Read this before you add a page, so the new page "
                    + "does not repeat one that is there.",
                    "Reads GET /projects/api/work/{entity}/dossier. -o json includes each page's Markdown body."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits work --entity qits-100 dossier list", "  qits work dossier list --entity qits-100 -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The platform refused (for example an item it does not know or one with no dossier, HTTP "
                            + "404), or cannot be reached.",
                    "2:Used wrongly (for example no --entity), not signed in, or the session ended."})
    @TuiCommand(effect = Effect.READ)
    public static class ListCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkDossierCommand dossier;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            WorkCommand work = dossier.work;
            boolean json = json(work.options.output);
            JsonNode answer = work.api(context).workDossier(work.entity());
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode p : answer.path("pages")) {
                rows.add(List.of(p.path("position").asText(), WorkEntities.cell(text(p, "slug"), 50),
                        WorkEntities.cell(text(p, "title"), 70), p.path("version").asText(),
                        Table.time(text(p, "updatedAt"))));
            }
            if (rows.isEmpty()) {
                context.out().println("The dossier has no pages.");
                return 0;
            }
            Table.print(context.out(), "", List.of("POS", "SLUG", "TITLE", "VERSION", "UPDATED"), rows);
            return 0;
        }
    }

    @CommandLine.Command(name = "get", mixinStandardHelpOptions = true,
            description = {"Read one dossier page in full: its title, its Markdown body and its version.",
                    "Reads GET /projects/api/work/{entity}/dossier/{page}. Read a page before you rewrite it: "
                            + "put needs the version this answers, and a page is somebody's prose."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits work --entity qits-100 dossier get --page data-flow",
                    "  qits work dossier get --entity qits-100 --page data-flow -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The platform refused (for example no such item or no such page, HTTP 404), or cannot be "
                            + "reached.",
                    "2:Used wrongly (for example no --entity or no --page), not signed in, or the session ended."})
    @TuiCommand(effect = Effect.READ)
    public static class GetCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkDossierCommand dossier;

        @CommandLine.Option(names = "--page", paramLabel = "<page>",
                description = "The page (required): its slug or its id.")
        String page;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            WorkCommand work = dossier.work;
            boolean json = json(work.options.output);
            String entity = work.entity();
            String wanted = RepositoriesCommand.required(page, NAME_THE_PAGE);
            JsonNode answer = work.api(context).workDossierPage(entity, wanted);
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            printPage(context.out(), answer, true);
            return 0;
        }
    }

    @CommandLine.Command(name = "put", mixinStandardHelpOptions = true,
            description = {"Add a page to an epic's or a ticket's dossier, or rewrite one.",
                    "Without --page it adds a page at the end: the payload is {\"title\":\"...\",\"body\":\"...\"}, "
                            + "sent to POST /projects/api/work/{entity}/dossier. The slug is made from the title "
                            + "and never changes.",
                    "With --page it rewrites that page: the payload is {\"title\"?:\"...\",\"body\"?:\"...\","
                            + "\"version\":<n>}, sent to PUT /projects/api/work/{entity}/dossier/{page}. A property "
                            + "left out keeps its value. version is the one get gave you; when somebody wrote to "
                            + "the page after you read it, the service refuses (HTTP 409). Read the page again, "
                            + "fold your change into it and write again; never write without a version.",
                    "The body is Markdown. Write what changes and how it works: paths, names, commands, exact "
                            + "values. To show a sketch or a design on an epic's page, get its Markdown line from "
                            + "inline; never write a figure URL by hand. A ticket's dossier has no figures.",
                    "With nothing on stdin (a terminal, or empty) it sends nothing, prints the payload's JSON schema "
                            + "from the service's OpenAPI document (/projects/q/openapi) and exits with 0."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  jq -n --rawfile b plan.md '{title:\"Data flow\", body:$b}' | qits work --entity qits-100 "
                            + "dossier put",
                    "  jq -n --rawfile b plan.md '{body:$b, version:3}' | qits work --entity qits-100 dossier put "
                            + "--page data-flow",
                    "  qits work --entity qits-100 dossier put --page data-flow </dev/null"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The page is written, or nothing was put in and the schema is printed.",
                    "1:The platform refused (for example a blank title or no version, HTTP 400, another project, "
                            + "HTTP 403, no such item or page, HTTP 404, or a stale version or an epic past "
                            + "REPORTED, HTTP 409), or cannot be reached.",
                    "2:Used wrongly (for example no --entity, or a payload that is not a JSON object), not signed "
                            + "in, or the session ended."})
    @TuiCommand(input = Input.PAYLOAD)
    public static class PutCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkDossierCommand dossier;

        @CommandLine.Option(names = "--page", paramLabel = "<page>",
                description = "The page to rewrite: its slug or its id. Leave it out to add a page.")
        String page;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            WorkCommand work = dossier.work;
            boolean json = json(work.options.output);
            ObjectNode payload = WorkPayload.read(context);
            boolean rewrite = page != null && !page.isBlank();
            ProjectsApi api = work.api(context);
            if (payload == null) {
                WorkPayload.printSchema(context, api, json, rewrite ? "PUT" : "POST",
                        rewrite ? PAGE_PATH : CREATE_PATH, List.of("application/json"));
                return 0;
            }
            String entity = work.entity();
            JsonNode answer = rewrite
                    ? api.putWorkDossierPage(entity, page.strip(), payload)
                    : api.createWorkDossierPage(entity, payload);
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            printPage(context.out(), answer, false);
            return 0;
        }
    }

    @CommandLine.Command(name = "move", mixinStandardHelpOptions = true,
            description = {"Move a dossier page to a position in the reading order, counted from 0. The pages "
                    + "between its old and new place shift to close the gap.",
                    "Sends {\"position\":<n>} to POST /projects/api/work/{entity}/dossier/{page}/move. Reading "
                            + "order is the only structure a dossier has: there is no nesting, and a page's own "
                            + "headings are its second level."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits work --entity qits-100 dossier move --page data-flow --position 0"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The page is moved.",
                    "1:The platform refused (for example another project, HTTP 403, no such item or page, HTTP 404, "
                            + "or an epic past REPORTED, HTTP 409), or cannot be reached.",
                    "2:Used wrongly (for example no --entity, --page or --position), not signed in, or the session "
                            + "ended."})
    public static class MoveCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkDossierCommand dossier;

        @CommandLine.Option(names = "--page", paramLabel = "<page>",
                description = "The page to move (required): its slug or its id.")
        String page;

        @CommandLine.Option(names = "--position", paramLabel = "<n>",
                description = "Where it goes (required), counted from 0.")
        Integer position;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            WorkCommand work = dossier.work;
            boolean json = json(work.options.output);
            String entity = work.entity();
            String wanted = RepositoriesCommand.required(page, NAME_THE_PAGE);
            if (position == null) {
                throw new CliFailure("Say where it goes: --position <n>, counted from 0.", CliFailure.USAGE);
            }
            ObjectNode body = JsonNodeFactory.instance.objectNode();
            body.put("position", position);
            JsonNode answer = work.api(context).moveWorkDossierPage(entity, wanted, body);
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            printPage(context.out(), answer, false);
            return 0;
        }
    }

    @CommandLine.Command(name = "delete", mixinStandardHelpOptions = true,
            description = {"Delete a dossier page. The pages after it close the gap.",
                    "Sends DELETE /projects/api/work/{entity}/dossier/{page}. Deleting somebody's page is not a "
                            + "way to disagree with it: rewrite the part that is wrong, and delete only what you "
                            + "added and no longer mean. On an epic, a figure no other page shows goes with it."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits work --entity qits-100 dossier delete --page old-notes"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The page is gone.",
                    "1:The platform refused (for example another project, HTTP 403, no such item or page, HTTP 404, "
                            + "or an epic past REPORTED, HTTP 409), or cannot be reached.",
                    "2:Used wrongly (for example no --entity or no --page), not signed in, or the session ended."})
    public static class DeleteCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkDossierCommand dossier;

        @CommandLine.Option(names = "--page", paramLabel = "<page>",
                description = "The page to delete (required): its slug or its id.")
        String page;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            WorkCommand work = dossier.work;
            boolean json = json(work.options.output);
            String entity = work.entity();
            String wanted = RepositoriesCommand.required(page, NAME_THE_PAGE);
            JsonNode answer = work.api(context).deleteWorkDossierPage(entity, wanted);
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            context.out().println("Deleted page " + SafeText.line(wanted) + ".");
            return 0;
        }
    }

    @CommandLine.Command(name = "inline", mixinStandardHelpOptions = true,
            description = {"Copy a sketch or a design of an epic's refinement into the epic's dossier, and print "
                    + "the exact Markdown line to paste into a page.",
                    "Sends {\"sourceId\":\"...\",\"kind\":\"IMAGE|DESIGN\"} to POST "
                            + "/projects/api/work/{entity}/dossier-assets. IMAGE is a sketch from the refinement's "
                            + "Sketch tab, DESIGN a design (`qits work design list`). The line renders as a picture "
                            + "or as a framed document. The copy keeps rendering after the refinement is gone, and "
                            + "inlining the same figure again gives the same line, not a second copy.",
                    "Never write a figure URL by hand: it ends up naming something that does not exist or "
                            + "belongs to another epic, and the page shows it broken."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits work --entity qits-100 dossier inline --source 6f1c0e2a-... --kind design",
                    "",
                    "- The table prints the Markdown line alone; -o json prints the figure's id, kind, label and URL "
                            + "too."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The figure is in the dossier and its line is printed.",
                    "1:The platform refused (for example another project, HTTP 403, or an item that is not an epic "
                            + "or no such figure in its refinement, HTTP 404), or cannot be reached.",
                    "2:Used wrongly (for example no --entity, --source or --kind), not signed in, or the session "
                            + "ended."})
    public static class InlineCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkDossierCommand dossier;

        @CommandLine.Option(names = "--source", paramLabel = "<id>",
                description = "The sketch's or the design's id (required).")
        String source;

        @CommandLine.Option(names = "--kind", paramLabel = "<kind>",
                description = "IMAGE for a sketch, DESIGN for a design (required). Case-insensitive.")
        String kind;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            WorkCommand work = dossier.work;
            boolean json = json(work.options.output);
            String entity = work.entity();
            ObjectNode body = JsonNodeFactory.instance.objectNode();
            body.put("sourceId", RepositoriesCommand.required(source, "Name the figure: --source <id>."));
            body.put("kind", RepositoriesCommand.required(kind, "Name its kind: --kind IMAGE or --kind DESIGN.")
                    .toUpperCase(Locale.ROOT));
            JsonNode answer = work.api(context).inlineWorkDossierAsset(entity, body);
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            context.out().println(SafeText.line(text(answer, "markdown")));
            return 0;
        }
    }

    /** A page's fields, then its body when {@code withBody}. */
    static void printPage(PrintStream out, JsonNode p, boolean withBody) {
        out.println("PAGE " + SafeText.line(text(p, "slug")) + "  (" + SafeText.line(text(p, "id")) + ")");
        Table.print(out, "  ", null, List.of(
                List.of("title", WorkEntities.cell(text(p, "title"), 200)),
                List.of("position", p.path("position").asText()),
                List.of("version", p.path("version").asText()),
                List.of("updated", Table.time(text(p, "updatedAt")))));
        if (withBody) {
            out.println();
            WorkEntities.lines(out, "", text(p, "body"));
        }
    }
}
