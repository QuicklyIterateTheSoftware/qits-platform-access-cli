package eu.wohlben.qits.cli.access.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cli.access.FakeIdp;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.TestCli;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.session.Session;
import eu.wohlben.qits.cli.access.session.SessionFile;
import eu.wohlben.qits.cli.session.AgentCredential;
import eu.wohlben.qits.cli.session.Mode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Claude's headers helper, in both homes: one JSON object on stdout and exit 0, or one line on
 * stderr, nothing on stdout and exit 1 — so a failure leaves Claude connecting without the header.
 */
class McpCredentialCommandTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path home;

    private FakeIdp idp;
    private FakeTime time;
    private SessionFile store;
    private final Map<String, String> env = new HashMap<>();
    private final ByteArrayOutputStream allErr = new ByteArrayOutputStream();

    record Result(int exit, String out, String err) {
    }

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdp();
        time = new FakeTime(T0);
        store = new SessionFile(home.resolve("qits"));
        env.put("XDG_CONFIG_HOME", home.toString());
    }

    @AfterEach
    void stop() {
        idp.close();
        // stdout carries a token by design, for Claude; stderr never does.
        assertThat(allErr.toString(StandardCharsets.UTF_8)).doesNotContain(FakeIdp.SECRET);
    }

    private Result run() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliContext context = new CliContext(Map.copyOf(env), InputStream.nullInputStream(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                time, time, TokenClient::new, stop -> { });
        int exit = TestCli.execute(context, "mcp-credential");
        allErr.writeBytes(err.toByteArray());
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private void inPlatform() {
        env.put(Mode.CLIENT_ID, idp.commissionedId);
        env.put(Mode.CLIENT_SECRET, idp.commissionedSecret);
        env.put("QITS_GIT_AUTH_TOKEN_URL", idp.url() + "/token");
    }

    private static String authorization(String out) throws Exception {
        JsonNode headers = JSON.readTree(out);
        assertThat(headers.size()).as("one header, nothing else").isEqualTo(1);
        return headers.path("Authorization").asText();
    }

    @Test
    void insideThePlatformItPrintsTheContainersBearer() throws Exception {
        inPlatform();

        Result r = run();

        assertThat(r.exit()).isZero();
        assertThat(r.err()).isEmpty();
        assertThat(r.out()).endsWith("}\n").startsWith("{\"Authorization\":\"Bearer ");
        String base = idp.url().substring(0, idp.url().length() - "/idp".length());
        String minted = new AgentCredential(base, idp.commissionedId, idp.commissionedSecret, "qits-platform", time)
                .bearer();
        assertThat(authorization(r.out())).isEqualTo("Bearer " + minted);
        assertThat(idp.requests).first().satisfies(request -> {
            assertThat(request).containsEntry("grant_type", "client_credentials");
            assertThat(request).containsEntry("audience", "qits-platform");
        });
    }

    /** The token as it is: nothing minted, no session read, even with the pair beside it. */
    @Test
    void withAWorkspaceTokenItPrintsThatToken() throws Exception {
        inPlatform();
        env.put(Mode.TOKEN, "the workspace token");

        Result r = run();

        assertThat(r).isEqualTo(new Result(0, "{\"Authorization\":\"Bearer the workspace token\"}\n", ""));
        assertThat(idp.requests).as("nothing is minted").isEmpty();
    }

    @Test
    void onAWorkstationItPrintsTheSessionsAccessToken() throws Exception {
        store.write(new Session(idp.url(), "qits-cli", FakeIdp.SECRET + "access-0", T0.plus(Duration.ofMinutes(10)),
                idp.issueRefreshToken(), T0.plus(Duration.ofDays(30))));

        Result r = run();

        assertThat(r).isEqualTo(new Result(0, "{\"Authorization\":\"Bearer " + FakeIdp.SECRET + "access-0\"}\n", ""));
        assertThat(idp.requests).as("a fresh token is used as it is").isEmpty();
    }

    @Test
    void onAWorkstationAnExpiringTokenIsRefreshedFirst() throws Exception {
        store.write(new Session(idp.url(), "qits-cli", FakeIdp.SECRET + "access-0", T0.plusSeconds(5),
                idp.issueRefreshToken(), T0.plus(Duration.ofDays(30))));

        Result r = run();

        assertThat(r.exit()).isZero();
        assertThat(idp.grants("refresh_token")).isEqualTo(1);
        String refreshed = store.read().orElseThrow().accessToken();
        assertThat(refreshed).isNotEqualTo(FakeIdp.SECRET + "access-0");
        assertThat(authorization(r.out())).isEqualTo("Bearer " + refreshed);
    }

    @Test
    void withoutASessionItSaysSoAndPrintsNothing() {
        Result r = run();

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.out()).isEmpty();
        assertThat(r.err()).isEqualTo("Not signed in — run `qits login`.\n");
    }

    @Test
    void anIdpThatRefusesTheContainerLeavesStdoutEmpty() {
        inPlatform();
        idp.commissionedSecret = "another secret";

        Result r = run();

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.out()).isEmpty();
        assertThat(r.err().lines()).singleElement()
                .satisfies(line -> assertThat(line).contains("The idp refused the workspace credential"));
        assertThat(r.err()).doesNotContain(env.get(Mode.CLIENT_SECRET));
    }
}
