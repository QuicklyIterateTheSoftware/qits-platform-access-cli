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

/** Setting a value from the keyboard: the list for what has choices, a field for what does not. */
class EditingTest {

    enum Status {QUEUED, RUNNING, SUCCESS}

    @CommandLine.Command(name = "leaf", description = "A leaf.")
    static class Leaf {

        @CommandLine.Option(names = "--project", description = "Free text.")
        String project;

        @CommandLine.Option(names = "--status", description = "One of a few.")
        Status status;

        @CommandLine.Option(names = "--logs", description = "A flag.")
        boolean logs;

        @CommandLine.Option(names = "--secret", interactive = true, description = "Typed, not shown.")
        String secret;
    }

    @CommandLine.Command(name = "qits", subcommands = Leaf.class, description = "The root.")
    static class Root {
    }

    private final CommandNode root = CommandNode.of(new CommandLine(new Root()).getCommandSpec());
    private final TuiApp app = new TuiApp(root, "qits tui", new CommandRunner("/bin/true"));
    private final Frame frame = new Frame(Glyphs.UNICODE);

    private List<String> lines() {
        return frame.render(app.view(), 80, 24).stream().map(AttributedString::toString).toList();
    }

    private void press(Key.Kind kind) {
        app.key(Key.of(kind));
    }

    private void type(String text) {
        text.chars().forEach(ch -> app.key(Key.character((char) ch)));
    }

    /** Into the leaf, then onto the named row. */
    private void on(String name) {
        while (app.selection().depth() > 0) {
            press(Key.Kind.ESCAPE);
        }
        press(Key.Kind.ENTER);
        for (int i = 0; i < 20; i++) {
            if (lines().stream().anyMatch(line -> line.contains(name) && line.contains("▸"))) {
                return;
            }
            press(Key.Kind.DOWN);
        }
        throw new AssertionError("no row " + name);
    }

    @Test
    void anEnumOpensItsConstantsAsASearchableList() {
        on("--status");
        press(Key.Kind.ENTER);
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("┌ --status"));
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("QUEUED"));
        type("suc");
        assertThat(lines().stream().filter(line -> line.contains("QUEUED")).toList()).isEmpty();
        press(Key.Kind.ENTER);
        assertThat(app.selection().commandLine()).isEqualTo("qits leaf --status SUCCESS");
    }

    @Test
    void escapeLeavesAnEnumValueUnchanged() {
        on("--status");
        press(Key.Kind.ENTER);
        press(Key.Kind.DOWN);
        press(Key.Kind.ESCAPE);
        assertThat(app.selection().commandLine()).isEqualTo("qits leaf");
    }

    @Test
    void aFlagTogglesWithNoEditorAtAll() {
        on("--logs");
        press(Key.Kind.ENTER);
        assertThat(app.selection().commandLine()).isEqualTo("qits leaf --logs");
        press(Key.Kind.ENTER);
        assertThat(app.selection().commandLine()).isEqualTo("qits leaf");
    }

    @Test
    void freeTextOpensAFieldPreFilledWithWhatIsThere() {
        on("--project");
        press(Key.Kind.ENTER);
        type("qits");
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("qits_"));
        press(Key.Kind.ENTER);
        assertThat(app.selection().commandLine()).isEqualTo("qits leaf --project qits");

        press(Key.Kind.ENTER);
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("qits_"));
        press(Key.Kind.BACKSPACE);
        press(Key.Kind.ENTER);
        assertThat(app.selection().commandLine()).isEqualTo("qits leaf --project qit");
    }

    @Test
    void escapeLeavesAFieldUnchanged() {
        on("--project");
        press(Key.Kind.ENTER);
        type("typed");
        press(Key.Kind.ESCAPE);
        assertThat(app.selection().commandLine()).isEqualTo("qits leaf");
    }

    @Test
    void aSecretIsNeverEchoedAnywhere() {
        on("--secret");
        press(Key.Kind.ENTER);
        type("hunter2");
        assertThat(lines()).noneSatisfy(line -> assertThat(line).contains("hunter2"));
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("•••••••"));
        press(Key.Kind.ENTER);
        assertThat(lines()).noneSatisfy(line -> assertThat(line).contains("hunter2"));
        assertThat(app.selection().commandLine()).isEqualTo("qits leaf --secret");
        assertThat(app.selection().secrets()).containsExactly("hunter2");
    }

    @Test
    void qWhileAFieldIsOpenIsAQNotAQuit() {
        on("--project");
        press(Key.Kind.ENTER);
        type("q");
        assertThat(app.running()).isTrue();
        press(Key.Kind.ENTER);
        assertThat(app.selection().commandLine()).isEqualTo("qits leaf --project q");
    }
}
