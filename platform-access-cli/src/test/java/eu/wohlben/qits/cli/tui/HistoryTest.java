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

import static org.assertj.core.api.Assertions.assertThat;

/** {@code ⌃P}: what ran this session, and what it does not hold. */
class HistoryTest {

    @CommandLine.Command(name = "leaf", description = "A leaf.")
    static class Leaf {

        @CommandLine.Option(names = "--thing", description = "A value.")
        String thing;

        @CommandLine.Option(names = "--secret", interactive = true, description = "Typed, not shown.")
        String secret;
    }

    @CommandLine.Command(name = "qits", subcommands = Leaf.class, description = "The root.")
    static class Root {
    }

    private final CommandNode root = CommandNode.of(new CommandLine(new Root()).getCommandSpec());
    private final CommandRunner runner = new CommandRunner("/bin/true");
    private final TuiApp app = new TuiApp(root, "qits tui", runner);
    private final Frame frame = new Frame(Glyphs.UNICODE);

    private List<String> lines() {
        return frame.render(app.view(), 80, 24).stream().map(AttributedString::toString).toList();
    }

    private void press(Key.Kind kind) {
        app.key(Key.of(kind));
    }

    private void intoLeafAndRun(String thing) {
        while (app.selection().depth() > 0) {
            press(Key.Kind.ESCAPE);
        }
        press(Key.Kind.ENTER);
        app.selection().set("thing", thing);
        press(Key.Kind.CTRL_R);
    }

    @Test
    void nothingHasRunYetSaysSo() {
        press(Key.Kind.CTRL_P);
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("nothing has run yet"));
    }

    @Test
    void newestFirstAndOneRowPerCommand() {
        intoLeafAndRun("one");
        intoLeafAndRun("two");
        press(Key.Kind.CTRL_P);
        List<String> rows = lines().stream()
                .filter(line -> line.startsWith(" \u2502") && line.contains("qits leaf --thing")).toList();
        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst()).contains("two");
        assertThat(rows.get(1)).contains("one");
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("history"));
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("⏎ run again"));
    }

    @Test
    void escapeClosesItAndLeavesThePickerWhereItWas() {
        intoLeafAndRun("one");
        press(Key.Kind.CTRL_P);
        press(Key.Kind.ESCAPE);
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("--thing"));
        assertThat(app.selection().commandLine()).isEqualTo("qits leaf --thing one");
    }

    @Test
    void eLoadsAnEarlierCommandBackIntoThePickerWithoutRunningIt() {
        intoLeafAndRun("one");
        intoLeafAndRun("two");
        press(Key.Kind.CTRL_P);
        app.key(Key.of(Key.Kind.DOWN));
        app.key(Key.character('e'));
        assertThat(app.selection().commandLine()).isEqualTo("qits leaf --thing one");
        assertThat(app.history().entries()).hasSize(2);
    }

    @Test
    void enterRunsAnEarlierCommandAgain() {
        intoLeafAndRun("one");
        intoLeafAndRun("two");
        press(Key.Kind.CTRL_P);
        app.key(Key.of(Key.Kind.DOWN));
        press(Key.Kind.ENTER);
        assertThat(app.selection().commandLine()).isEqualTo("qits leaf --thing one");
        assertThat(app.history().entries()).hasSize(2);
        assertThat(app.history().entries().getFirst().commandLine()).isEqualTo("qits leaf --thing one");
    }

    @Test
    void aValueTypedIntoAHiddenFieldNeverEntersTheBuffer() {
        press(Key.Kind.ENTER);
        app.selection().set("thing", "shown");
        app.selection().set("secret", "hunter2");
        press(Key.Kind.CTRL_R);
        assertThat(app.history().entries()).singleElement().satisfies(entry -> {
            assertThat(entry.commandLine()).isEqualTo("qits leaf --thing shown");
            assertThat(entry.values()).doesNotContainKey("secret");
        });
        press(Key.Kind.CTRL_P);
        assertThat(lines()).noneSatisfy(line -> assertThat(line).contains("hunter2"));
    }
}
