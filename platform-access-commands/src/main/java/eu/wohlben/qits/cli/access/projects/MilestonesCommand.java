package eu.wohlben.qits.cli.access.projects;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Locale;
import java.util.Map;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

/**
 * {@code qits milestones}: a project's milestones (epic qits-615). A milestone is a foreseeable
 * "needs to be met" with a name, shared by any number of work items: groups OR'd of criteria
 * AND'd, the same condition a campaign member used to carry alone. It has no lifecycle: it holds
 * or it does not, and it can go back to unmet when its criteria are edited.
 * <p>
 * The writes that take a condition read it on stdin as {@code qits work} does ({@link
 * WorkPayload}), and with none print the payload's schema from the service's OpenAPI document.
 * {@code qits work milestones} attaches one to an item; {@code qits work members milestones} sets
 * what a campaign member needs.
 * <p>
 * Names, titles and notes are written by people and agents, so every value a table shows goes
 * through {@link SafeText}, and the JSON form through {@link SafeJson}.
 */
@CommandLine.Command(name = "milestones", mixinStandardHelpOptions = true,
        subcommands = {MilestonesCommand.ListCommand.class, MilestonesCommand.DetailsCommand.class,
                MilestonesCommand.CreateCommand.class, MilestonesCommand.RenameCommand.class,
                MilestonesCommand.CriteriaCommand.class, MilestonesCommand.ApproveCommand.class},
        description = {"A project's milestones: named, foreseeable things that must be met before work can go on "
                        + "(\"bootstrap test node\"). list shows a project's milestones, details shows one with its "
                        + "criteria and the items that need it, create writes one, rename renames it, criteria "
                        + "replaces its condition, and approve says yes to an APPROVAL criterion.",
                "A milestone's condition is groups OR'd of criteria AND'd: it is met while every criterion of at "
                        + "least one group holds. Each criterion, each group and the milestone keep the date they "
                        + "came to hold. Editing a criterion clears its latch, so a met milestone can become unmet "
                        + "again; the platform then sends MilestoneUnmet, and MilestoneMet when it holds again.",
                "Any work item can need any number of milestones of its project (`qits work milestones attach`), "
                        + "and a campaign member is dispatched only once all of its milestones are met (`qits work "
                        + "members milestones`). `qits work list` and `qits work details` show what an item still "
                        + "needs. A milestone is not a block: a block is a problem found during work; a milestone "
                        + "is known in advance.",
                MilestonesCommand.CRITERION_KINDS,
                "A write that takes a condition reads its payload, a JSON document, on stdin. With nothing on stdin "
                        + "(a terminal, or empty) it sends nothing and prints its usage and the payload's JSON "
                        + "schema instead, from the service's OpenAPI document."},
        footerHeading = "%nNotes:%n",
        footer = {
                "- --milestone, --output and --projects-url may come before or after the command.",
                "- --milestone is the milestone's id, as list and create print it.",
                "- Reading takes qits:admin or qits:agent; so does writing, in the agent's own project, except "
                        + "approve, which takes a person (qits:admin) or the project's delivery desk."})
public class MilestonesCommand implements Runnable {

    static final String NAME_THE_MILESTONE = "Name the milestone: --milestone <id>, as `qits milestones list` prints it.";

    /** The criterion kinds and when each latches: the same words in every help that takes a condition. */
    static final String CRITERION_KINDS = "Kinds and their predicates: ENTITY_STATUS {entityId, status} - a work item "
            + "of the same project (qualified id or id) reaches that status; it latches from the current state, so "
            + "an item already there meets it at once. DEPLOYMENT_ACTIVE {applicationName, environmentName?, "
            + "minimumVersion?} - that application goes live. SCM_RELEASE {repositoryName, projectId?, "
            + "minimumVersion?} - that repository releases. DEPLOYMENT_ACTIVE and SCM_RELEASE latch only on events "
            + "after the criterion was made: a deployment or a release from before it never meets it. APPROVAL {} "
            + "- a person says yes (approve).";

    @CommandLine.Mixin
    ProjectsOptions options;

    @CommandLine.Option(names = "--milestone", paramLabel = "<milestone>", scope = CommandLine.ScopeType.INHERIT,
            description = "The milestone: its id, passed to the service as it is.")
    String milestone;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    String milestone() throws CliFailure {
        return RepositoriesCommand.required(milestone, NAME_THE_MILESTONE);
    }

    ProjectsApi api(CliContext context) throws CliFailure, InterruptedException {
        return ProjectsApi.connect(context, options.projectsUrl);
    }

    // --- list ---

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = {"A project's milestones, oldest first: id, whether it is met and since when, its "
                    + "criteria (how many hold of how many), the items that need it, and its name.",
                    "Reads GET /projects/api/projects/{project}/milestones. -o json prints each milestone whole: its "
                            + "groups and criteria with their dates, evidence and approval, the items and the "
                            + "campaign memberships that need it."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits milestones list --project qits",
                    "  qits milestones list --project qits --met false",
                    "  qits milestones list --project qits -o json",
                    "",
                    "- --met false lists the unmet ones only, what work still needs; --met true the met ones."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The platform refused (for example a project it does not know, HTTP 404), or cannot be "
                            + "reached.",
                    "2:Used wrongly (for example no --project, or --met neither true nor false), not signed in, or "
                            + "the session ended."})
    @TuiCommand(effect = Effect.READ)
    public static class ListCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        MilestonesCommand milestones;

        @Completes(ProjectSource.class)
        @CommandLine.Option(names = "--project", paramLabel = "<project>",
                description = "The project (required): its id or its slug.")
        String project;

        @CommandLine.Option(names = "--met", paramLabel = "true|false",
                description = "Only the met (true) or the unmet (false) milestones.")
        String met;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(milestones.options.output);
            String wanted = RepositoriesCommand.required(project, RepositoriesCommand.NAME_THE_PROJECT);
            Boolean only = met(met);
            JsonNode answer = milestones.api(context).projectMilestones(wanted, only);
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            List<JsonNode> all = objects(answer.path("milestones"));
            if (all.isEmpty()) {
                context.out().println("No milestones match.");
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode m : all) {
                rows.add(List.of(WorkEntities.cell(text(m, "id"), 40), MilestonesCommand.met(m) ? "yes" : "no",
                        Table.time(text(m, "satisfiedAt")), criteriaCount(m),
                        WorkEntities.cell(String.join(", ", neededBy(m)), 40),
                        WorkEntities.cell(text(m, "displayName"), 70)));
            }
            Table.print(context.out(), "", List.of("ID", "MET", "SINCE", "CRITERIA", "NEEDED BY", "NAME"), rows);
            return 0;
        }

        /** --met as the service reads it; null when not given. */
        static Boolean met(String given) throws CliFailure {
            if (given == null || given.isBlank()) {
                return null;
            }
            return switch (given.strip().toLowerCase(Locale.ROOT)) {
                case "true", "yes" -> Boolean.TRUE;
                case "false", "no" -> Boolean.FALSE;
                default -> throw new CliFailure("--met is true or false, not " + SafeText.line(given.strip()) + ".",
                        CliFailure.USAGE);
            };
        }
    }

    // --- details ---

    @CommandLine.Command(name = "details", mixinStandardHelpOptions = true,
            description = {"One milestone: its name, whether it is met and since when, its condition (each group "
                    + "and criterion with its id and the date it came to hold, a criterion's evidence or approval), "
                    + "the work items that need it and the campaign memberships it gates.",
                    "Reads GET /projects/api/milestones/{milestone}."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits milestones details --milestone 45a14f8e-f550-45bb-a117-6b34d8c472e3",
                    "  qits milestones --milestone 45a14f8e-f550-45bb-a117-6b34d8c472e3 details -o json",
                    "",
                    "- A group's criteria are marked [x] while they hold and [ ] while they do not. The criterion "
                            + "ids are what approve and a restated criterion take."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The platform refused (for example a milestone it does not know, HTTP 404), or cannot be "
                            + "reached.",
                    "2:Used wrongly (for example no --milestone), not signed in, or the session ended."})
    @TuiCommand(effect = Effect.READ)
    public static class DetailsCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        MilestonesCommand milestones;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(milestones.options.output);
            printAnswer(context.out(), json, milestones.api(context).milestone(milestones.milestone()));
            return 0;
        }
    }

    // --- create ---

    @CommandLine.Command(name = "create", mixinStandardHelpOptions = true,
            description = {"Write a new milestone of a project: its name and its whole condition.",
                    "Reads {\"displayName\":\"...\",\"groups\":[{\"criteria\":[{\"kind\",\"predicate\"}]}]} on stdin "
                            + "and sends it unchanged to POST /projects/api/projects/{project}/milestones. Groups are "
                            + "OR'd, criteria in a group AND'd; at least one group, and no empty group. The "
                            + "milestone is latched from the current state at once, and the command prints it with "
                            + "its id and its criteria's ids.",
                    CRITERION_KINDS,
                    "With nothing on stdin (a terminal, or empty) it sends nothing, prints the payload's JSON schema "
                            + "from the service's OpenAPI document (/projects/q/openapi) and exits with 0."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits milestones create --project qits </dev/null",
                    "  echo '{\"displayName\":\"bootstrap test node\",\"groups\":[{\"criteria\":[{\"kind\":\"APPROVAL\","
                            + "\"predicate\":{}}]}]}' | qits milestones create --project qits",
                    "  echo '{\"displayName\":\"export shipped\",\"groups\":[{\"criteria\":[{\"kind\":\"ENTITY_STATUS\","
                            + "\"predicate\":{\"entityId\":\"qits-101\",\"status\":\"DONE\"}}]}]}' "
                            + "| qits milestones create --project qits -o json",
                    "",
                    "- Then attach it to the items that need it: qits work --entity qits-102 milestones attach "
                            + "--milestone <id>."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The milestone is written, or nothing was put in and the schema is printed.",
                    "1:The platform refused (for example no name, no groups, an empty group, a predicate it cannot "
                            + "read or an ENTITY_STATUS item of another project, HTTP 400, an agent in another "
                            + "project, HTTP 403, or a project it does not know, HTTP 404), or cannot be reached.",
                    "2:Used wrongly (for example no --project, or a payload that is not a JSON object), not signed "
                            + "in, or the session ended."})
    @TuiCommand(input = Input.PAYLOAD)
    public static class CreateCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        MilestonesCommand milestones;

        @Completes(ProjectSource.class)
        @CommandLine.Option(names = "--project", paramLabel = "<project>",
                description = "The project (required unless nothing is put in): its id or its slug.")
        String project;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(milestones.options.output);
            ObjectNode payload = WorkPayload.read(context);
            ProjectsApi api = milestones.api(context);
            if (payload == null) {
                WorkPayload.printSchema(context, api, json, "POST", ProjectsApi.PROJECT_MILESTONES,
                        List.of("application/json"));
                return 0;
            }
            String wanted = RepositoriesCommand.required(project, RepositoriesCommand.NAME_THE_PROJECT);
            printAnswer(context.out(), json, api.createMilestone(wanted, payload));
            return 0;
        }
    }

    // --- rename ---

    @CommandLine.Command(name = "rename", mixinStandardHelpOptions = true,
            description = {"Give a milestone another name.",
                    "Sends {\"displayName\":\"...\"} to PATCH /projects/api/milestones/{milestone}. No criterion, "
                            + "latch or date moves, and no event is sent."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits milestones --milestone 45a14f8e-f550-45bb-a117-6b34d8c472e3 rename "
                    + "--name 'bootstrap test node (arm64)'"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The milestone is renamed.",
                    "1:The platform refused (for example an agent in another project, HTTP 403, or a milestone it "
                            + "does not know, HTTP 404), or cannot be reached.",
                    "2:Used wrongly (for example no --milestone or no --name), not signed in, or the session "
                            + "ended."})
    public static class RenameCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        MilestonesCommand milestones;

        @CommandLine.Option(names = "--name", paramLabel = "<text>", description = "The new name (required).")
        String name;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(milestones.options.output);
            String id = milestones.milestone();
            String newName = RepositoriesCommand.required(name, "Say the new name: --name <text>.");
            printAnswer(context.out(), json, milestones.api(context).renameMilestone(id, newName));
            return 0;
        }
    }

    // --- criteria ---

    @CommandLine.Command(name = "criteria", mixinStandardHelpOptions = true,
            description = {"Replace a milestone's condition, whole.",
                    "Reads {\"groups\":[{\"criteria\":[{\"id\"?,\"kind\",\"predicate\"}]}]} on stdin and sends it "
                            + "unchanged to PUT /projects/api/milestones/{milestone}/groups. What you state is the "
                            + "whole condition afterwards; what you leave out is deleted. Restate a criterion with "
                            + "its id, kind and predicate unchanged to keep whether it already held; a changed or new "
                            + "one starts unlatched from now (an ENTITY_STATUS one is latched from the current state "
                            + "at once). A met milestone that stops holding becomes unmet (MilestoneUnmet); one that "
                            + "comes to hold becomes met (MilestoneMet).",
                    CRITERION_KINDS,
                    "With nothing on stdin (a terminal, or empty) it sends nothing, prints the payload's JSON schema "
                            + "from the service's OpenAPI document (/projects/q/openapi) and exits with 0."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits milestones criteria </dev/null",
                    "  echo '{\"groups\":[{\"criteria\":[{\"id\":\"<criterion id>\",\"kind\":\"APPROVAL\",\"predicate\":{}}]},"
                            + "{\"criteria\":[{\"kind\":\"ENTITY_STATUS\",\"predicate\":{\"entityId\":\"qits-101\","
                            + "\"status\":\"DONE\"}}]}]}' "
                            + "| qits milestones --milestone 45a14f8e-f550-45bb-a117-6b34d8c472e3 criteria",
                    "",
                    "- qits milestones details shows the criterion ids to restate."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The condition is replaced, or nothing was put in and the schema is printed.",
                    "1:The platform refused (for example no groups, an empty group, a predicate it cannot read, a "
                            + "criterion id of another milestone or an ENTITY_STATUS item of another project, HTTP "
                            + "400, an agent in another project, HTTP 403, or a milestone it does not know, HTTP "
                            + "404), or cannot be reached.",
                    "2:Used wrongly (for example no --milestone, or a payload that is not a JSON object), not signed "
                            + "in, or the session ended."})
    @TuiCommand(input = Input.PAYLOAD)
    public static class CriteriaCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        MilestonesCommand milestones;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(milestones.options.output);
            ObjectNode payload = WorkPayload.read(context);
            ProjectsApi api = milestones.api(context);
            if (payload == null) {
                WorkPayload.printSchema(context, api, json, "PUT", ProjectsApi.MILESTONES + "/{milestone}/groups",
                        List.of("application/json"));
                return 0;
            }
            printAnswer(context.out(), json, api.setMilestoneGroups(milestones.milestone(), payload));
            return 0;
        }
    }

    // --- approve ---

    @CommandLine.Command(name = "approve", mixinStandardHelpOptions = true,
            description = {"Say yes to a milestone's APPROVAL criterion.",
                    "Sends {\"note\":\"...\"} ({} without --note) to POST "
                            + "/projects/api/milestones/{milestone}/criteria/{criterion}/approve and prints the "
                            + "milestone. When the approval makes the milestone hold, it is met and the platform "
                            + "sends MilestoneMet."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits milestones --milestone 45a14f8e-f550-45bb-a117-6b34d8c472e3 approve --criterion "
                            + "5f2c0a9e-... --note 'Node 7 is up.'",
                    "",
                    "- A person's to do (qits:admin, signed in), or the project's delivery desk's; an agent is "
                            + "answered HTTP 403."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The criterion is approved.",
                    "1:The platform refused (for example an agent, HTTP 403, no such milestone or criterion, HTTP "
                            + "404, or a criterion that is not APPROVAL or is approved already, HTTP 409), or cannot "
                            + "be reached.",
                    "2:Used wrongly (for example no --milestone or no --criterion), not signed in, or the session "
                            + "ended."})
    public static class ApproveCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        MilestonesCommand milestones;

        @CommandLine.Option(names = "--criterion", paramLabel = "<criterion>",
                description = "The APPROVAL criterion (required): its id, as details prints it.")
        String criterion;

        @CommandLine.Option(names = "--note", paramLabel = "<text>", description = "Why yes. Optional.")
        String note;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(milestones.options.output);
            String id = milestones.milestone();
            String which = RepositoriesCommand.required(criterion,
                    "Name the criterion: --criterion <id>, as `qits milestones details` prints it.");
            printAnswer(context.out(), json, milestones.api(context).approveMilestoneCriterion(id, which, note));
            return 0;
        }
    }

    // --- printing, shared with qits work ---

    /** {@code {"milestone":{…}}}: as JSON, or in full. */
    static void printAnswer(PrintStream out, boolean json, JsonNode answer) throws CliFailure {
        if (json) {
            SafeJson.print(out, answer);
            return;
        }
        printDetails(out, answer.path("milestone"));
    }

    /** One milestone in full: its fields, its condition, the items and memberships that need it. */
    static void printDetails(PrintStream out, JsonNode m) {
        out.println("MILESTONE " + WorkEntities.cell(text(m, "displayName"), 200) + "  ("
                + SafeText.line(text(m, "id")) + ")");
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("met", met(m) ? "yes, since " + Table.time(text(m, "satisfiedAt")) : "no"));
        rows.add(List.of("project", WorkEntities.cell(text(m, "projectId"), 60)));
        rows.add(List.of("created", Table.time(text(m, "createdAt"))));
        Table.print(out, "  ", null, rows);
        condition(m).forEach(out::println);
        List<JsonNode> entities = objects(m.path("entities"));
        if (entities.isEmpty()) {
            out.println("Needed by: no work item.");
        } else {
            out.println("Needed by (" + entities.size() + "):");
            List<List<String>> needed = new ArrayList<>();
            for (JsonNode e : entities) {
                needed.add(List.of(WorkEntities.cell(label(e), 40), WorkEntities.cell(text(e, "archetype"), 9),
                        WorkEntities.cell(text(e, "status"), 30), WorkEntities.cell(text(e, "title"), 70)));
            }
            Table.print(out, "  ", null, needed);
        }
        List<JsonNode> memberships = objects(m.path("memberships"));
        if (!memberships.isEmpty()) {
            out.println("Campaign members (" + memberships.size() + "):");
            List<List<String>> gated = new ArrayList<>();
            for (JsonNode g : memberships) {
                gated.add(List.of(WorkEntities.cell(text(g, "campaignQualifiedId"), 40),
                        WorkEntities.cell(text(g, "entityQualifiedId"), 40),
                        "membership " + WorkEntities.cell(text(g, "membershipId"), 40),
                        text(g, "claimedAt").isEmpty() ? "not claimed" : "claimed " + Table.time(text(g, "claimedAt")),
                        WorkEntities.cell(text(g, "campaignTitle"), 60)));
            }
            Table.print(out, "  ", null, gated);
        }
    }

    /**
     * The condition, a line each: the first group under {@code met when:}, the others under {@code
     * or:}, each criterion two spaces in, {@code [x]} while it holds and {@code [ ]} while it does
     * not, with its id, and under a held one when and why it holds.
     */
    static List<String> condition(JsonNode m) {
        List<String> lines = new ArrayList<>();
        List<JsonNode> groups = objects(m.path("groups"));
        if (groups.isEmpty()) {
            lines.add("Met when: (no criteria)");
            return lines;
        }
        boolean first = true;
        for (JsonNode group : groups) {
            String held = text(group, "satisfiedAt").isEmpty() ? ""
                    : "  (holds since " + Table.time(text(group, "satisfiedAt")) + ")";
            lines.add((first ? "Met when:" : "or:") + held);
            first = false;
            for (JsonNode criterion : group.path("criteria")) {
                boolean holds = !text(criterion, "satisfiedAt").isEmpty();
                lines.add("  " + (holds ? "[x] " : "[ ] ") + WorkMembersCommand.criterion(criterion, Map.of())
                        + "  id " + SafeText.line(text(criterion, "id")));
                String why = latch(criterion);
                if (!why.isEmpty()) {
                    lines.add("      " + why);
                }
            }
        }
        return lines;
    }

    /** When and why a criterion holds: its date, then its approval or its evidence; empty while it does not. */
    private static String latch(JsonNode criterion) {
        if (text(criterion, "satisfiedAt").isEmpty()) {
            return "";
        }
        StringBuilder why = new StringBuilder("since ").append(Table.time(text(criterion, "satisfiedAt")));
        JsonNode approval = criterion.path("approval");
        JsonNode evidence = criterion.path("evidence");
        if (approval.isObject()) {
            why.append(", approved by ").append(WorkEntities.cell(text(approval, "approvedBy"), 60));
            if (!text(approval, "note").isEmpty()) {
                why.append(": ").append(WorkEntities.cell(text(approval, "note"), 200));
            }
        } else if (evidence.isObject()) {
            why.append(", ").append(WorkEntities.cell(text(evidence, "signature"), 40));
            if (!text(evidence, "summary").isEmpty()) {
                why.append(": ").append(WorkEntities.cell(text(evidence, "summary"), 200));
            }
        }
        return why.toString();
    }

    /** Whether the milestone is met: {@code met}, else a {@code satisfiedAt}. */
    static boolean met(JsonNode m) {
        JsonNode met = m.get("met");
        return met != null && met.isBoolean() ? met.asBoolean() : !text(m, "satisfiedAt").isEmpty();
    }

    /** How many criteria hold of how many: {@code 1/2}. */
    private static String criteriaCount(JsonNode m) {
        int all = 0;
        int holding = 0;
        for (JsonNode group : m.path("groups")) {
            for (JsonNode criterion : group.path("criteria")) {
                all++;
                if (!text(criterion, "satisfiedAt").isEmpty()) {
                    holding++;
                }
            }
        }
        return holding + "/" + all;
    }

    /** The qualified ids of the work items that need it. */
    private static List<String> neededBy(JsonNode m) {
        List<String> all = new ArrayList<>();
        m.path("entities").forEach(e -> all.add(label(e)));
        return all;
    }

    /**
     * The names of the milestones in an item's {@code milestones} that are not met, in the order
     * the service lists them: what the item still needs. Empty when it needs nothing, or when a
     * service older than epic qits-615 sends no milestones.
     */
    static List<String> unmet(JsonNode item) {
        List<String> names = new ArrayList<>();
        for (JsonNode m : item.path("milestones")) {
            if (m.isObject() && text(m, "satisfiedAt").isEmpty()) {
                String name = text(m, "displayName");
                names.add(SafeText.line(name.isEmpty() ? text(m, "id") : name));
            }
        }
        return names;
    }

    /** The met ones of an item's {@code milestones}, by name. */
    static List<String> metNames(JsonNode item) {
        List<String> names = new ArrayList<>();
        for (JsonNode m : item.path("milestones")) {
            if (m.isObject() && !text(m, "satisfiedAt").isEmpty()) {
                String name = text(m, "displayName");
                names.add(SafeText.line(name.isEmpty() ? text(m, "id") : name));
            }
        }
        return names;
    }

    /**
     * An item's milestones in brief, {@code {"milestones":[{id, displayName, satisfiedAt}]}}: as
     * JSON, or a row each (id, met since, name).
     */
    static void printBrief(PrintStream out, boolean json, JsonNode answer, String none) throws CliFailure {
        if (json) {
            SafeJson.print(out, answer);
            return;
        }
        List<JsonNode> all = objects(answer.path("milestones"));
        if (all.isEmpty()) {
            out.println(none);
            return;
        }
        List<List<String>> rows = new ArrayList<>();
        for (JsonNode m : all) {
            rows.add(List.of(WorkEntities.cell(text(m, "id"), 40),
                    text(m, "satisfiedAt").isEmpty() ? "no" : "yes", Table.time(text(m, "satisfiedAt")),
                    WorkEntities.cell(text(m, "displayName"), 70)));
        }
        Table.print(out, "", List.of("ID", "MET", "SINCE", "NAME"), rows);
    }

    private static String label(JsonNode e) {
        String qualified = text(e, "qualifiedId");
        return qualified.isEmpty() ? text(e, "id") : qualified;
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
