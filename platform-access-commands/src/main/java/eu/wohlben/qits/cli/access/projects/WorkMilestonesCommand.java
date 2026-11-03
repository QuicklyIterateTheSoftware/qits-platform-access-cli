package eu.wohlben.qits.cli.access.projects;

import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.tui.api.Effect;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

/**
 * {@code qits work milestones}: the milestones a work item needs (epic qits-615), attached and
 * detached one at a time. The milestones themselves are {@code qits milestones}.
 */
@CommandLine.Command(name = "milestones", mixinStandardHelpOptions = true,
        subcommands = {WorkMilestonesCommand.ListCommand.class, WorkMilestonesCommand.AttachCommand.class,
                WorkMilestonesCommand.DetachCommand.class},
        description = {"The milestones a work item needs: list shows them, attach adds one, detach takes one off. "
                + "An item needs every milestone attached to it; `qits work list` and `qits work details` show the "
                + "unmet ones as what it still needs.",
                "The milestones themselves - their names and criteria - are `qits milestones`."},
        footerHeading = "%nNotes:%n",
        footer = {
                "- --entity is the item: its qualified id (qits-100) or its id. --milestone is the milestone's id, "
                        + "as `qits milestones list` prints it.",
                "- A milestone and the item must be of the same project. Detaching leaves the milestone as it is."})
public class WorkMilestonesCommand implements Runnable {

    @CommandLine.ParentCommand
    WorkCommand work;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    static final String NONE = "The item needs no milestone.";

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = {"List the milestones a work item needs, in the order attached: id, whether it is met "
                    + "and since when, and its name.",
                    "Reads GET /projects/api/work/{entity}/milestones."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits work --entity qits-100 milestones list",
                    "  qits work --entity qits-100 milestones list -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The platform refused (for example an item it does not know, HTTP 404), or cannot be reached.",
                    "2:Used wrongly (for example no --entity), not signed in, or the session ended."})
    @TuiCommand(effect = Effect.READ)
    public static class ListCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkMilestonesCommand milestones;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            WorkCommand work = milestones.work;
            boolean json = json(work.options.output);
            MilestonesCommand.printBrief(context.out(), json, work.api(context).workMilestones(work.entity()), NONE);
            return 0;
        }
    }

    @CommandLine.Command(name = "attach", mixinStandardHelpOptions = true,
            description = {"Make a work item need a milestone of its project.",
                    "Sends PUT /projects/api/work/{entity}/milestones/{milestone} and prints what the item needs "
                            + "now. Attaching one it already needs changes nothing."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits work --entity qits-100 milestones attach --milestone 45a14f8e-f550-45bb-a117-6b34d8c472e3"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The item needs the milestone.",
                    "1:The platform refused (for example an agent in another project, HTTP 403, no such item or "
                            + "milestone, HTTP 404, or a milestone of another project, HTTP 409), or cannot be "
                            + "reached.",
                    "2:Used wrongly (for example no --entity or no --milestone), not signed in, or the session "
                            + "ended."})
    public static class AttachCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkMilestonesCommand milestones;

        @CommandLine.Option(names = "--milestone", paramLabel = "<milestone>",
                description = "The milestone (required): its id.")
        String milestone;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            WorkCommand work = milestones.work;
            boolean json = json(work.options.output);
            String entity = work.entity();
            String id = RepositoriesCommand.required(milestone, MilestonesCommand.NAME_THE_MILESTONE);
            MilestonesCommand.printBrief(context.out(), json, work.api(context).attachWorkMilestone(entity, id), NONE);
            return 0;
        }
    }

    @CommandLine.Command(name = "detach", mixinStandardHelpOptions = true,
            description = {"Make a work item no longer need a milestone.",
                    "Sends DELETE /projects/api/work/{entity}/milestones/{milestone} and prints what the item needs "
                            + "now. The milestone stays, and so does every other item's and campaign member's need "
                            + "of it."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits work --entity qits-100 milestones detach --milestone 45a14f8e-f550-45bb-a117-6b34d8c472e3"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The item no longer needs the milestone.",
                    "1:The platform refused (for example an agent in another project, HTTP 403, or no such item, or "
                            + "one that does not need this milestone, HTTP 404), or cannot be reached.",
                    "2:Used wrongly (for example no --entity or no --milestone), not signed in, or the session "
                            + "ended."})
    public static class DetachCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkMilestonesCommand milestones;

        @CommandLine.Option(names = "--milestone", paramLabel = "<milestone>",
                description = "The milestone (required): its id.")
        String milestone;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            WorkCommand work = milestones.work;
            boolean json = json(work.options.output);
            String entity = work.entity();
            String id = RepositoriesCommand.required(milestone, MilestonesCommand.NAME_THE_MILESTONE);
            MilestonesCommand.printBrief(context.out(), json, work.api(context).detachWorkMilestone(entity, id), NONE);
            return 0;
        }
    }
}
