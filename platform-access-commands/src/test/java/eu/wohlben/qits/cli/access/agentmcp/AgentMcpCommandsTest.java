package eu.wohlben.qits.cli.access.agentmcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cli.access.FakeIdp;
import eu.wohlben.qits.cli.access.FakePlatform;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.TestCli;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.session.Session;
import eu.wohlben.qits.cli.access.session.SessionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
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

/** `qits agent-mcp credentials …` against a fake edge and a fake idp (epic qits-765, qits-1394). */
class AgentMcpCommandsTest {

    private static final Instant T0 = Instant.parse("2026-10-30T10:00:00Z");
    private static final String CREDENTIALS = "/projects/api/agent-mcp-catalog/credentials";
    private static final String GITHUB = CREDENTIALS + "/env.GITHUB_TOKEN";
    private static final String SECRET = "ghp_never-printed";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path home;

    private FakeIdp idp;
    private FakePlatform platform;
    private FakeTime time;
    private final Map<String, String> env = new HashMap<>();

    record Result(int exit, String out, String err) {
    }

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdp();
        platform = new FakePlatform();
        time = new FakeTime(T0);
        env.put("XDG_CONFIG_HOME", home.toString());
        env.put("QITS_PROJECTS_URL", platform.url());
        new SessionFile(home.resolve("qits")).write(new Session(idp.url(), "qits-cli", FakeIdp.SECRET + "access-0",
                T0.plus(Duration.ofMinutes(10)), idp.issueRefreshToken(), T0.plus(Duration.ofDays(30))));
        platform.answer("GET", CREDENTIALS, """
                {"credentials":[{"key":"env.GITHUB_TOKEN","updatedAt":"2026-10-30T09:00:00Z","updatedBy":"admin"},
                                {"key":"env.LINEAR_KEY","updatedAt":"2026-10-29T09:00:00Z","updatedBy":"desk"}]}
                """);
        platform.answer("PUT", GITHUB, 200, """
                {"key":"env.GITHUB_TOKEN","updatedAt":"2026-10-30T10:00:00Z","updatedBy":"admin"}
                """);
        platform.answer("DELETE", GITHUB, 204, "");
    }

    @AfterEach
    void stop() {
        platform.close();
        idp.close();
    }

    private Result run(InputStream stdin, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliContext context = new CliContext(Map.copyOf(env), stdin,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                time, time, TokenClient::new, stop -> { });
        int exit = TestCli.execute(context, args);
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private Result run(String... args) {
        return run(InputStream.nullInputStream(), args);
    }

    private static InputStream stdin(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void listShowsKeysOnly() {
        Result r = run("agent-mcp", "credentials", "list");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("KEY").contains("UPDATED BY").contains("env.GITHUB_TOKEN").contains("env.LINEAR_KEY")
                .contains("admin").contains("desk");
    }

    @Test
    void listJsonIsTheAnswer() {
        Result r = run("agent-mcp", "credentials", "list", "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("\"credentials\"").contains("\"env.GITHUB_TOKEN\"");
    }

    @Test
    void setSendsTheValueFromStdinAndNeverPrintsIt() throws Exception {
        Result r = run(stdin(SECRET + "\n"), "agent-mcp", "credentials", "set", "--key", "env.GITHUB_TOKEN");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out().strip()).isEqualTo("set env.GITHUB_TOKEN");
        assertThat(r.err()).doesNotContain(SECRET);
        assertThat(JSON.readTree(platform.requests("PUT", GITHUB).getFirst().body()))
                .isEqualTo(JSON.readTree("{\"value\":\"" + SECRET + "\"}"));
    }

    @Test
    void setJsonIsTheAnswerWithoutAValue() {
        Result r = run(stdin(SECRET), "agent-mcp", "credentials", "set", "--key", "env.GITHUB_TOKEN", "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("\"key\" : \"env.GITHUB_TOKEN\"").contains("\"updatedBy\"").doesNotContain(SECRET);
    }

    @Test
    void aMalformedKeyIsTheServicesRefusal() {
        platform.answer("PUT", CREDENTIALS + "/GITHUB_TOKEN", 400, "{\"message\":\"key must be env.<VAR>\"}");

        Result r = run(stdin(SECRET), "agent-mcp", "credentials", "set", "--key", "GITHUB_TOKEN");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("400").doesNotContain(SECRET);
    }

    @Test
    void setRefusesNothingOnStdin() {
        Result r = run(stdin(""), "agent-mcp", "credentials", "set", "--key", "env.GITHUB_TOKEN");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("Nothing on stdin").contains("qits agent-mcp credentials set --key env.GITHUB_TOKEN");
        assertThat(platform.requests("PUT", GITHUB)).isEmpty();
    }

    @Test
    void aRefusedSetNamesTheStatusButNotTheValue() {
        platform.answer("PUT", GITHUB, 403, "{\"message\":\"forbidden\"}");

        Result r = run(stdin(SECRET), "agent-mcp", "credentials", "set", "--key", "env.GITHUB_TOKEN");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("403").doesNotContain(SECRET);
        assertThat(r.out()).doesNotContain(SECRET);
    }

    @Test
    void deletePrintsDeletedAndA404Fails() {
        Result deleted = run("agent-mcp", "credentials", "delete", "--key", "env.GITHUB_TOKEN");
        platform.answer("DELETE", GITHUB, 404, "{\"message\":\"no credential env.GITHUB_TOKEN\"}");
        Result missing = run("agent-mcp", "credentials", "delete", "--key", "env.GITHUB_TOKEN");

        assertThat(deleted.exit()).as(deleted.err()).isZero();
        assertThat(deleted.out().strip()).isEqualTo("deleted");
        assertThat(missing.exit()).isEqualTo(1);
        assertThat(missing.err()).contains("404");
    }
}
