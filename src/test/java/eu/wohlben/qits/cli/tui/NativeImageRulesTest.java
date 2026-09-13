package eu.wohlben.qits.cli.tui;

import org.jline.terminal.spi.TerminalProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the native binary needs, proven here rather than found out during a twenty-minute compile.
 * <p>
 * The one thing that has broken this before in the estate is JLine's provider set: the aggregate
 * {@code jline} artifact carries providers that call libc through JNI or the foreign function API,
 * and GraalVM's analysis stops on both. {@code jline-terminal} alone carries {@code exec} and
 * {@code dumb}, and the TUI asks for {@code exec} by name.
 */
class NativeImageRulesTest {

    private static final List<String> FORBIDDEN = List.of(
            "org.jline.terminal.impl.jni.JniTerminalProvider",
            "org.jline.terminal.impl.ffm.FfmTerminalProvider",
            "org.jline.terminal.impl.jansi.JansiTerminalProvider",
            "org.jline.terminal.impl.jna.JnaTerminalProvider");

    @Test
    void noProviderThatGraalCannotAnalyseIsOnTheClasspath() {
        for (String provider : FORBIDDEN) {
            assertThat(reachable(provider))
                    .as(provider + " is on the classpath — depend on jline-terminal, never the aggregate jline")
                    .isFalse();
        }
    }

    @Test
    void theExecProviderIsThere() {
        assertThat(reachable("org.jline.terminal.impl.exec.ExecTerminalProvider")).isTrue();
        assertThat(TerminalProvider.class).isNotNull();
    }

    private static boolean reachable(String className) {
        try {
            Class.forName(className, false, NativeImageRulesTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError notThere) {
            return false;
        }
    }
}
