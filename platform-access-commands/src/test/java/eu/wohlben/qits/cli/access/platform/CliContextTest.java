package eu.wohlben.qits.cli.access.platform;

import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.session.AgentCredential;
import eu.wohlben.qits.cli.session.Credential;
import eu.wohlben.qits.cli.session.Mode;
import eu.wohlben.qits.cli.session.RequestCredential;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Which credential a context calls the platform with, and the one place it may not be had by default. */
class CliContextTest {

    private final FakeTime time = new FakeTime(Instant.parse("2026-10-01T10:00:00Z"));

    @AfterEach
    void clear() {
        System.clearProperty(CliContext.MCP_SERVICE);
    }

    private CliContext context(Map<String, String> env) {
        PrintStream nowhere = new PrintStream(OutputStream.nullOutputStream());
        return new CliContext(env, InputStream.nullInputStream(), nowhere, nowhere, time, time,
                TokenClient::new, stop -> { });
    }

    @Test
    void withoutOneOfItsOwnTheModeChooses() {
        assertThat(context(Map.of("XDG_CONFIG_HOME", "/nonexistent")).credential()).isInstanceOf(AccessTokens.class);
        assertThat(context(Map.of(Mode.CLIENT_ID, "id", Mode.CLIENT_SECRET, "secret")).credential())
                .isInstanceOf(AgentCredential.class);
    }

    @Test
    void aCarriedCredentialWinsOverTheMode() throws Exception {
        Credential caller = new RequestCredential("the caller's token");
        CliContext inside = context(Map.of(Mode.CLIENT_ID, "id", Mode.CLIENT_SECRET, "secret")).withCredential(caller);
        CliContext outside = context(Map.of("XDG_CONFIG_HOME", "/nonexistent")).withCredential(caller);

        assertThat(inside.credential()).isSameAs(caller);
        assertThat(outside.credential()).isSameAs(caller);
        assertThat(outside.credential().bearer()).isEqualTo("the caller's token");
    }

    @Test
    void theProcessContextIsRefusedInsideTheMcpService() {
        assertThat(CliContext.system()).isNotNull();

        System.setProperty(CliContext.MCP_SERVICE, "true");

        assertThatThrownBy(CliContext::system)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(CliContext.MCP_SERVICE);
    }

    /** The fallback a command takes when nobody handed it a context is the one the marker shuts. */
    @Test
    void aCommandWithoutAContextFailsLoudlyInsideTheMcpService() {
        PlatformCommand command = new PlatformCommand() {
            @Override
            protected int execute(CliContext context) {
                return 0;
            }
        };
        System.setProperty(CliContext.MCP_SERVICE, "");

        assertThatThrownBy(command::call).isInstanceOf(IllegalStateException.class);
    }
}
