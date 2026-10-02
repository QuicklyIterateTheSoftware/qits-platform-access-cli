package eu.wohlben.qits.cli.access.maintenance;

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

/** `qits maintenance …` against a fake edge (projects and maintenance) and a fake idp. */
class MaintenanceCommandsTest {

    private static final Instant T0 = Instant.parse("2026-10-02T10:00:00Z");
    private static final String QITS = "8f1c2d3e-0000-4000-8000-000000000001";
    private static final String LANDING = "0a0b0c0d-0000-4000-8000-00000000000a";
    private static final String REQUEST = "4f2a91c0-2222-3333-4444-555555555555";
    private static final String OTHER = "9e9e9e9e-2222-3333-4444-555555555555";
    private static final String JOB = "6f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f";
    private static final String DOOR = "/maintenance/api/repositories/qits-landing-app/release-requests/" + REQUEST
            + "/screenshot-baselines";

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
        env.put("QITS_MAINTENANCE_URL", platform.url());
        new SessionFile(home.resolve("qits")).write(new Session(idp.url(), "qits-cli", FakeIdp.SECRET + "access-0",
                T0.plus(Duration.ofMinutes(10)), idp.issueRefreshToken(), T0.plus(Duration.ofDays(30))));
        platform.answer("GET", "/projects/api/projects", """
                {"entries":[{"project":{"id":"%s","name":"qits platform","slug":"qits"}}]}
                """.formatted(QITS));
        platform.answer("GET", "/projects/api/projects/" + QITS + "/repositories", """
                {"entries":[{"repository":{"id":"%s","name":"qits-landing-app","archetype":"APP"},"declared":true}]}
                """.formatted(LANDING));
        platform.answer("GET", "/projects/api/repositories/" + LANDING + "/release-requests", """
                {"requests":[{"id":"%s","state":"REJECTED"},{"id":"%s","state":"RELEASED"}]}
                """.formatted(REQUEST, OTHER));
        platform.answer("POST", DOOR, 202, "{\"id\":\"" + JOB + "\"}");
        platform.answer("GET", "/maintenance/api/bumps/" + JOB, """
                {"id":"%s","mode":"BASELINES","repository":"qits-landing-app",
                 "branch":"maintenance/baselines/%s","status":"SUCCEEDED",
                 "message":"new baselines on maintenance/baselines/%s at abc, joined to release request %s",
                 "resultSha":"abc","releaseRequestId":"%s"}
                """.formatted(JOB, REQUEST, REQUEST, REQUEST, REQUEST));
    }

    @AfterEach
    void stop() {
        platform.close();
        idp.close();
    }

    private Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliContext context = new CliContext(Map.copyOf(env), InputStream.nullInputStream(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                time, time, TokenClient::new, stop -> { });
        int exit = TestCli.execute(context, args);
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aShortRequestIdIsFoundAndTheJobIsRequested() {
        Result r = run("maintenance", "--project", "qits", "--repository", "qits-landing-app",
                "screenshot-baselines", "--request", "4f2a", "--work-item", "qits-112");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("job " + JOB).contains("qits maintenance bump " + JOB);
        assertThat(platform.requests("POST", DOOR)).hasSize(1);
        assertThat(platform.requests("POST", DOOR).getFirst().body()).contains("\"workItem\":\"qits-112\"");
        assertThat(platform.requests("POST", DOOR).getFirst().authorization())
                .isEqualTo("Bearer " + FakeIdp.SECRET + "access-0");
    }

    @Test
    void withoutAWorkItemTheBodyNamesNone() {
        Result r = run("maintenance", "screenshot-baselines", "--project", "qits", "--repository", "qits-landing-app",
                "--request", REQUEST);

        assertThat(r.exit()).isZero();
        assertThat(platform.requests("POST", DOOR).getFirst().body()).isEqualTo("{}");
    }

    @Test
    void anUnknownRequestIsAUsageError() {
        Result r = run("maintenance", "--project", "qits", "--repository", "qits-landing-app",
                "screenshot-baselines", "--request", "ffff");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("no release request whose id starts with 'ffff'");
        assertThat(platform.requests("POST", DOOR)).isEmpty();
    }

    @Test
    void aRefusalIsPassedOn() {
        platform.answer("POST", DOOR, 409, "{\"message\":\"the release request is RELEASED and takes no branch\"}");
        Result r = run("maintenance", "--project", "qits", "--repository", "qits-landing-app",
                "screenshot-baselines", "--request", REQUEST);

        assertThat(r.exit()).isEqualTo(1);
    }

    @Test
    void bumpShowsTheJob() {
        Result r = run("maintenance", "bump", JOB);

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("BASELINES").contains("SUCCEEDED").contains("joined to release request " + REQUEST);
    }
}
