package eu.wohlben.qits.cli.access.projects;

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
import java.util.List;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

/**
 * {@code qits work comment}: the comment thread of any work entity. A comment's author and body are
 * written by people and agents, so every value goes through {@link SafeText} before a person sees it.
 */
@CommandLine.Command(name = "comment", mixinStandardHelpOptions = true,
        subcommands = {WorkCommentCommand.CreateCommand.class, WorkCommentCommand.UpdateCommand.class},
        description = {"The comment thread of a work entity: create adds a comment, update edits one.",
                "Both read a JSON document on stdin and send it as it is. With nothing on stdin they send nothing "
                        + "and print the payload's JSON schema, with its required fields named."},
        footerHeading = "%nNotes:%n",
        footer = {
                "- The author is the signed-in caller, and an edit leaves it as it is. Anybody who may comment "
                        + "may edit a comment's text.",
                "- Deleting a comment takes qits:admin, and qits has no command for it."})
public class WorkCommentCommand implements Runnable {

    @CommandLine.ParentCommand
    WorkCommand work;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    static final String CREATE_PATH = "/projects/api/entities/{id}/comments";
    static final String UPDATE_PATH = "/projects/api/comments/{commentId}";

    @CommandLine.Command(name = "create", mixinStandardHelpOptions = true,
            description = {"Add a comment to a work entity's thread.",
                    "Reads the payload, a JSON object such as {\"body\":\"...\"}, on stdin and sends it unchanged "
                            + "to POST /projects/api/entities/{id}/comments. The body is Markdown. You are its "
                            + "author. The command prints the comment once filed.",
                    "With nothing on stdin (a terminal, or empty) it sends nothing, prints the payload's JSON schema "
                            + "from the service's OpenAPI document (/projects/q/openapi) with the required fields "
                            + "named, and exits with 0."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  echo '{\"body\":\"I can reproduce it.\"}' | qits work --entity qits-100 comment create",
                    "  jq -n --rawfile b note.md '{body: $b}' | qits work comment create --entity qits-100 -o json",
                    "  qits work --entity qits-100 comment create </dev/null",
                    "",
                    "- The last one prints the schema: nothing is sent.",
                    "- -o json prints the service's answer; the table prints the comment's id, author and time."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The comment is filed, or nothing was put in and the schema is printed.",
                    "1:The platform refused (for example your roles or another project's entity, HTTP 403, an "
                            + "entity it does not know, HTTP 404, or a payload it does not take, HTTP 400), cannot "
                            + "be reached, or its OpenAPI document does not describe the payload.",
                    "2:Used wrongly (for example a payload that is not a JSON object, or no --entity), not signed "
                            + "in, or the session ended."})
    @TuiCommand(input = Input.PAYLOAD)
    public static class CreateCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkCommentCommand comment;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            WorkCommand work = comment.work;
            boolean json = json(work.options.output);
            ObjectNode payload = WorkPayload.read(context);
            if (payload == null) {
                WorkPayload.printSchema(context, ProjectsApi.connect(context, work.options.projectsUrl), json,
                        "POST", CREATE_PATH, List.of("application/json"));
                return 0;
            }
            String entity = work.entity();
            JsonNode answer = ProjectsApi.connect(context, work.options.projectsUrl)
                    .createEntityComment(entity, payload);
            print(context.out(), json, answer, "createdAt", "CREATED");
            return 0;
        }
    }

    @CommandLine.Command(name = "update", mixinStandardHelpOptions = true,
            description = {"Edit a comment on a work entity's thread.",
                    "Reads a JSON merge patch, such as {\"body\":\"...\"}, on stdin and sends it unchanged to PATCH "
                            + "/projects/api/comments/{commentId} as application/merge-patch+json. Before that it "
                            + "reads the entity's thread, and refuses a comment that is not on it. The author stays "
                            + "who it was.",
                    "With nothing on stdin (a terminal, or empty) it sends nothing, prints the patch's JSON schema "
                            + "from the service's OpenAPI document (/projects/q/openapi) with the required fields "
                            + "named, and exits with 0."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  echo '{\"body\":\"Fixed in 2026.929.1.\"}' | qits work --entity qits-100 comment update "
                            + "--comment 8de195f1-631d-420a-893e-7baca2229be4",
                    "  qits work --entity qits-100 comment update </dev/null",
                    "",
                    "- --comment is the comment's full id, as `create` printed it.",
                    "- -o json prints the service's answer; the table prints the comment's id, author and time."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The comment is edited, or nothing was put in and the schema is printed.",
                    "1:The platform refused (for example your roles, HTTP 403, an entity it does not know, HTTP "
                            + "404, or a patch it does not take, HTTP 400), cannot be reached, or its OpenAPI "
                            + "document does not describe the patch.",
                    "2:Used wrongly (for example a patch that is not a JSON object, no --entity or --comment, or a "
                            + "comment that is not on the entity's thread), not signed in, or the session ended."})
    @TuiCommand(input = Input.PAYLOAD)
    public static class UpdateCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkCommentCommand comment;

        @CommandLine.Option(names = "--comment", paramLabel = "<comment>",
                description = "The comment to edit (required unless nothing is put in): its id.")
        String commentId;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            WorkCommand work = comment.work;
            boolean json = json(work.options.output);
            ObjectNode patch = WorkPayload.read(context);
            if (patch == null) {
                WorkPayload.printSchema(context, ProjectsApi.connect(context, work.options.projectsUrl), json,
                        "PATCH", UPDATE_PATH, List.of(ProjectsApi.MERGE_PATCH, "application/json"));
                return 0;
            }
            String entity = work.entity();
            String id = RepositoriesCommand.required(commentId, "Name the comment: --comment <id>.");
            ProjectsApi api = ProjectsApi.connect(context, work.options.projectsUrl);
            boolean onThread = ProjectsApi.entries(api.entityComments(entity), "comment").stream()
                    .anyMatch(c -> id.equals(text(c, "id")));
            if (!onThread) {
                throw new CliFailure("Comment " + SafeText.line(id) + " is not on the thread of " + SafeText.line(entity)
                        + ". Nothing was sent.", CliFailure.USAGE);
            }
            JsonNode answer = api.patchComment(id, patch);
            print(context.out(), json, answer, "updatedAt", "UPDATED");
            return 0;
        }
    }

    /** The answer as JSON, or the comment's id, author and the time that matters for this write. */
    private static void print(PrintStream out, boolean json, JsonNode answer, String timeField, String timeHeader)
            throws CliFailure {
        if (json) {
            SafeJson.print(out, answer);
            return;
        }
        JsonNode c = answer.path("comment");
        Table.print(out, "", List.of("ID", "AUTHOR", timeHeader),
                List.of(List.of(cell(text(c, "id")), cell(text(c, "author")), Table.time(text(c, timeField)))));
    }

    private static String cell(String value) {
        return Table.cell(SafeText.line(value), 80);
    }
}
