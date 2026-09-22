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
import eu.wohlben.qits.cli.access.complete.ProjectSource;
import eu.wohlben.qits.cli.access.complete.TicketSource;
import eu.wohlben.qits.cli.tui.api.Completes;
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
 * The tickets of one project. A ticket's title, description and comments are written by people and
 * agents, so, like a CI step's output, they are untrusted text: every value goes through {@link
 * SafeText} before a person sees it, and the JSON form writes control characters as escapes.
 */
@CommandLine.Command(name = "ticket", mixinStandardHelpOptions = true,
        subcommands = {TicketCommand.ListCommand.class, TicketCommand.NewCommand.class, TicketCommand.DetailsCommand.class,
                TicketCommand.CommentCommand.class},
        description = {"The tickets of one project: small pieces of work, each a bug or an improvement. list shows "
                        + "them, new files one, details shows one with its description and comments, and comment "
                        + "adds one to its thread.",
                "Types: BUG (something behaves other than it should) and IMPROVEMENT (something works and could work "
                        + "better).",
                "Statuses: REPORTED (somebody said what is wrong or could be better, and nothing more; a new ticket "
                        + "starts here), REFINED (it now says what to do, and is ready to be picked up), IMPLEMENTED "
                        + "(the change is released and deployed, not merely merged), VERIFIED (somebody checked the "
                        + "platform and it no longer occurs), DONE (closed, which is a person's call). DROPPED is the "
                        + "exit for work a decision was taken not to do.",
                "A ticket is blocked when the phase its status belongs to cannot proceed. It is temporary: any "
                        + "transition clears it."},
        footerHeading = "%nNotes:%n",
        footer = {
                "- --project, --output and --projects-url may come before or after the command. So may --ticket, "
                        + "which `details` and `comment` take.",
                "- Reading tickets needs the role qits:admin or qits:agent. Filing one and commenting need "
                        + "qits:admin.",
                "- The reporter and the comment author are the signed-in caller. Nobody can file a ticket or "
                        + "comment as somebody else.",
                "- Work that needs a plan is an epic, not a ticket. qits does not resolve or edit a ticket yet."})
public class TicketCommand implements Runnable {

    static final String NAME_THE_TICKET = "Name the ticket: --ticket <id, slug or the start of the id>.";

    @CommandLine.Mixin
    ProjectsOptions options;

    @Completes(ProjectSource.class)
    @CommandLine.Option(names = "--project", paramLabel = "<project>", scope = CommandLine.ScopeType.INHERIT,
            description = "The project: its id, slug or name.")
    String project;

    /** Only so that --ticket may also come before `details`; list and new refuse it. */
    @Completes(TicketSource.class)
    @CommandLine.Option(names = "--ticket", paramLabel = "<ticket>",
            description = "The ticket, for details: its id, its slug, or the start of its id.")
    String ticket;

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

    private void noTicket(String command) throws CliFailure {
        if (ticket != null) {
            throw new CliFailure("--ticket is for `qits ticket details`, not `qits ticket " + command + "`.",
                    CliFailure.USAGE);
        }
    }

    /**
     * The project's ticket whose id or slug is {@code wanted}, else whose id starts with it. It reads
     * every ticket of the project. The service finds a ticket by its id alone, so this lookup is also
     * what keeps the command off another project's ticket.
     */
    private static JsonNode find(Scope scope, String wanted) throws CliFailure, InterruptedException {
        List<JsonNode> all = ProjectsApi.entries(scope.api().tickets(scope.projectId(), null), "ticket");
        List<JsonNode> matches = all.stream()
                .filter(t -> wanted.equals(text(t, "id")) || wanted.equals(text(t, "slug")))
                .toList();
        if (matches.isEmpty()) {
            String start = wanted.toLowerCase(Locale.ROOT);
            matches = all.stream().filter(t -> text(t, "id").toLowerCase(Locale.ROOT).startsWith(start)).toList();
        }
        if (matches.isEmpty()) {
            throw new CliFailure("Project " + scope.label() + " has no ticket with the id or slug '" + wanted
                    + "', and no ticket id starts with it. `qits ticket --project " + scope.label()
                    + " list` shows them.", CliFailure.USAGE);
        }
        if (matches.size() > 1) {
            throw new CliFailure("'" + wanted + "' fits more than one ticket of project " + scope.label() + ": "
                    + matches.stream().map(t -> cell(text(t, "id"), 64) + " (" + cell(text(t, "title"), 40) + ")")
                            .collect(Collectors.joining(", "))
                    + ". Give more of the id.", CliFailure.USAGE);
        }
        return matches.getFirst();
    }

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = {"List the project's tickets, oldest first: id, type, status, title, the assignee when "
                    + "a ticket has one, and BLOCKED when one is blocked.",
                    "Without --status and --type it lists every ticket. The ID column shows the first 8 characters of "
                            + "the id, which is enough for `details`."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits ticket --project qits list",
                    "  qits ticket --project qits list --status REFINED --type BUG",
                    "  qits ticket list --project qits -o json",
                    "",
                    "- The service applies --status, and refuses a status it does not know (HTTP 400) rather than "
                            + "answer with no tickets. --type is applied here, in both output forms."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class ListCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        TicketCommand parent;

        @CommandLine.Option(names = "--status", paramLabel = "<STATUS>",
                description = "Only the tickets in this status: REPORTED, REFINED, IMPLEMENTED, VERIFIED, DONE or "
                        + "DROPPED. Default: every status.")
        String status;

        @CommandLine.Option(names = "--type", paramLabel = "<TYPE>",
                description = "Only the tickets of this type: BUG or IMPROVEMENT. Default: every type.")
        String type;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.options.output);
            parent.noTicket("list");
            String wantedStatus = upper(status);
            String wantedType = upper(type);
            Scope scope = parent.scope(context);
            JsonNode answer;
            try {
                answer = scope.api().tickets(scope.projectId(), wantedStatus);
            } catch (CliFailure refused) {
                if (refused.status() == 400 && wantedStatus != null) {
                    throw new CliFailure("No ticket status is called '" + wantedStatus + "' (HTTP 400). "
                            + "Statuses: REPORTED, REFINED, IMPLEMENTED, VERIFIED, DONE, DROPPED.",
                            CliFailure.FAILED);
                }
                throw refused;
            }
            if (wantedType != null) {
                answer = onlyType(answer, wantedType);
            }
            if (json) {
                printJson(context.out(), answer);
                return 0;
            }
            List<JsonNode> tickets = ProjectsApi.entries(answer, "ticket");
            if (tickets.isEmpty()) {
                String which = (wantedStatus == null ? "" : wantedStatus + " ") + (wantedType == null ? "" : wantedType + " ");
                context.out().println("No " + which + "tickets in project " + scope.label() + ".");
                return 0;
            }
            printTickets(context.out(), tickets);
            return 0;
        }

        /** The service has no type filter, so the other types are dropped here, in both output forms. */
        static JsonNode onlyType(JsonNode answer, String type) {
            ObjectNode copy = answer.isObject() ? ((ObjectNode) answer).deepCopy() : JsonNodeFactory.instance.objectNode();
            ArrayNode kept = copy.arrayNode();
            answer.path("entries").forEach(e -> {
                if (type.equalsIgnoreCase(text(e.path("ticket"), "type"))) {
                    kept.add(e);
                }
            });
            copy.set("entries", kept);
            return copy;
        }
    }

    @CommandLine.Command(name = "new", mixinStandardHelpOptions = true,
            description = {"File a ticket in the project: a bug or an improvement.",
                    "The ticket starts REPORTED, and you are its reporter. The command prints the new ticket the way "
                            + "`details` does."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits ticket --project qits new --type BUG --title \"The log view stops at 64 KiB\"",
                    "  qits ticket --project qits new --type IMPROVEMENT --title \"Filter runs by author\" "
                            + "--description \"The runs list needs an author filter.\"",
                    "  qits ticket new --project qits --type BUG --title \"Login loops\" --description-file report.md",
                    "  cat report.md | qits ticket --project qits new --type BUG --title \"Login loops\" "
                            + "--description-file -",
                    "",
                    "- --type is required: the platform takes no ticket that is neither a bug nor an improvement.",
                    "- The description is Markdown. --description-file - reads it from stdin."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The ticket is filed.",
                    "1:The platform refused (for example a type it does not know, HTTP 400, or your roles, HTTP 403), "
                            + "or cannot be reached.",
                    "2:Used wrongly (for example an empty title, or a description file that cannot be read), not "
                            + "signed in, or the session ended."})
    public static class NewCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        TicketCommand parent;

        @CommandLine.Option(names = "--title", paramLabel = "<text>", required = true,
                description = "What is wrong or could be better, in a short line.")
        String title;

        @CommandLine.Option(names = "--type", paramLabel = "<TYPE>", required = true,
                description = "BUG or IMPROVEMENT.")
        String type;

        @CommandLine.Option(names = "--description", paramLabel = "<text>",
                description = "The long form, in Markdown: what happens, what should happen, how to see it.")
        String description;

        @CommandLine.Option(names = "--description-file", paramLabel = "<path>",
                description = "Read the description from this file (UTF-8), or from stdin for -. Not together with "
                        + "--description.")
        String descriptionFile;

        @CommandLine.Option(names = "--assignee", paramLabel = "<name>",
                description = "Who takes it, as a name. Default: nobody.")
        String assignee;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.options.output);
            parent.noTicket("new");
            if (title.isBlank() || type.isBlank()) {
                throw new CliFailure("--title and --type must not be empty.", CliFailure.USAGE);
            }
            String body = description(context);
            Scope scope = parent.scope(context);
            JsonNode answer;
            try {
                answer = scope.api().createTicket(scope.projectId(), title.strip(), upper(type), body, assignee);
            } catch (CliFailure refused) {
                if (refused.status() == 400) {
                    throw new CliFailure(refused.getMessage() + ". --type is BUG or IMPROVEMENT.", CliFailure.FAILED);
                }
                throw refused;
            }
            if (json) {
                printJson(context.out(), answer);
                return 0;
            }
            printTicket(context.out(), answer.path("ticket"), null);
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
            description = {"Show one ticket: id, slug, type, status, whether it is blocked, title, assignee, who "
                    + "created it and when, its description, and its comments, the oldest first.",
                    "--ticket takes the ticket's id, its slug, or the start of its id (list shows 8 characters). "
                            + "Terminal control characters are taken out of the text."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits ticket --project qits details --ticket 4f2a91c0",
                    "  qits ticket details --ticket the-log-view-stops-at-64-kib --project qits",
                    "  qits ticket --project qits details --ticket 4f2a91c0 -o json | jq -r .ticket.description",
                    "",
                    "- -o json prints one object: the ticket as the service answers it, and its comments as a list. "
                            + "Control characters are written as escapes."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The platform refused (for example the ticket was deleted a moment ago, HTTP 404), or cannot be "
                            + "reached.",
                    "2:Used wrongly (for example a --ticket that fits no ticket of the project, or more than one), not "
                            + "signed in, or the session ended."})
    public static class DetailsCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        TicketCommand parent;

        @Completes(TicketSource.class)
        @CommandLine.Option(names = "--ticket", paramLabel = "<ticket>",
                description = "The ticket (required, before or after details): its id, its slug, or enough of the "
                        + "start of its id to name one.")
        String ticket;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.options.output);
            String wanted = RepositoriesCommand.required(ticket != null ? ticket : parent.ticket, NAME_THE_TICKET);
            Scope scope = parent.scope(context);
            String id = text(find(scope, wanted), "id");
            JsonNode one;
            JsonNode comments;
            try {
                one = scope.api().ticket(id);
                comments = scope.api().ticketComments(id);
            } catch (CliFailure refused) {
                if (refused.status() == 404) {
                    throw new CliFailure("No such ticket: " + id + " (HTTP 404). It may have been deleted a moment ago.",
                            CliFailure.FAILED);
                }
                throw refused;
            }
            JsonNode found = one.has("ticket") ? one.get("ticket") : one;
            List<JsonNode> thread = ProjectsApi.entries(comments, "comment");
            if (json) {
                ObjectNode both = JsonNodeFactory.instance.objectNode();
                both.set("ticket", found);
                ArrayNode list = both.putArray("comments");
                thread.forEach(list::add);
                printJson(context.out(), both);
                return 0;
            }
            printTicket(context.out(), found, thread);
            return 0;
        }
    }

    @CommandLine.Command(name = "comment", mixinStandardHelpOptions = true,
            description = {"Add a comment to a ticket's thread.",
                    "You are its author. The comment is Markdown; the command prints it once filed."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits ticket --project qits comment --ticket 4f2a91c0 --body \"I can reproduce it.\"",
                    "  qits ticket comment --ticket the-log-view-stops-at-64-kib --project qits --body-file note.md",
                    "  cat note.md | qits ticket --project qits comment --ticket 4f2a91c0 --body-file -",
                    "",
                    "- The comment is Markdown. --body-file - reads it from stdin."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The platform refused (for example your roles, HTTP 403, or the ticket was deleted a moment "
                            + "ago, HTTP 404), or cannot be reached.",
                    "2:Used wrongly (for example neither --body nor --body-file given, an empty comment, or a "
                            + "--ticket that fits no ticket of the project, or more than one), not signed in, or "
                            + "the session ended."})
    public static class CommentCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        TicketCommand parent;

        @Completes(TicketSource.class)
        @CommandLine.Option(names = "--ticket", paramLabel = "<ticket>",
                description = "The ticket (required, before or after comment): its id, its slug, or enough of the "
                        + "start of its id to name one.")
        String ticket;

        @CommandLine.Option(names = "--body", paramLabel = "<text>",
                description = "The comment, in Markdown.")
        String body;

        @CommandLine.Option(names = "--body-file", paramLabel = "<path>",
                description = "Read the comment from this file (UTF-8), or from stdin for -. Not together with "
                        + "--body.")
        String bodyFile;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.options.output);
            String wanted = RepositoriesCommand.required(ticket != null ? ticket : parent.ticket, NAME_THE_TICKET);
            String commentBody = body(context);
            Scope scope = parent.scope(context);
            String id = text(find(scope, wanted), "id");
            JsonNode answer;
            try {
                answer = scope.api().createTicketComment(id, commentBody);
            } catch (CliFailure refused) {
                if (refused.status() == 404) {
                    throw new CliFailure("No such ticket: " + id + " (HTTP 404). It may have been deleted a moment ago.",
                            CliFailure.FAILED);
                }
                throw refused;
            }
            if (json) {
                printJson(context.out(), answer);
                return 0;
            }
            printComment(context.out(), answer.path("comment"));
            return 0;
        }

        /** The text of --body or --body-file, without trailing blanks; never blank. */
        private String body(CliContext context) throws CliFailure {
            if (body != null && bodyFile != null) {
                throw new CliFailure("Give --body or --body-file, not both.", CliFailure.USAGE);
            }
            if (body == null && bodyFile == null) {
                throw new CliFailure("Give --body or --body-file.", CliFailure.USAGE);
            }
            String text = bodyFile != null ? (bodyFile.equals("-") ? stdin(context) : file(bodyFile)) : body;
            if (text == null || text.isBlank()) {
                throw new CliFailure("The comment is empty.", CliFailure.USAGE);
            }
            return text.stripTrailing();
        }

        private static String stdin(CliContext context) throws CliFailure {
            try {
                return new String(context.in().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new CliFailure("Cannot read the comment from stdin: " + e.getMessage(), CliFailure.USAGE);
            }
        }

        private static String file(String path) throws CliFailure {
            try {
                return Files.readString(Path.of(path), StandardCharsets.UTF_8);
            } catch (NoSuchFileException e) {
                throw new CliFailure("There is no file " + path + " (--body-file).", CliFailure.USAGE);
            } catch (CharacterCodingException e) {
                throw new CliFailure("The file " + path + " is not UTF-8 text (--body-file).", CliFailure.USAGE);
            } catch (IOException | InvalidPathException e) {
                throw new CliFailure("Cannot read " + path + " (--body-file): " + e.getMessage(), CliFailure.USAGE);
            }
        }
    }

    /** One comment, on its own: when it was written, by whom, and its body. */
    static void printComment(PrintStream out, JsonNode comment) {
        out.println(Table.time(text(comment, "createdAt")) + "  " + cell(text(comment, "author"), 80));
        lines(out, "  ", text(comment, "body"));
    }

    static void printTickets(PrintStream out, List<JsonNode> tickets) {
        boolean assignees = tickets.stream().anyMatch(t -> !text(t, "assignee").isBlank());
        // A column of its own rather than a mark on the status, so a blocked ticket reads the same
        // width as any other; it appears only when one is blocked, the way ASSIGNEE does.
        boolean anyBlocked = tickets.stream().anyMatch(TicketCommand::blocked);
        List<String> headers = new ArrayList<>(List.of("ID", "TYPE", "STATUS"));
        if (anyBlocked) {
            headers.add("BLOCKED");
        }
        headers.add("TITLE");
        if (assignees) {
            headers.add("ASSIGNEE");
        }
        Table.print(out, "", headers, tickets.stream().map(t -> {
            List<String> row = new ArrayList<>(List.of(
                    cell(ReleaseRequestCommand.shortId(text(t, "id")), 8),
                    cell(text(t, "type"), 12),
                    // 11, so that IMPLEMENTED, the longest status, is not cut to IMPLEMENTE…
                    cell(text(t, "status"), 11)));
            if (anyBlocked) {
                row.add(blocked(t) ? "yes" : "-");
            }
            row.add(cell(text(t, "title"), 70));
            if (assignees) {
                row.add(cell(text(t, "assignee"), 30));
            }
            return row;
        }).toList());
    }

    /** Blocked means the phase the ticket's status belongs to cannot proceed. An older service sends no field. */
    private static boolean blocked(JsonNode ticket) {
        return ticket.path("blocked").asBoolean(false);
    }

    /** One ticket; its comments too, unless {@code comments} is null (a ticket just filed has none). */
    static void printTicket(PrintStream out, JsonNode ticket, List<JsonNode> comments) {
        out.println("Ticket " + SafeText.line(text(ticket, "id")));
        List<List<String>> rows = new ArrayList<>(List.of(
                row("slug", text(ticket, "slug")),
                row("type", text(ticket, "type")),
                row("status", text(ticket, "status")),
                row("blocked", blocked(ticket) ? "yes" : "no"),
                row("title", text(ticket, "title")),
                row("assignee", text(ticket, "assignee")),
                row("created by", text(ticket, "createdBy")),
                List.of("created", Table.time(text(ticket, "createdAt"))),
                List.of("updated", Table.time(text(ticket, "updatedAt")))));
        List<String> workspaces = new ArrayList<>();
        ticket.path("workspaces").forEach(w -> workspaces.add(text(w, "workspaceId") + " on " + text(w, "branch")));
        if (!workspaces.isEmpty()) {
            rows.add(row("workspaces", String.join(", ", workspaces)));
        }
        Table.print(out, "  ", null, rows);
        out.println("Description:");
        String description = text(ticket, "description");
        if (description.isBlank()) {
            out.println("  (none)");
        } else {
            lines(out, "  ", description);
        }
        if (comments == null) {
            return;
        }
        if (comments.isEmpty()) {
            out.println("Comments: none.");
            return;
        }
        out.println("Comments (" + comments.size() + "):");
        for (JsonNode comment : comments) {
            out.println("  " + Table.time(text(comment, "createdAt")) + "  " + cell(text(comment, "author"), 80));
            lines(out, "    ", text(comment, "body"));
        }
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

    /** The service reads type and status names in capitals only. */
    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.strip().toUpperCase(Locale.ROOT);
    }
}
