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
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * qits work --entity <id> comment create|update, as a person types it, in this process, against a
 * fake platform and a fake idp. The payload schemas come from a canned OpenAPI answer: the command
 * keeps no copy of its own.
 */
class WorkCommentCommandsTest {

    private static final Instant T0 = Instant.parse("2026-09-29T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EPIC = "qits-100";
    private static final String TASK = "dddd5555-0000-4000-8000-000000000005";
    private static final String THREAD = "/projects/api/entities/" + EPIC + "/comments";
    private static final String C1 = "eeee1111-0000-4000-8000-000000000001";
    private static final String PATCH_C1 = "/projects/api/comments/" + C1;
    private static final String OPENAPI = "/projects/q/openapi";
    private static final char ESC = 0x1B;

    /** The service's document, cut down to the two doors, with the schemas behind references as it serves them. */
    private static final String DOCUMENT = """
            {"openapi":"3.1.0",
             "components":{"schemas":{
               "CreateCommentRequest":{"type":"object","required":["body"],
                 "properties":{"body":{"$ref":"#/components/schemas/Markdown"}}},
               "CommentPatch":{"type":"object","properties":{"body":{"type":"string","pattern":"\\\\S"}}},
               "Markdown":{"type":"string","pattern":"\\\\S"}}},
             "paths":{
               "/projects/api/entities/{entityId}/comments":{
                 "get":{"responses":{"200":{"description":"OK"}}},
                 "post":{"requestBody":{"required":true,"content":{"application/json":
                   {"schema":{"$ref":"#/components/schemas/CreateCommentRequest"}}}}}},
               "/projects/api/comments/{commentId}":{
                 "patch":{"requestBody":{"required":true,"content":{
                   "application/json":{"schema":{"type":"string"}},
                   "application/merge-patch+json":{"schema":{"$ref":"#/components/schemas/CommentPatch"}}}}}}}}
            """;

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

        platform.answer("GET", OPENAPI, DOCUMENT);
        // The author carries an escape sequence, as whoever names an account can write.
        platform.answer("POST", THREAD, """
                {"comment":{"id":"c3","entityId":"%s","author":"wohl\\u001b[2Jben","body":"hi",
                  "createdAt":"2026-09-29T10:00:00Z","updatedAt":"2026-09-29T10:00:00Z"}}
                """.formatted(TASK));
        platform.answer("GET", THREAD, """
                {"entries":[
                  {"comment":{"id":"%s","entityId":"%s","author":"alice","body":"first",
                    "createdAt":"2026-09-29T09:00:00Z","updatedAt":"2026-09-29T09:00:00Z"}}]}
                """.formatted(C1, TASK));
        platform.answer("PATCH", PATCH_C1, """
                {"comment":{"id":"%s","entityId":"%s","author":"alice","body":"edited",
                  "createdAt":"2026-09-29T09:00:00Z","updatedAt":"2026-09-29T10:00:00Z"}}
                """.formatted(C1, TASK));
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

    private Result runWithInput(String stdin, String... args) {
        return runWithInput(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)), args);
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

    private boolean anyWrite() {
        synchronized (platform.requests) {
            return platform.requests.stream().anyMatch(r -> !r.method().equals("GET"));
        }
    }

    // --- create ---

    @Test
    void createSendsThePayloadUnchangedToTheQualifiedIdAndPrintsIdAuthorAndTime() throws Exception {
        String payload = "{\"body\":\"I can \\u001b[2Jreproduce it\",\"note\":{\"kept\":[1,2]}}";
        Result r = runWithInput(payload, "work", "--entity", EPIC, "comment", "create");

        assertThat(r.exit()).as(r.err()).isZero();
        FakePlatform.Request sent = platform.requests("POST", THREAD).getFirst();
        assertThat(JSON.readTree(sent.body())).isEqualTo(JSON.readTree(payload));
        assertThat(sent.contentType()).isEqualTo("application/json");
        assertThat(sent.authorization()).isEqualTo("Bearer " + FakeIdp.SECRET + "access-0");
        // Nothing but the write: a payload needs no schema.
        assertThat(platform.requests("GET", OPENAPI)).isEmpty();
        assertThat(r.out().lines().toList()).hasSize(2);
        assertThat(r.out().lines().toList().getFirst()).startsWith("ID").contains("AUTHOR").contains("CREATED");
        assertThat(r.out().lines().toList().get(1)).startsWith("c3").contains("wohlben");
        assertThat(r.out()).doesNotContain(String.valueOf(ESC));
    }

    @Test
    void theEntityMayComeAfterTheCommandAndAUuidIsPassedAsItIs() throws Exception {
        String path = "/projects/api/entities/" + TASK + "/comments";
        platform.answer("POST", path, "{\"comment\":{\"id\":\"c4\",\"entityId\":\"" + TASK + "\",\"author\":\"a\"}}");

        Result r = runWithInput("{\"body\":\"x\"}", "work", "comment", "create", "--entity", TASK, "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("POST", path)).hasSize(1);
        assertThat(JSON.readTree(r.out()).path("comment").path("id").asText()).isEqualTo("c4");
    }

    @Test
    void createAsJsonPrintsTheServicesAnswerWithEscapes() throws Exception {
        Result r = runWithInput("{\"body\":\"hi\"}", "work", "--entity", EPIC, "comment", "create", "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).doesNotContain(String.valueOf(ESC)).contains("\\u001B[2J");
        assertThat(JSON.readTree(r.out()).path("comment").path("author").asText()).isEqualTo("wohl" + ESC + "[2Jben");
    }

    @Test
    void nothingOnStdinPrintsTheSchemaFromTheServiceAndSendsNothing() {
        Result r = run("work", "--entity", EPIC, "comment", "create");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(anyWrite()).isFalse();
        assertThat(platform.requests("GET", OPENAPI).getFirst().query()).isEqualTo("format=json");
        assertThat(r.out())
                .contains("nothing was sent")
                .contains("Required: body.")
                .contains("POST /projects/api/entities/{id}/comments, application/json")
                // The reference is resolved: the property's own schema is printed, not a pointer.
                .contains("\"pattern\" : \"\\\\S\"")
                .doesNotContain("$ref");
    }

    @Test
    void blankStdinIsNothingPutInAndTheEntityIsNotNeededForTheSchema() {
        Result r = runWithInput("  \n\t\n", "work", "comment", "create");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("Required: body.");
        assertThat(anyWrite()).isFalse();
    }

    @Test
    void theSchemaAsJsonIsTheResolvedSchemaAlone() throws Exception {
        Result r = run("work", "--entity", EPIC, "comment", "create", "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        JsonNode schema = JSON.readTree(r.out());
        assertThat(schema.path("required").get(0).asText()).isEqualTo("body");
        assertThat(schema.path("properties").path("body").path("type").asText()).isEqualTo("string");
    }

    @Test
    void aServiceThatDoesNotDescribeTheDoorSaysSoAndSendsNothing() {
        platform.answer("GET", OPENAPI, "{\"openapi\":\"3.1.0\",\"paths\":{}}");

        Result r = run("work", "--entity", EPIC, "comment", "create");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("describes no request body for POST /projects/api/entities/{id}/comments")
                .contains("may not be deployed yet");
        assertThat(anyWrite()).isFalse();
    }

    @Test
    void aPayloadThatIsNotAJsonObjectIsAUsageErrorWithNoRequest() {
        Result broken = runWithInput("{\"body\": \"unterminated", "work", "--entity", EPIC, "comment", "create");
        assertThat(broken.exit()).isEqualTo(2);
        assertThat(broken.err()).contains("not JSON").contains("Nothing was sent.");

        Result array = runWithInput("[\"body\"]", "work", "--entity", EPIC, "comment", "create");
        assertThat(array.exit()).isEqualTo(2);
        assertThat(array.err()).contains("must be a JSON object, not an array");

        Result trailing = runWithInput("{\"body\":\"a\"} {\"body\":\"b\"}", "work", "--entity", EPIC, "comment", "create");
        assertThat(trailing.exit()).isEqualTo(2);

        assertThat(platform.requests).isEmpty();
    }

    @Test
    void aPayloadWithoutAnEntityIsAUsageErrorWithNoRequest() {
        Result r = runWithInput("{\"body\":\"hi\"}", "work", "comment", "create");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("--entity");
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void aRefusalIsReported() {
        platform.answer("POST", THREAD, 403, "");

        Result r = runWithInput("{\"body\":\"hi\"}", "work", "--entity", EPIC, "comment", "create");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("HTTP 403").contains(THREAD);
    }

    // --- update ---

    @Test
    void updateSendsTheMergePatchUnchangedWithItsMediaType() throws Exception {
        String patch = "{\"body\":\"edited\"}";
        Result r = runWithInput(patch, "work", "--entity", EPIC, "comment", "update", "--comment", C1);

        assertThat(r.exit()).as(r.err()).isZero();
        FakePlatform.Request sent = platform.requests("PATCH", PATCH_C1).getFirst();
        assertThat(sent.contentType()).isEqualTo("application/merge-patch+json");
        assertThat(JSON.readTree(sent.body())).isEqualTo(JSON.readTree(patch));
        assertThat(platform.requests("GET", THREAD)).hasSize(1);
        assertThat(r.out().lines().toList().getFirst()).contains("UPDATED");
        assertThat(r.out()).contains(C1).contains("alice");
    }

    @Test
    void updateOfACommentNotOnTheEntityIsRefusedAndNothingIsSent() {
        Result r = runWithInput("{\"body\":\"edited\"}", "work", "--entity", EPIC, "comment", "update",
                "--comment", "ffff9999-0000-4000-8000-000000000009");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("is not on the thread of " + EPIC).contains("Nothing was sent.");
        assertThat(anyWrite()).isFalse();
    }

    @Test
    void updateWithNothingOnStdinPrintsTheMergePatchSchema() {
        Result r = run("work", "--entity", EPIC, "comment", "update", "--comment", C1);

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("PATCH /projects/api/comments/{commentId}, application/merge-patch+json")
                .contains("Required: nothing.");
        assertThat(anyWrite()).isFalse();
        assertThat(platform.requests("GET", THREAD)).isEmpty();
    }

    @Test
    void updateWithoutACommentIsAUsageErrorWithNoRequest() {
        Result r = runWithInput("{\"body\":\"edited\"}", "work", "--entity", EPIC, "comment", "update");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("--comment");
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void aRefusedPatchIsReported() {
        platform.answer("PATCH", PATCH_C1, 400, "{\"message\":\"unknown property author\"}");

        Result r = runWithInput("{\"author\":\"x\"}", "work", "--entity", EPIC, "comment", "update", "--comment", C1);

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("HTTP 400").contains("unknown property author");
    }
}
