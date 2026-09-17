package eu.wohlben.qits.cli.tui;

import eu.wohlben.qits.cli.tui.model.CommandNode;
import eu.wohlben.qits.cli.tui.run.CommandRunner;
import eu.wohlben.qits.cli.tui.screen.Frame;
import eu.wohlben.qits.cli.tui.screen.Glyphs;
import eu.wohlben.qits.cli.tui.screen.Key;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Running a command as a child, with {@code /bin/sh} standing in for this binary: what is proven is
 * the fork, the output box and the stop, not any command.
 */
class RunnerTest {

    @CommandLine.Command(name = "leaf", description = "A leaf.")
    static class Leaf {

        @CommandLine.Option(names = "--needed", required = true, description = "Must be given.")
        String needed;
    }

    @CommandLine.Command(name = "qits", subcommands = Leaf.class, description = "The root.")
    static class Root {
    }

    private final CommandNode root = CommandNode.of(new CommandLine(new Root()).getCommandSpec());
    private final CommandRunner runner = new CommandRunner("/bin/sh");
    private final TuiApp app = new TuiApp(root, "qits tui", runner);
    private final Frame frame = new Frame(Glyphs.UNICODE);

    private List<String> lines() {
        return frame.render(app.view(), 80, 24).stream().map(AttributedString::toString).toList();
    }

    /** A child is another process, so waiting for it is polling. Ten seconds is a failing test. */
    private static void until(BooleanSupplier done) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the child did not get there in ten seconds");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }
    }

    private void settled(CommandRunner runner, String title) {
        until(() -> title.equals(runner.title()));
    }

    @Test
    void aFinishedCommandShowsItsOutputAndItsExitCode() {
        runner.start(List.of("-c", "echo first; echo second >&2; exit 2"), false);
        assertThat(runner.title()).isEqualTo("running");
        settled(runner, "exit 2");
        assertThat(runner.lines()).contains("first", "second");
    }

    @Test
    void bothStreamsLandInTheOneBox() {
        runner.start(List.of("-c", "echo out; echo err >&2"), false);
        settled(runner, "exit 0");
        assertThat(runner.lines()).containsExactlyInAnyOrder("out", "err");
    }

    @Test
    void theChildIsToldNotToPaint() {
        runner.start(List.of("-c", "echo \"tui=$QITS_TUI\""), false);
        settled(runner, "exit 0");
        assertThat(runner.lines()).containsExactly("tui=0");
    }

    @Test
    void aStreamKeepsRunningUntilItIsStopped() {
        runner.start(List.of("-c", "while true; do echo tick; sleep 0.05; done"), true);
        assertThat(runner.title()).isEqualTo("streaming");
        until(() -> !runner.lines().isEmpty());
        assertThat(runner.running()).isTrue();
        runner.stop();
        settled(runner, "stopped");
        assertThat(runner.running()).isFalse();
    }

    @Test
    void stoppingLeavesTheTuiOnTheScreen() {
        app.key(Key.of(Key.Kind.ENTER));
        app.selection().set("needed", "x");
        runner.start(List.of("-c", "sleep 30"), true);
        assertThat(app.key(Key.of(Key.Kind.CTRL_C))).isTrue();
        assertThat(app.running()).isTrue();
    }

    @Test
    void runIsRefusedWhileARequiredOptionIsUnsetAndTheListJumpsToIt() {
        app.key(Key.of(Key.Kind.ENTER));
        app.key(Key.of(Key.Kind.CTRL_R));
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("--needed is required"));
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("--needed").contains("▸"));
        assertThat(runner.title()).isEmpty();
    }

    @Test
    void theOutputBoxCarriesTheTitleAndTheLines() {
        app.key(Key.of(Key.Kind.ENTER));
        app.selection().set("needed", "x");
        runner.start(List.of("-c", "echo hello"), false);
        settled(runner, "exit 0");
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("output").contains("exit 0"));
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("hello"));
    }

    @Test
    void aSecretGoesOnTheChildsInputAndNeverIntoItsArgv() {
        runner.start(List.of("-c", "read answer; echo \"got $answer\"; echo \"argv=$*\"", "sh", "--secret"),
                false, List.of("hunter2"));
        settled(runner, "exit 0");
        assertThat(runner.lines()).contains("got hunter2", "argv=--secret");
    }

    @Test
    void terminalControlCharactersNeverReachTheScreen() {
        runner.start(List.of("-c", "printf 'a\\033[31mb\\n'"), false);
        settled(runner, "exit 0");
        assertThat(runner.lines()).singleElement().isEqualTo("ab");
    }
}
