package eu.wohlben.qits.cli.session;

import eu.wohlben.qits.cli.access.AccessCli;
import eu.wohlben.qits.cli.access.FakeIdp;
import eu.wohlben.qits.cli.access.FakePlatform;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.tui.TuiApp;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.model.CommandNode;
import eu.wohlben.qits.cli.tui.run.CommandRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What an agent is told when a door says no — worded once, not printed raw. */
class InPlatformRefusalsTest {

    private static final Instant T0 = Instant.parse("2026-09-13T10:00:00Z");

    private FakeIdp idp;
    private FakePlatform platform;
    private FakeTime time;

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdp();
        platform = new FakePlatform();
        time = new FakeTime(T0);
    }

    @AfterEach
    void stop() {
        idp.close();
        platform.close();
    }

    private AgentCredential agent() {
        String base = idp.url().substring(0, idp.url().length() - "/idp".length());
        return new AgentCredential(base, idp.commissionedId, idp.commissionedSecret, "qits-platform", time);
    }

    @Test
    void aWriteDoorSaysWhatTheCredentialIsBeforeItSaysWhatTheServiceSaid() {
        platform.answer("POST", "/projects/api/write", 403, "{\"error\":\"forbidden\"}");
        PlatformClient client = new PlatformClient(agent());
        assertThatThrownBy(() -> client.post(URI.create(platform.url() + "/projects/api/write")))
                .isInstanceOf(CliFailure.class)
                .hasMessageStartingWith("403 - this credential is qits:agent, which reads but does not write")
                .hasMessageContaining("HTTP 403");
    }

    @Test
    void aWorkstationSessionAddsNothingOfItsOwn() {
        assertThat(new AgentCredential("http://idp", "id", "secret", "a", time).explain(404)).isNull();
        assertThat(((Credential) () -> "token").explain(403)).isNull();
    }

    @Test
    void aBrowserCommandIsRefusedInsideAndUntouchedOutside() {
        assertThat(refuse(Mode.IN_PLATFORM, "login")).isEqualTo(BrowserGuard.NO_BROWSER);
        assertThat(refuse(Mode.IN_PLATFORM, "git-login")).isEqualTo(BrowserGuard.NO_BROWSER);
        assertThat(refuse(Mode.WORKSTATION, "login")).isNull();
        assertThat(refuse(Mode.IN_PLATFORM, "ci")).isNull();
    }

    @Test
    void helpOnABrowserCommandStillAnswersInside() {
        assertThat(refuse(Mode.IN_PLATFORM, "login", "--help")).isNull();
    }

    /** The guard's answer: the refusal it printed, or null when it let the command through. */
    private String refuse(Mode mode, String... args) {
        CommandLine cli = new CommandLine(new AccessCli());
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        cli.setErr(new PrintWriter(err, true, StandardCharsets.UTF_8));
        cli.setOut(new PrintWriter(out, true, StandardCharsets.UTF_8));
        boolean[] ranThrough = {false};
        cli.setExecutionStrategy(new BrowserGuard(parsed -> {
            ranThrough[0] = true;
            return 0;
        }, mode));
        cli.execute(args);
        return ranThrough[0] ? null : err.toString(StandardCharsets.UTF_8).strip();
    }

    @Test
    void theScreenShowsTheBrowserCommandsAsDecorationInside() {
        CommandNode root = CommandNode.of(new CommandLine(new AccessCli()).getCommandSpec());
        TuiApp inside = new TuiApp(root, "qits tui", new CommandRunner("/bin/true")).inPlatform(true);
        List<String> names = inside.view().rows().stream().map(row -> row.name()).toList();
        assertThat(inside.view().rows()).filteredOn(row -> row.name().equals("login"))
                .singleElement().satisfies(row -> assertThat(row.dim()).isTrue());
        assertThat(names.indexOf("login")).isGreaterThan(names.indexOf("ci"));

        TuiApp outside = new TuiApp(root, "qits tui", new CommandRunner("/bin/true"));
        assertThat(outside.view().rows()).filteredOn(row -> row.name().equals("login"))
                .singleElement().satisfies(row -> assertThat(row.dim()).isFalse());
    }

    @Test
    void theCliDeclaresWhichCommandsOpenABrowser() {
        CommandNode root = CommandNode.of(new CommandLine(new AccessCli()).getCommandSpec());
        assertThat(root.child("login").interaction()).isEqualTo(Interaction.BROWSER);
        assertThat(root.child("git-login").interaction()).isEqualTo(Interaction.BROWSER);
    }
}
