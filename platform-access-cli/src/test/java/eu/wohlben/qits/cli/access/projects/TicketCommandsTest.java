package eu.wohlben.qits.cli.access.projects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cli.access.AccessCli;
import eu.wohlben.qits.cli.access.FakeIdp;
import eu.wohlben.qits.cli.access.FakePlatform;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.session.Session;
import eu.wohlben.qits.cli.access.session.SessionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** qits ticket, as a person types it, in this process, against a fake platform and a fake idp. */
class TicketCommandsTest {

    private static final Instant T0 = Instant.parse("2026-09-12T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String QITS = "8f1c2d3e-0000-4000-8000-000000000001";
    private static final String TICKETS = "/projects/api/projects/" + QITS + "/tickets";
    private static final String LOG = "aaaa1111-0000-4000-8000-000000000001";
    private static final String FILTER = "aaaa2222-0000-4000-8000-000000000002";
    private static final String EVIL = "bbbb3333-0000-4000-8000-000000000003";
    private static final String NEW = "cccc4444-0000-4000-8000-000000000004";
    private static final char ESC = 0x1B;

    @TempDir
    Path home;

    private FakeIdp idp;
    private FakePlatform platform;
    private FakeTime time;
    private final Map<String, String> env = new HashMap<>();
    private final ByteArrayOutputStream allOut = new ByteArrayOutputStream();
    private final ByteArrayOutputStream allErr = new ByteArrayOutputStream();

    record Result(int exit, String out, String err) {
    }

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdp();
        platform = new FakePlatform();
        time = new FakeTime(T0);
        SessionFile store = new SessionFile(home.resolve("qits"));
        env.put("XDG_CONFIG_HOME", home.toString());
        env.put("QITS_PROJECTS_URL", platform.url());
        store.write(new Session(idp.url(), "qits-cli", FakeIdp.SECRET + "access-0", T0.plus(Duration.ofMinutes(10)),
                idp.issueRefreshToken(), T0.plus(Duration.ofDays(30))));

        platform.answer("GET", "/projects/api/projects", """
                {"entries":[{"project":{"id":"%s","name":"qits platform","slug":"qits"}}]}
                """.formatted(QITS));
        // The third title and the description carry escape sequences, as anyone who files a ticket can write.
        platform.answer("GET", TICKETS, """
                {"entries":[
                  {"ticket":{"id":"%s","projectId":"%s","title":"Log view stops\\nat 64 KiB","slug":"log-view-stops",
                    "type":"BUG","status":"REPORTED","assignee":null,"createdBy":"wohlben","description":"x",
                    "createdAt":"2026-09-12T09:00:00Z","updatedAt":"2026-09-12T09:00:00Z","workspaces":[]}},
                  {"ticket":{"id":"%s","projectId":"%s","title":"Filter runs by author","slug":"filter-runs-by-author",
                    "type":"IMPROVEMENT","status":"IMPLEMENTED","assignee":"alice","createdBy":"wohlben","description":null,
                    "blocked":false,
                    "createdAt":"2026-09-12T09:10:00Z","updatedAt":"2026-09-12T09:20:00Z","workspaces":[]}},
                  {"ticket":{"id":"%s","projectId":"%s","title":"Evil \\u001b]0;pwned\\u0007title","slug":"evil-title",
                    "type":"BUG","status":"REPORTED","assignee":null,"createdBy":null,"description":null,
                    "createdAt":"2026-09-12T09:30:00Z","updatedAt":"2026-09-12T09:30:00Z","workspaces":[]}}]}
                """.formatted(LOG, QITS, FILTER, QITS, EVIL, QITS));
        platform.answer("GET", "/projects/api/tickets/" + LOG, """
                {"ticket":{"id":"%s","projectId":"%s","title":"Log view stops at 64 KiB","slug":"log-view-stops",
                  "type":"BUG","status":"REPORTED","assignee":null,"createdBy":"wohlben",
                  "description":"It stops.\\n\\u001b[31mRed\\u001b[0m text\\u202e here\\r\\nThird line",
                  "createdAt":"2026-09-12T09:00:00Z","updatedAt":"2026-09-12T09:00:00Z",
                  "workspaces":[{"workspaceRowId":7,"repositoryId":"r1","workspaceId":"ws-7","branch":"external/fix-log"}]}}
                """.formatted(LOG, QITS));
        platform.answer("GET", "/projects/api/tickets/" + LOG + "/comments", """
                {"entries":[
                  {"comment":{"id":"c1","ticketId":"%s","author":"alice","body":"I can \\u001b[2Jreproduce it",
                    "createdAt":"2026-09-12T09:05:00Z","updatedAt":"2026-09-12T09:05:00Z"}},
                  {"comment":{"id":"c2","ticketId":"%s","author":null,"body":"Two\\nlines",
                    "createdAt":"2026-09-12T09:06:00Z","updatedAt":"2026-09-12T09:06:00Z"}}]}
                """.formatted(LOG, LOG));
        platform.answer("POST", TICKETS, """
                {"ticket":{"id":"%s","projectId":"%s","title":"The log view stops","slug":"the-log-view-stops",
                  "type":"BUG","status":"REPORTED","assignee":null,"createdBy":"wohlben","description":null,
                  "createdAt":"2026-09-12T10:00:00Z","updatedAt":"2026-09-12T10:00:00Z","workspaces":[]}}
                """.formatted(NEW, QITS));
        platform.answer("POST", "/projects/api/tickets/" + LOG + "/comments", """
                {"comment":{"id":"c3","ticketId":"%s","author":"wohlben","body":"I can \\u001b[2Jreproduce it too",
                  "createdAt":"2026-09-12T10:00:00Z","updatedAt":"2026-09-12T10:00:00Z"}}
                """.formatted(LOG));
    }

    @AfterEach
    void stop() {
        platform.close();
        idp.close();
        assertThat(allOut.toString(StandardCharsets.UTF_8)).doesNotContain(FakeIdp.SECRET);
        assertThat(allErr.toString(StandardCharsets.UTF_8)).doesNotContain(FakeIdp.SECRET);
    }

    private Result run(String... args) {
        return runWithInput(InputStream.nullInputStream(), args);
    }

    private Result runWithInput(InputStream in, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliContext context = new CliContext(Map.copyOf(env), in,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), time, time, TokenClient::new, stop -> { });
        CommandLine cli = new CommandLine(new AccessCli(), new CommandLine.IFactory() {
            @Override
            public <K> K create(Class<K> type) throws Exception {
                K made = CommandLine.defaultFactory().create(type);
                if (made instanceof PlatformCommand command) {
                    command.useContext(context);
                }
                return made;
            }
        });
        cli.setOut(new PrintWriter(out, true, StandardCharsets.UTF_8));
        cli.setErr(new PrintWriter(err, true, StandardCharsets.UTF_8));
        int exit = cli.execute(args);
        allOut.writeBytes(out.toByteArray());
        allErr.writeBytes(err.toByteArray());
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private JsonNode sentTicket() throws Exception {
        return JSON.readTree(platform.requests("POST", TICKETS).getFirst().body());
    }

    private static String listLine(String id, String type, String status, String title, String assignee) {
        return String.format("%-10s%-13s%-13s%-26s%s", id, type, status, title, assignee);
    }

    /** The same table with the BLOCKED column, which only a blocked ticket brings. */
    private static String blockedLine(String id, String type, String status, String blocked, String title) {
        return String.format("%-10s%-13s%-13s%-9s%s", id, type, status, blocked, title);
    }

    // --- list ---

    @Test
    void listIsATableWithTheAssigneeColumnWhenATicketHasOne() {
        Result r = run("ticket", "--project", "qits", "list");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out().lines().toList()).containsExactly(
                listLine("ID", "TYPE", "STATUS", "TITLE", "ASSIGNEE"),
                listLine("aaaa1111", "BUG", "REPORTED", "Log view stops at 64 KiB", "-"),
                listLine("aaaa2222", "IMPROVEMENT", "IMPLEMENTED", "Filter runs by author", "alice"),
                listLine("bbbb3333", "BUG", "REPORTED", "Evil title", "-"));
        // The longest status fits: no ticket is shown as IMPLEMENTE…
        assertThat(r.out()).contains("IMPLEMENTED").doesNotContain("\u2026");
        assertThat(platform.requests("GET", TICKETS).getFirst().query()).isNull();
        assertThat(platform.requests("GET", TICKETS).getFirst().authorization())
                .isEqualTo("Bearer " + FakeIdp.SECRET + "access-0");
    }

    @Test
    void theStatusGoesToTheServiceAndTheTypeIsAppliedHere() {
        Result r = run("ticket", "--project", "qits", "list", "--status", "reported", "--type", "bug");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("GET", TICKETS).getFirst().query()).isEqualTo("status=REPORTED");
        // No ticket left has an assignee, so the column goes.
        assertThat(r.out().lines().toList()).containsExactly(
                "ID        TYPE  STATUS    TITLE",
                "aaaa1111  BUG   REPORTED  Log view stops at 64 KiB",
                "bbbb3333  BUG   REPORTED  Evil title");
    }

    @Test
    void jsonOutputLeavesTheOtherTypesOutAndEscapesControlCharacters() throws Exception {
        Result r = run("ticket", "list", "--project", "qits", "--type", "BUG", "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(JSON.readTree(r.out()).path("entries")).extracting(e -> e.path("ticket").path("id").asText())
                .containsExactly(LOG, EVIL);
        assertThat(r.out()).doesNotContain(String.valueOf(ESC)).containsIgnoringCase("\\u001b]0;pwned");
        assertThat(JSON.readTree(r.out()).path("entries").get(1).path("ticket").path("title").asText())
                .isEqualTo("Evil " + ESC + "]0;pwned" + (char) 7 + "title");
    }

    @Test
    void anEmptyListSaysWhatWasAskedFor() {
        assertThat(run("ticket", "--project", "qits", "list", "--type", "chore").out())
                .isEqualTo("No CHORE tickets in project qits.\n");
        platform.answer("GET", TICKETS, "{\"entries\":[]}");
        assertThat(run("ticket", "--project", "qits", "list").out()).isEqualTo("No tickets in project qits.\n");
        assertThat(run("ticket", "--project", "qits", "list", "--status", "DROPPED", "--type", "BUG").out())
                .isEqualTo("No DROPPED BUG tickets in project qits.\n");
    }

    @Test
    void aStatusTheServiceDoesNotKnowIsRefused() {
        platform.answer("GET", TICKETS, 400, "{\"message\":\"Unknown ticket status: REPRTED\"}");

        Result r = run("ticket", "--project", "qits", "list", "--status", "reprted");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("No ticket status is called 'REPRTED' (HTTP 400). "
                + "Statuses: REPORTED, REFINED, IMPLEMENTED, VERIFIED, DONE, DROPPED.");
        assertThat(r.out()).isEmpty();
    }

    @Test
    void aBlockedTicketBringsItsColumnAndADroppedOneIsListed() {
        platform.answer("GET", TICKETS, """
                {"entries":[
                  {"ticket":{"id":"%s","title":"Log view stops at 64 KiB","slug":"log-view-stops",
                    "type":"BUG","status":"REPORTED","assignee":null,"createdBy":"wohlben"}},
                  {"ticket":{"id":"%s","title":"Filter runs by author","slug":"filter-runs-by-author",
                    "type":"IMPROVEMENT","status":"IMPLEMENTED","blocked":true,"assignee":null,"createdBy":"wohlben"}},
                  {"ticket":{"id":"%s","title":"Evil title","slug":"evil-title",
                    "type":"BUG","status":"DROPPED","blocked":false,"assignee":null,"createdBy":null}}]}
                """.formatted(LOG, FILTER, EVIL));

        Result r = run("ticket", "--project", "qits", "list");

        assertThat(r.exit()).as(r.err()).isZero();
        // The first ticket carries no blocked field at all, and reads as not blocked.
        assertThat(r.out().lines().toList()).containsExactly(
                blockedLine("ID", "TYPE", "STATUS", "BLOCKED", "TITLE"),
                blockedLine("aaaa1111", "BUG", "REPORTED", "-", "Log view stops at 64 KiB"),
                blockedLine("aaaa2222", "IMPROVEMENT", "IMPLEMENTED", "yes", "Filter runs by author"),
                blockedLine("bbbb3333", "BUG", "DROPPED", "-", "Evil title"));
        assertThat(r.out()).doesNotContain("null");
    }

    @Test
    void noBlockedTicketMeansNoBlockedColumn() {
        Result r = run("ticket", "--project", "qits", "list");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).doesNotContain("BLOCKED").doesNotContain("null");
    }

    // --- new ---

    @Test
    void newSendsTitleAndTypeAndPrintsTheTicket() throws Exception {
        Result r = run("ticket", "--project", "qits", "new", "--title", " The log view stops ", "--type", "bug");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(sentTicket()).isEqualTo(JSON.readTree("{\"title\":\"The log view stops\",\"type\":\"BUG\"}"));
        assertThat(r.out()).startsWith("Ticket " + NEW + "\n")
                .contains("  slug        the-log-view-stops\n")
                .contains("  type        BUG\n")
                .contains("  status      REPORTED\n")
                .contains("  blocked     no\n")
                .contains("  assignee    -\n")
                .contains("  created by  wohlben\n")
                .contains("Description:\n  (none)\n")
                .doesNotContain("Comments");
        assertThat(platform.requests).noneMatch(req -> req.path().startsWith("/projects/api/tickets/"));
    }

    @Test
    void newSendsTheDescriptionAndTheAssignee() throws Exception {
        Result r = run("ticket", "new", "--project", "qits", "--type", "IMPROVEMENT", "--title", "Filter runs",
                "--description", "The runs list needs an author filter.", "--assignee", " alice ", "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(sentTicket()).isEqualTo(JSON.readTree("{\"title\":\"Filter runs\",\"type\":\"IMPROVEMENT\","
                + "\"description\":\"The runs list needs an author filter.\",\"assignee\":\"alice\"}"));
        assertThat(JSON.readTree(r.out()).path("ticket").path("id").asText()).isEqualTo(NEW);
    }

    @Test
    void newReadsTheDescriptionFromAFile() throws Exception {
        Path report = home.resolve("report.md");
        Files.writeString(report, "Steps:\n1. open the log view\n\n", StandardCharsets.UTF_8);

        Result r = run("ticket", "--project", "qits", "new", "--type", "BUG", "--title", "Log", "--description-file",
                report.toString());

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(sentTicket().path("description").asText()).isEqualTo("Steps:\n1. open the log view");
    }

    @Test
    void newReadsTheDescriptionFromStdin() throws Exception {
        Result r = runWithInput(new ByteArrayInputStream("From a pipe\n".getBytes(StandardCharsets.UTF_8)),
                "ticket", "--project", "qits", "new", "--type", "BUG", "--title", "Log", "--description-file", "-");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(sentTicket().path("description").asText()).isEqualTo("From a pipe");
    }

    @Test
    void anEmptyDescriptionFileSendsNoDescription() throws Exception {
        Path empty = home.resolve("empty.md");
        Files.writeString(empty, "\n  \n", StandardCharsets.UTF_8);

        Result r = run("ticket", "--project", "qits", "new", "--type", "BUG", "--title", "Log", "--description-file",
                empty.toString());

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(sentTicket().has("description")).isFalse();
    }

    @Test
    void newIsUsedWronglyWithoutCallingThePlatform() {
        Path missing = home.resolve("missing.md");
        assertThat(run("ticket", "--project", "qits", "new", "--type", "BUG").exit()).isEqualTo(2);
        assertThat(run("ticket", "--project", "qits", "new", "--title", "Log").exit()).isEqualTo(2);

        Result blank = run("ticket", "--project", "qits", "new", "--type", "BUG", "--title", "  ");
        assertThat(blank.exit()).isEqualTo(2);
        assertThat(blank.err()).contains("--title and --type must not be empty.");

        Result both = run("ticket", "--project", "qits", "new", "--type", "BUG", "--title", "Log",
                "--description", "a", "--description-file", "-");
        assertThat(both.exit()).isEqualTo(2);
        assertThat(both.err()).contains("Give --description or --description-file, not both.");

        Result noFile = run("ticket", "--project", "qits", "new", "--type", "BUG", "--title", "Log",
                "--description-file", missing.toString());
        assertThat(noFile.exit()).isEqualTo(2);
        assertThat(noFile.err()).contains("There is no file " + missing + " (--description-file).");

        Result noProject = run("ticket", "new", "--type", "BUG", "--title", "Log");
        assertThat(noProject.exit()).isEqualTo(2);
        assertThat(noProject.err()).contains("Name the project: --project <id, slug or name>.");

        assertThat(platform.requests).isEmpty();
    }

    @Test
    void aTypeTheServiceDoesNotKnowIsRefused() {
        platform.answer("POST", TICKETS, 400, "{\"message\":\"Unknown ticket type: CHORE\"}");

        Result r = run("ticket", "--project", "qits", "new", "--type", "chore", "--title", "Tidy up");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("POST " + platform.url() + TICKETS
                + " answered HTTP 400: Unknown ticket type: CHORE. --type is BUG or IMPROVEMENT.");
        assertThat(r.out()).isEmpty();
    }

    @Test
    void newWithoutTheRoleSaysSo() {
        platform.answer("POST", TICKETS, 403, "");

        Result r = run("ticket", "--project", "qits", "new", "--type", "BUG", "--title", "Log");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("Your roles do not allow this (HTTP 403): POST " + platform.url() + TICKETS);
    }

    // --- details ---

    @Test
    void detailsFindsTheTicketByTheStartOfItsIdAndShowsItWithItsComments() {
        Result r = run("ticket", "--project", "qits", "details", "--ticket", "AAAA1");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).startsWith("Ticket " + LOG + "\n")
                .contains("  slug        log-view-stops\n")
                .contains("  type        BUG\n")
                .contains("  status      REPORTED\n")
                .contains("  blocked     no\n")
                .contains("  title       Log view stops at 64 KiB\n")
                .contains("  assignee    -\n")
                .contains("  created by  wohlben\n")
                .contains("  workspaces  ws-7 on external/fix-log\n")
                // Terminal control characters out; each line of the text on its own line.
                .contains("Description:\n  It stops.\n  Red text here\n  Third line\n")
                .contains("Comments (2):\n")
                .contains("  alice\n    I can reproduce it\n")
                .contains("  -\n    Two\n    lines\n")
                .doesNotContain(String.valueOf(ESC)).doesNotContain("‮").doesNotContain("\r");
        assertThat(platform.requests("GET", TICKETS).getFirst().query()).isNull();
        assertThat(platform.requests("GET", "/projects/api/tickets/" + LOG)).hasSize(1);
        assertThat(platform.requests("GET", "/projects/api/tickets/" + LOG + "/comments")).hasSize(1);
    }

    @Test
    void detailsTakesTheWholeIdOrTheSlugAndItsOptionsInAnyOrder() {
        for (String[] args : new String[][] {
                {"ticket", "--project", "qits", "details", "--ticket", LOG},
                {"ticket", "details", "--ticket", "log-view-stops", "--project", "qits"},
                {"ticket", "--project", "qits", "--ticket", "aaaa1111", "details"},
                {"ticket", "--ticket", "aaaa1111", "details", "--project", QITS},
                {"ticket", "details", "--project", "qits platform", "--ticket", "aaaa1111"}}) {
            Result r = run(args);
            assertThat(r.exit()).as(String.join(" ", args) + ": " + r.err()).isZero();
            assertThat(r.out()).as(String.join(" ", args)).startsWith("Ticket " + LOG + "\n");
        }
    }

    @Test
    void aTicketThatFitsNoneSaysWhereToLook() {
        Result r = run("ticket", "--project", "qits", "details", "--ticket", "9999");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("Project qits has no ticket with the id or slug '9999', and no ticket id starts "
                + "with it. `qits ticket --project qits list` shows them.");
        assertThat(platform.requests).noneMatch(req -> req.path().startsWith("/projects/api/tickets/"));
    }

    @Test
    void aStartThatFitsTwoTicketsAsksForMore() {
        Result r = run("ticket", "--project", "qits", "details", "--ticket", "aaaa");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("'aaaa' fits more than one ticket of project qits: " + LOG
                + " (Log view stops at 64 KiB), " + FILTER + " (Filter runs by author). Give more of the id.");
        assertThat(platform.requests).noneMatch(req -> req.path().startsWith("/projects/api/tickets/"));
    }

    @Test
    void theTitleInAMessageLosesItsControlCharactersToo() {
        platform.answer("GET", TICKETS, """
                {"entries":[
                  {"ticket":{"id":"%s","title":"Evil \\u001b]0;pwned\\u0007title"}},
                  {"ticket":{"id":"bbbb3334-0000-4000-8000-000000000000","title":"Other"}}]}
                """.formatted(EVIL));

        Result r = run("ticket", "--project", "qits", "details", "--ticket", "bbbb");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains(EVIL + " (Evil title)").doesNotContain(String.valueOf(ESC));
    }

    @Test
    void detailsAsJsonIsTheTicketAndItsCommentsWithEscapes() throws Exception {
        Result r = run("ticket", "--project", "qits", "details", "--ticket", "aaaa1111", "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).doesNotContain(String.valueOf(ESC)).containsIgnoringCase("\\u001b[31mRed");
        JsonNode printed = JSON.readTree(r.out());
        assertThat(printed.path("ticket").path("id").asText()).isEqualTo(LOG);
        assertThat(printed.path("ticket").path("workspaces").get(0).path("branch").asText()).isEqualTo("external/fix-log");
        assertThat(printed.path("comments")).extracting(c -> c.path("id").asText()).containsExactly("c1", "c2");
        assertThat(printed.path("comments").get(0).path("body").asText()).isEqualTo("I can " + ESC + "[2Jreproduce it");
    }

    @Test
    void aTicketWithNoDescriptionOrCommentsSaysSo() {
        platform.answer("GET", "/projects/api/tickets/" + FILTER, """
                {"ticket":{"id":"%s","slug":"filter-runs-by-author","type":"IMPROVEMENT","status":"IMPLEMENTED",
                  "title":"Filter runs by author","assignee":"alice","description":null}}
                """.formatted(FILTER));
        platform.answer("GET", "/projects/api/tickets/" + FILTER + "/comments", "{\"entries\":[]}");

        Result r = run("ticket", "--project", "qits", "details", "--ticket", "aaaa2");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("  assignee    alice\n").contains("Description:\n  (none)\nComments: none.\n")
                .doesNotContain("workspaces");
    }

    @Test
    void aTicketDeletedAfterTheListIsA404() {
        Result r = run("ticket", "--project", "qits", "details", "--ticket", "bbbb");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("No such ticket: " + EVIL + " (HTTP 404). It may have been deleted a moment ago.");
        assertThat(r.out()).isEmpty();
    }

    @Test
    void detailsNeedsATicketAndOnlyDetailsTakesOne() {
        Result none = run("ticket", "--project", "qits", "details");
        assertThat(none.exit()).isEqualTo(2);
        assertThat(none.err()).contains("Name the ticket: --ticket <id, slug or the start of the id>.");

        Result onList = run("ticket", "--project", "qits", "--ticket", "aaaa1111", "list");
        assertThat(onList.exit()).isEqualTo(2);
        assertThat(onList.err()).contains("--ticket is for `qits ticket details`, not `qits ticket list`.");

        assertThat(run("ticket", "--project", "qits", "list", "--ticket", "aaaa1111").exit()).isEqualTo(2);
        assertThat(run("ticket", "--project", "qits").exit()).isEqualTo(2);
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void detailsShowsADroppedTicketAndThatItIsBlocked() {
        platform.answer("GET", "/projects/api/tickets/" + FILTER, """
                {"ticket":{"id":"%s","slug":"filter-runs-by-author","type":"IMPROVEMENT","status":"DROPPED",
                  "blocked":true,"title":"Filter runs by author","assignee":"alice","description":null}}
                """.formatted(FILTER));
        platform.answer("GET", "/projects/api/tickets/" + FILTER + "/comments", "{\"entries\":[]}");

        Result r = run("ticket", "--project", "qits", "details", "--ticket", "aaaa2");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("  status      DROPPED\n").contains("  blocked     yes\n");
    }

    @Test
    void aTicketWithNoBlockedFieldIsNotBlocked() {
        platform.answer("GET", "/projects/api/tickets/" + FILTER, """
                {"ticket":{"id":"%s","slug":"filter-runs-by-author","type":"IMPROVEMENT","status":"VERIFIED",
                  "title":"Filter runs by author","assignee":"alice","description":null}}
                """.formatted(FILTER));
        platform.answer("GET", "/projects/api/tickets/" + FILTER + "/comments", "{\"entries\":[]}");

        Result r = run("ticket", "--project", "qits", "details", "--ticket", "aaaa2");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("  status      VERIFIED\n").contains("  blocked     no\n")
                .doesNotContain("null");
    }

    // --- refusals and the project ---

    @Test
    void refusalsCarryTheirStatus() {
        platform.answer("GET", TICKETS, 401, "",
                "WWW-Authenticate", "Bearer error=\"invalid_token\", error_description=\"the token has expired\"");
        Result unauthorized = run("ticket", "--project", "qits", "list");
        assertThat(unauthorized.exit()).isEqualTo(1);
        assertThat(unauthorized.err()).contains("The platform refused the token (HTTP 401: invalid_token, the token "
                + "has expired).");

        platform.answer("GET", TICKETS, 403, "");
        Result forbidden = run("ticket", "--project", "qits", "details", "--ticket", "aaaa1");
        assertThat(forbidden.exit()).isEqualTo(1);
        assertThat(forbidden.err()).contains("Your roles do not allow this (HTTP 403): GET " + platform.url() + TICKETS);

        platform.answer("GET", TICKETS, 409, "{\"message\":\"the service is busy\"}");
        Result conflict = run("ticket", "--project", "qits", "list");
        assertThat(conflict.exit()).isEqualTo(1);
        assertThat(conflict.err()).contains("GET " + platform.url() + TICKETS + " answered HTTP 409: the service is busy");
    }

    @Test
    void anUnknownProjectNamesTheOnesThereAre() {
        Result r = run("ticket", "--project", "nope", "list");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("No project has the id, slug or name 'nope'. Projects: qits.");
        assertThat(platform.requests("GET", TICKETS)).isEmpty();
    }

    // --- comment ---

    @Test
    void commentSendsTheBodyAndPrintsTheComment() throws Exception {
        Result r = run("ticket", "--project", "qits", "comment", "--ticket", "AAAA1", "--body",
                " I can reproduce it too ");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(JSON.readTree(platform.requests("POST", "/projects/api/tickets/" + LOG + "/comments")
                .getFirst().body()).path("body").asText()).isEqualTo(" I can reproduce it too");
        assertThat(r.out()).contains("wohlben").contains("I can reproduce it too")
                .doesNotContain(String.valueOf(ESC));
    }

    @Test
    void commentReadsTheBodyFromAFileAndFromStdin() throws Exception {
        Path note = home.resolve("note.md");
        Files.writeString(note, "Confirmed on staging.\n\n", StandardCharsets.UTF_8);

        Result fromFile = run("ticket", "--project", "qits", "comment", "--ticket", "aaaa1111", "--body-file",
                note.toString());
        assertThat(fromFile.exit()).as(fromFile.err()).isZero();
        assertThat(JSON.readTree(platform.requests("POST", "/projects/api/tickets/" + LOG + "/comments")
                .getFirst().body()).path("body").asText()).isEqualTo("Confirmed on staging.");

        Result fromStdin = runWithInput(new ByteArrayInputStream("From a pipe\n".getBytes(StandardCharsets.UTF_8)),
                "ticket", "--project", "qits", "comment", "--ticket", "aaaa1111", "--body-file", "-");
        assertThat(fromStdin.exit()).as(fromStdin.err()).isZero();
    }

    @Test
    void commentAsJsonCarriesTheComment() throws Exception {
        Result r = run("ticket", "--project", "qits", "comment", "--ticket", "aaaa1111", "--body", "Reproduced",
                "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(JSON.readTree(r.out()).path("comment").path("id").asText()).isEqualTo("c3");
    }

    @Test
    void commentIsUsedWronglyWithoutCallingThePlatform() {
        Result none = run("ticket", "--project", "qits", "comment", "--ticket", "aaaa1111");
        assertThat(none.exit()).isEqualTo(2);
        assertThat(none.err()).contains("Give --body or --body-file.");

        Result both = run("ticket", "--project", "qits", "comment", "--ticket", "aaaa1111", "--body", "a",
                "--body-file", "-");
        assertThat(both.exit()).isEqualTo(2);
        assertThat(both.err()).contains("Give --body or --body-file, not both.");

        Result blank = run("ticket", "--project", "qits", "comment", "--ticket", "aaaa1111", "--body", "  ");
        assertThat(blank.exit()).isEqualTo(2);
        assertThat(blank.err()).contains("The comment is empty.");

        Result noTicket = run("ticket", "--project", "qits", "comment", "--body", "a");
        assertThat(noTicket.exit()).isEqualTo(2);
        assertThat(noTicket.err()).contains("Name the ticket: --ticket <id, slug or the start of the id>.");

        assertThat(platform.requests).noneMatch(req -> req.path().endsWith("/comments") && req.method().equals("POST"));
    }

    @Test
    void commentOnATicketThatFitsNoneSaysWhereToLook() {
        Result r = run("ticket", "--project", "qits", "comment", "--ticket", "9999", "--body", "a");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("Project qits has no ticket with the id or slug '9999'");
        assertThat(platform.requests).noneMatch(req -> req.path().endsWith("/comments") && req.method().equals("POST"));
    }

    @Test
    void commentWithoutTheRoleSaysSo() {
        platform.answer("POST", "/projects/api/tickets/" + LOG + "/comments", 403, "");

        Result r = run("ticket", "--project", "qits", "comment", "--ticket", "aaaa1111", "--body", "a");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("Your roles do not allow this (HTTP 403): POST " + platform.url()
                + "/projects/api/tickets/" + LOG + "/comments");
    }
}
