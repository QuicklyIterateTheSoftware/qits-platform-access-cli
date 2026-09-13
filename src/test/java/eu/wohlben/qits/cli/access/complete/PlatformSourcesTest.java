package eu.wohlben.qits.cli.access.complete;

import eu.wohlben.qits.cli.access.AccessCli;
import eu.wohlben.qits.cli.access.FakeIdp;
import eu.wohlben.qits.cli.access.FakePlatform;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.session.Session;
import eu.wohlben.qits.cli.access.session.SessionFile;
import eu.wohlben.qits.cli.tui.api.CompletionSource;
import eu.wohlben.qits.cli.tui.model.CommandNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The six sources, against the fake platform: one call, one list, and the labels a person reads. */
class PlatformSourcesTest {

    private static final Instant T0 = Instant.parse("2026-09-13T10:00:00Z");
    private static final String QITS = "8f1c2d3e-0000-4000-8000-000000000001";
    private static final String CI = "0a0b0c0d-0000-4000-8000-00000000000a";
    private static final String REQUESTS = "/projects/api/repositories/" + CI + "/release-requests";

    @TempDir
    Path home;

    private FakeIdp idp;
    private FakePlatform platform;
    private CliContext context;
    private final Map<String, String> env = new HashMap<>();

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdp();
        platform = new FakePlatform();
        FakeTime time = new FakeTime(T0);
        SessionFile store = new SessionFile(home.resolve("qits"));
        env.put("XDG_CONFIG_HOME", home.toString());
        env.put("QITS_PROJECTS_URL", platform.url());
        env.put("QITS_CI_URL", platform.url());
        store.write(new Session(idp.url(), TokenClient.CLIENT_ID, "access", T0.plus(Duration.ofMinutes(10)),
                "refresh", T0.plus(Duration.ofDays(1))));
        context = new CliContext(env, InputStream.nullInputStream(),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                time, time, TokenClient::new, stop -> {
        });

        platform.answer("GET", "/projects/api/projects", """
                {"entries":[{"project":{"id":"%s","name":"qits platform","slug":"qits","description":null,"dns":null}}]}
                """.formatted(QITS));
        platform.answer("GET", "/projects/api/projects/" + QITS + "/repositories", """
                {"entries":[{"repository":{"id":"%s","name":"qits-ci-service","archetype":"QUARKUS_SERVICE",
                  "component":"qits-ci","projectId":"%s"},"declared":true}]}
                """.formatted(CI, QITS));
    }

    @AfterEach
    void stop() {
        idp.close();
        platform.close();
    }

    private <T extends PlatformSource> T source(T source) {
        source.useContext(context);
        return source;
    }

    @Test
    void projectsAreListedBySlug() {
        List<CompletionSource.Choice> choices = source(new ProjectSource()).choices(Map.of());
        assertThat(choices).singleElement().satisfies(choice -> {
            assertThat(choice.value()).isEqualTo("qits");
            assertThat(choice.label()).contains("qits").contains("qits platform");
        });
    }

    @Test
    void repositoriesAreThoseOfTheChosenProject() {
        RepositorySource source = source(new RepositorySource());
        assertThat(source.dependsOn()).containsExactly("project");
        assertThat(source.choices(Map.of("project", "qits"))).singleElement().satisfies(choice -> {
            assertThat(choice.value()).isEqualTo("qits-ci-service");
            assertThat(choice.label()).contains("QUARKUS_SERVICE");
        });
    }

    @Test
    void onlyTheReleaseRequestsThatAreStillOpenAreOffered() {
        platform.answer("GET", REQUESTS, """
                {"requests":[
                  {"id":"4f2a91c0-0000-4000-8000-000000000001","state":"PENDING","summary":"a change","version":null},
                  {"id":"4f2a91c0-0000-4000-8000-000000000002","state":"RELEASED","summary":"shipped","version":"2026.913.1"},
                  {"id":"4f2a91c0-0000-4000-8000-000000000003","state":"WITHDRAWN","summary":"dropped","version":null}]}
                """);
        ReleaseRequestSource source = source(new ReleaseRequestSource());
        assertThat(source.dependsOn()).containsExactlyInAnyOrder("project", "repository");
        assertThat(source.choices(Map.of("project", "qits", "repository", "qits-ci-service")))
                .singleElement().satisfies(choice -> {
                    assertThat(choice.value()).isEqualTo("4f2a91c0-0000-4000-8000-000000000001");
                    assertThat(choice.label()).contains("4f2a91c0").contains("PENDING").contains("a change");
                });
    }

    @Test
    void versionsComeFromTheRequestsThatReleased() {
        platform.answer("GET", REQUESTS, """
                {"requests":[
                  {"id":"4f2a91c0-0000-4000-8000-000000000002","state":"RELEASED","summary":"shipped","version":"2026.913.1"},
                  {"id":"4f2a91c0-0000-4000-8000-000000000004","state":"RELEASED","summary":"earlier","version":"2026.912.9"}]}
                """);
        assertThat(source(new VersionSource()).choices(Map.of("project", "qits", "repository", "qits-ci-service")))
                .extracting(CompletionSource.Choice::value)
                .containsExactly("2026.913.1", "2026.912.9");
    }

    @Test
    void ticketsAreTheOpenOnesOfTheProject() {
        platform.answer("GET", "/projects/api/projects/" + QITS + "/tickets", """
                {"entries":[{"ticket":{"id":"235bc53e-0000-4000-8000-000000000001","title":"a thing to do","status":"OPEN"}}]}
                """);
        TicketSource source = source(new TicketSource());
        assertThat(source.dependsOn()).containsExactly("project");
        assertThat(source.choices(Map.of("project", "qits"))).singleElement().satisfies(choice -> {
            assertThat(choice.value()).isEqualTo("235bc53e-0000-4000-8000-000000000001");
            assertThat(choice.label()).contains("235bc53e").contains("a thing to do");
        });
    }

    @Test
    void aRunIsPickedByItsBranchStatusAndTime() {
        platform.answer("GET", "/ci/api/runs", """
                {"runs":[{"id":"5f2c0a9e-0000-4000-8000-000000000001","branch":"main","status":"SUCCESS",
                  "createdAt":"2026-09-13T14:02:11Z"}]}
                """);
        assertThat(source(new RunSource()).choices(Map.of("project", "qits", "repository", "qits-ci-service")))
                .singleElement().satisfies(choice -> {
                    assertThat(choice.value()).isEqualTo("5f2c0a9e-0000-4000-8000-000000000001");
                    assertThat(choice.label()).contains("5f2c0a9e").contains("main").contains("SUCCESS");
                });
    }

    @Test
    void aRefusalArrivesAsItsOwnSentence() {
        assertThatThrownBy(() -> source(new RepositorySource()).choices(Map.of("project", "nothing")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No project has the id, slug or name 'nothing'");
    }

    @Test
    void everyOptionOfThoseKindsNamesTheSameSource() {
        CommandNode qits = CommandNode.of(new CommandLine(new AccessCli()).getCommandSpec());
        assertThat(qits.child("ci").child("runs").row("project").completionSource()).isEqualTo(ProjectSource.class);
        assertThat(qits.child("ci").child("runs").row("repository").completionSource())
                .isEqualTo(RepositorySource.class);
        assertThat(qits.child("ci").child("runs").row("release-request").completionSource())
                .isEqualTo(ReleaseRequestSource.class);
        assertThat(qits.child("ci").child("run").row("run id").completionSource()).isEqualTo(RunSource.class);
        assertThat(qits.child("repositories").child("list").row("project").completionSource())
                .isEqualTo(ProjectSource.class);
        assertThat(qits.child("ticket").child("list").row("project").completionSource())
                .isEqualTo(ProjectSource.class);
        assertThat(qits.child("ticket").child("details").row("ticket").completionSource())
                .isEqualTo(TicketSource.class);
        assertThat(qits.child("release-request").child("list").row("repository").completionSource())
                .isEqualTo(RepositorySource.class);
    }
}
