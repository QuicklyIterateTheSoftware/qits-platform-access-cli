package eu.wohlben.qits.cli.access.projects;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.Table;
import eu.wohlben.qits.cli.tui.api.Effect;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.io.PrintStream;
import java.util.List;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

/**
 * {@code qits work refinement}: an epic's or a ticket's refinement room itself, the one the Refine
 * button opens. {@code open} does what the page does, the open and then the bring-up of the room's
 * container, because a room whose container was never brought up has nothing running in it. The
 * room's label and status come from the service, so a table shows them through {@link SafeText},
 * and the JSON form goes through {@link SafeJson}.
 */
@CommandLine.Command(name = "refinement", mixinStandardHelpOptions = true,
        subcommands = {WorkRefinementCommand.OpenCommand.class, WorkRefinementCommand.ShowCommand.class,
                WorkRefinementCommand.DiscardCommand.class},
        description = {"An epic's or a ticket's refinement room: the workspace with its own container and its own "
                + "refining/<slug> branch where the item's scope is worked out, and where its designs live. open "
                + "opens the room and brings its container up, show shows it, discard tears it down.",
                "An item has at most one room, opened only while the item is at REPORTED and never for a "
                        + "feature or a task. The move from REPORTED to REFINED is the gate on the plan; the room "
                        + "stays until it is discarded or the item reaches a final status."},
        footerHeading = "%nNotes:%n",
        footer = {
                "- --entity is the epic or the ticket, not the room. The room's own id is in show's ID column.",
                "- show takes qits:admin, qits:admin-agent or qits:agent. open and discard take qits:admin or "
                        + "qits:admin-agent; an agent with qits:agent alone is refused (HTTP 403)."})
public class WorkRefinementCommand implements Runnable {

    @CommandLine.ParentCommand
    WorkCommand work;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    @CommandLine.Command(name = "open", mixinStandardHelpOptions = true,
            description = {"Open an epic's or a ticket's refinement room and bring its container up, as the Refine "
                    + "button does. An item that already has a room gets that room back, and its container is "
                    + "brought up again: a stopped one is woken, a running one is left running.",
                    "Sends POST /projects/api/work/{entity}/refinement, then POST "
                            + "/projects/api/refinements/{id}/ensure-container with the room's id. The container "
                            + "comes up in the background; the technical process it prints narrates that. Shows the "
                            + "room's id, its item, its label and its status, which is PROVISIONING while the "
                            + "container comes up."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits work --entity qits-100 refinement open",
                    "  qits work --entity qits-100 refinement open -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The room is open and its container is coming up.",
                    "1:The platform refused (for example your roles, HTTP 403, an item it does not know, HTTP 404, "
                            + "an item that cannot be refined now, such as one not at REPORTED, a feature or a task, "
                            + "HTTP 409, or a branch the git host would not cut, HTTP 502), or the platform cannot "
                            + "be reached.",
                    "2:Used wrongly (for example no --entity), not signed in, or the session ended."})
    @TuiCommand(effect = Effect.WRITE)
    public static class OpenCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkRefinementCommand refinement;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            WorkCommand work = refinement.work;
            boolean json = json(work.options.output);
            ProjectsApi api = work.api(context);
            JsonNode opened = api.openWorkRefinement(work.entity()).path("refinement");
            String id = opened.isObject() ? text(opened, "id") : "";
            if (id.isEmpty()) {
                throw new CliFailure("The platform answered the open with no room.", CliFailure.FAILED);
            }
            JsonNode answer = api.ensureRefinement(id);
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            printRoom(context.out(), answer.path("refinement"));
            context.out().println();
            context.out().println("Technical process: " + SafeText.line(text(answer, "technicalProcessId")));
            return 0;
        }
    }

    @CommandLine.Command(name = "show", mixinStandardHelpOptions = true,
            description = {"Show an epic's or a ticket's refinement room: its id, its item, its label and its status, "
                    + "such as PROVISIONING, RUNNING or STOPPED. An item with no room says so and exits with 0.",
                    "Reads GET /projects/api/work/{entity}/refinement; it never opens a room."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits work --entity qits-100 refinement show",
                    "  qits work --entity qits-100 refinement show -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:Done, also when the item has no room.",
                    "1:The platform refused (for example an item it does not know, HTTP 404), or cannot be reached.",
                    "2:Used wrongly (for example no --entity), not signed in, or the session ended."})
    @TuiCommand(effect = Effect.READ)
    public static class ShowCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkRefinementCommand refinement;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            WorkCommand work = refinement.work;
            boolean json = json(work.options.output);
            String entity = work.entity();
            JsonNode answer = work.api(context).workRefinement(entity);
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            JsonNode room = answer.path("refinement");
            if (!room.isObject()) {
                context.out().println(noRoom(entity));
                return 0;
            }
            printRoom(context.out(), room);
            return 0;
        }
    }

    @CommandLine.Command(name = "discard", mixinStandardHelpOptions = true,
            description = {"Discard an epic's or a ticket's refinement room. This deletes the room's container, its "
                    + "refining/<slug> branch, and its designs, prompt draft and prompt images with it; none of it "
                    + "can be brought back. The work item itself is untouched, and a later open starts a fresh "
                    + "room.",
                    "Reads GET /projects/api/work/{entity}/refinement for the room, then sends POST "
                            + "/projects/api/refinements/{id}/discard."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits work --entity qits-100 refinement discard",
                    "  qits work --entity qits-100 refinement discard -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The room is discarded.",
                    "1:The platform refused (for example your roles, HTTP 403, or an item or room it does not know, "
                            + "HTTP 404), the item has no room, or the platform cannot be reached.",
                    "2:Used wrongly (for example no --entity), not signed in, or the session ended."})
    @TuiCommand(effect = Effect.WRITE)
    public static class DiscardCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkRefinementCommand refinement;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            WorkCommand work = refinement.work;
            boolean json = json(work.options.output);
            String entity = work.entity();
            ProjectsApi api = work.api(context);
            JsonNode answer = api.discardRefinement(WorkDesignCommand.refinement(api, entity, ""));
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            context.out().println("Discarded the refinement room of " + SafeText.line(entity) + ".");
            return 0;
        }
    }

    static String noRoom(String entity) {
        return "No refinement is open for " + SafeText.line(entity) + ".";
    }

    /** The room as one row: what a person needs to find it again. */
    static void printRoom(PrintStream out, JsonNode room) {
        Table.print(out, "", List.of("ID", "ENTITY", "LABEL", "STATUS"), List.of(List.of(
                WorkEntities.cell(text(room, "id"), 40), WorkEntities.cell(text(room, "entityId"), 40),
                WorkEntities.cell(text(room, "label"), 60), WorkEntities.cell(text(room, "runtimeStatus"), 20))));
    }
}
