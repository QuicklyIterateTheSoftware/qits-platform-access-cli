package eu.wohlben.qits.cli.access.projects;

import eu.wohlben.qits.cli.tui.api.Effect;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * {@code qits work members}: the members of a campaign, each an epic or a ticket with the condition
 * it waits on. Titles are written by people and agents, so a table shows them through {@link
 * eu.wohlben.qits.cli.access.observe.SafeText}, and the JSON form goes through {@link SafeJson}.
 */
@CommandLine.Command(name = "members", mixinStandardHelpOptions = true,
        subcommands = {WorkMembersCommand.ListCommand.class, WorkMembersCommand.AddCommand.class,
                WorkMembersCommand.MoveCommand.class, WorkMembersCommand.RemoveCommand.class,
                WorkMembersCommand.ConditionCommand.class},
        description = {"The members of a campaign: the epics and tickets it runs, in order, each with the "
                + "condition it waits on. list shows them, add gathers one, move reorders, remove takes one out, "
                + "and condition replaces what one waits on. `qits work progress` shows how they are doing.",
                "A condition is groups OR'd of criteria AND'd: a member waits until every criterion of at least "
                        + "one group holds."},
        footerHeading = "%nNotes:%n",
        footer = {
                "- --entity is the campaign. --member is a membership's id, as list prints it, not the member "
                        + "item's id.",
                "- Members and conditions change only while the campaign is REPORTED, REFINED or READY_FOR_DEV.",
                "- Starting a campaign and approving an APPROVAL criterion are a person's to do."})
public class WorkMembersCommand implements Runnable {

    static final String NAME_THE_MEMBER = "Name the membership: --member <id>, as `members list` prints it.";

    @CommandLine.ParentCommand
    WorkCommand work;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    static final String ADD_PATH = ProjectsApi.WORK + "/{entity}/members";
    static final String CONDITION_PATH = ProjectsApi.WORK + "/{entity}/members/{membershipId}/condition";

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = {"List a campaign's members in order: membership id, position, the member's qualified "
                    + "id, archetype, status and title, and whether it waits on a condition.",
                    "Reads GET /projects/api/work/{entity}/members. -o json adds each member's condition (groups "
                            + "of criteria with their latches) and its dispatch record."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits work --entity qits-130 members list", "  qits work --entity qits-130 members list -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The platform refused (for example no campaign with this id, HTTP 404), or cannot be reached.",
                    "2:Used wrongly (for example no --entity), not signed in, or the session ended."})
    @TuiCommand(effect = Effect.READ)
    public static class ListCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkMembersCommand members;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            WorkCommand work = members.work;
            boolean json = json(work.options.output);
            printMembers(context.out(), json, work.api(context).workMembers(work.entity()));
            return 0;
        }
    }

    @CommandLine.Command(name = "add", mixinStandardHelpOptions = true,
            description = {"Gather an epic or a ticket into a campaign.",
                    "Reads {\"entityId\":\"<qualified id or id>\",\"position\"?:<n>,\"inFlight\"?:true|false} on "
                            + "stdin and sends it unchanged to POST /projects/api/work/{entity}/members. Without "
                            + "position it goes at the end. When the member before it is not yet VERIFIED or DONE, "
                            + "the new member is made to wait for that one to reach VERIFIED, so the order states the "
                            + "dependency; change it with `members condition`. Without inFlight the service decides: "
                            + "a member already IMPLEMENTING or later, or with an ACTIVE workspace on its branch, "
                            + "joins running.",
                    "With nothing on stdin (a terminal, or empty) it sends nothing, prints the payload's JSON schema "
                            + "from the service's OpenAPI document (/projects/q/openapi) and exits with 0."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  echo '{\"entityId\":\"qits-101\"}' | qits work --entity qits-130 members add",
                    "  echo '{\"entityId\":\"qits-102\",\"position\":0}' | qits work --entity qits-130 members add",
                    "  qits work --entity qits-130 members add </dev/null"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The member is added, or nothing was put in and the schema is printed.",
                    "1:The platform refused (for example no entityId, HTTP 400, another project, HTTP 403, no such "
                            + "campaign or item, HTTP 404, or one that cannot join - a member already, a feature or "
                            + "a task, a closed campaign - HTTP 409), or cannot be reached.",
                    "2:Used wrongly (for example no --entity, or a payload that is not a JSON object), not signed "
                            + "in, or the session ended."})
    @TuiCommand(input = Input.PAYLOAD)
    public static class AddCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkMembersCommand members;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            WorkCommand work = members.work;
            boolean json = json(work.options.output);
            ObjectNode payload = WorkPayload.read(context);
            ProjectsApi api = work.api(context);
            if (payload == null) {
                WorkPayload.printSchema(context, api, json, "POST", ADD_PATH, List.of("application/json"));
                return 0;
            }
            JsonNode answer = api.addWorkMember(work.entity(), payload);
            printMember(context.out(), json, answer);
            return 0;
        }
    }

    @CommandLine.Command(name = "move", mixinStandardHelpOptions = true,
            description = {"Move a member to a position in the campaign, counted from 0; past the end is the "
                    + "last place. The members between shift.",
                    "Sends {\"position\":<n>} to PUT /projects/api/work/{entity}/members/{membershipId}/position "
                            + "and prints the members in their new order. It changes no condition: what a member "
                            + "waits on is its criteria, not its place."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits work --entity qits-130 members move --member 5f2c0a9e-... --position 0"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The member is moved.",
                    "1:The platform refused (for example another project, HTTP 403, or no such campaign or "
                            + "membership, HTTP 404), or cannot be reached.",
                    "2:Used wrongly (for example no --entity, --member or --position), not signed in, or the "
                            + "session ended."})
    public static class MoveCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkMembersCommand members;

        @CommandLine.Option(names = "--member", paramLabel = "<membership>",
                description = "The membership to move (required): its id, as list prints it.")
        String member;

        @CommandLine.Option(names = "--position", paramLabel = "<n>",
                description = "Where it goes (required), counted from 0.")
        Integer position;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            WorkCommand work = members.work;
            boolean json = json(work.options.output);
            String entity = work.entity();
            String id = RepositoriesCommand.required(member, NAME_THE_MEMBER);
            if (position == null) {
                throw new CliFailure("Say where it goes: --position <n>, counted from 0.", CliFailure.USAGE);
            }
            ObjectNode body = JsonNodeFactory.instance.objectNode();
            body.put("position", position);
            printMembers(context.out(), json, work.api(context).moveWorkMember(entity, id, body));
            return 0;
        }
    }

    @CommandLine.Command(name = "remove", mixinStandardHelpOptions = true,
            description = {"Take a member out of a campaign, with its condition.",
                    "Sends DELETE /projects/api/work/{entity}/members/{membershipId} and prints the members that "
                            + "remain. Refused once the member is claimed (its run record would be lost), and "
                            + "while another member's condition still names it: change that condition first."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits work --entity qits-130 members remove --member 5f2c0a9e-..."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The member is removed.",
                    "1:The platform refused (for example another project, HTTP 403, no such campaign or "
                            + "membership, HTTP 404, or a claimed member or one another member waits on, HTTP 409), "
                            + "or cannot be reached.",
                    "2:Used wrongly (for example no --entity or no --member), not signed in, or the session ended."})
    public static class RemoveCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkMembersCommand members;

        @CommandLine.Option(names = "--member", paramLabel = "<membership>",
                description = "The membership to remove (required): its id, as list prints it.")
        String member;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            WorkCommand work = members.work;
            boolean json = json(work.options.output);
            String entity = work.entity();
            String id = RepositoriesCommand.required(member, NAME_THE_MEMBER);
            printMembers(context.out(), json, work.api(context).removeWorkMember(entity, id));
            return 0;
        }
    }

    @CommandLine.Command(name = "condition", mixinStandardHelpOptions = true,
            description = {"Replace what a campaign member waits on, whole.",
                    "Reads {\"groups\":[{\"criteria\":[{\"id\"?,\"kind\",\"predicate\"}]}]} on stdin and sends it "
                            + "unchanged to PUT /projects/api/work/{entity}/members/{membershipId}/condition. What "
                            + "you state is the whole condition afterwards; what you leave out is deleted. Groups "
                            + "are OR'd, criteria in a group AND'd. {\"groups\":[]} makes the member wait on "
                            + "nothing; an empty group is refused. Restate a criterion with its id to keep whether "
                            + "it already held; one without an id is new.",
                    "Kinds and their predicates: ENTITY_STATUS {entityId, status} - another member of this "
                            + "campaign (qualified id or id) reaches that status. DEPLOYMENT_ACTIVE "
                            + "{applicationName, environmentName?, minimumVersion?} - that application goes live. "
                            + "SCM_RELEASE {repositoryName, projectId?, minimumVersion?} - that repository releases. "
                            + "APPROVAL {} - a person says yes.",
                    "With nothing on stdin (a terminal, or empty) it sends nothing, prints the payload's JSON schema "
                            + "from the service's OpenAPI document (/projects/q/openapi) and exits with 0."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  echo '{\"groups\":[{\"criteria\":[{\"kind\":\"ENTITY_STATUS\",\"predicate\":"
                            + "{\"entityId\":\"qits-101\",\"status\":\"VERIFIED\"}}]}]}' "
                            + "| qits work --entity qits-130 members condition --member 5f2c0a9e-...",
                    "  echo '{\"groups\":[]}' | qits work --entity qits-130 members condition --member 5f2c0a9e-...",
                    "",
                    "- Refused once the member is claimed: its condition is settled."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The condition is replaced, or nothing was put in and the schema is printed.",
                    "1:The platform refused (for example a criterion it cannot read, HTTP 400, another project, "
                            + "HTTP 403, no such campaign or membership, HTTP 404, or a claimed member, HTTP 409), "
                            + "or cannot be reached.",
                    "2:Used wrongly (for example no --entity or no --member, or a payload that is not a JSON "
                            + "object), not signed in, or the session ended."})
    @TuiCommand(input = Input.PAYLOAD)
    public static class ConditionCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkMembersCommand members;

        @CommandLine.Option(names = "--member", paramLabel = "<membership>",
                description = "The membership whose condition to replace (required unless nothing is put in): "
                        + "its id, as list prints it.")
        String member;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            WorkCommand work = members.work;
            boolean json = json(work.options.output);
            ObjectNode payload = WorkPayload.read(context);
            ProjectsApi api = work.api(context);
            if (payload == null) {
                WorkPayload.printSchema(context, api, json, "PUT", CONDITION_PATH, List.of("application/json"));
                return 0;
            }
            String entity = work.entity();
            String id = RepositoriesCommand.required(member, NAME_THE_MEMBER);
            printMember(context.out(), json, api.setWorkMemberCondition(entity, id, payload));
            return 0;
        }
    }

    /** {@code {"members":[…]}}: as JSON, or a row each. */
    static void printMembers(PrintStream out, boolean json, JsonNode answer) throws CliFailure {
        if (json) {
            SafeJson.print(out, answer);
            return;
        }
        List<List<String>> rows = new ArrayList<>();
        answer.path("members").forEach(m -> rows.add(row(m)));
        if (rows.isEmpty()) {
            out.println("The campaign has no members.");
            return;
        }
        Table.print(out, "", HEADERS, rows);
    }

    /** {@code {"member":{…}}}: as JSON, or its row. */
    static void printMember(PrintStream out, boolean json, JsonNode answer) throws CliFailure {
        if (json) {
            SafeJson.print(out, answer);
            return;
        }
        Table.print(out, "", HEADERS, List.of(row(answer.path("member"))));
    }

    private static final List<String> HEADERS =
            List.of("MEMBERSHIP", "POS", "ID", "ARCHETYPE", "STATUS", "WAITS", "TITLE");

    private static List<String> row(JsonNode m) {
        JsonNode e = m.path("entity");
        int groups = m.path("groups").size();
        return List.of(WorkEntities.cell(text(m, "membershipId"), 40), m.path("position").asText(),
                WorkEntities.cell(text(e, "qualifiedId"), 40), WorkEntities.cell(text(e, "archetype"), 9),
                WorkEntities.cell(text(e, "status"), 11),
                groups == 0 ? "-" : groups + (groups == 1 ? " group" : " groups"),
                WorkEntities.cell(text(e, "title"), 60));
    }
}
