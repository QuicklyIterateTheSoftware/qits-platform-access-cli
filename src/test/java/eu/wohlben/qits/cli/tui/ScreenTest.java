package eu.wohlben.qits.cli.tui;

import eu.wohlben.qits.cli.tui.model.CommandNode;
import eu.wohlben.qits.cli.tui.screen.Frame;
import eu.wohlben.qits.cli.tui.screen.Glyphs;
import eu.wohlben.qits.cli.tui.screen.Key;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The screen at 80x24, as lines. No terminal: the frame is a function of the app's state. */
class ScreenTest {

    @CommandLine.Command(name = "runs", description = "List the runs.")
    static class Runs {

        @CommandLine.Option(names = "--project", required = true, description = "The project.")
        String project;

        @CommandLine.Option(names = "--limit", defaultValue = "20", description = "How many.")
        int limit;
    }

    @CommandLine.Command(name = "builds", description = "The builds.", subcommands = Runs.class)
    static class Builds {
    }

    @CommandLine.Command(name = "qits", mixinStandardHelpOptions = true, subcommands = Builds.class,
            description = "The root.")
    static class Root {
    }

    private static final int WIDTH = 80;
    private static final int HEIGHT = 24;

    private final CommandNode root = CommandNode.of(new CommandLine(new Root()).getCommandSpec());
    private final TuiApp app = new TuiApp(root, "qits tui · dev.wohlben.eu · signed in as jan");
    private final Frame frame = new Frame(Glyphs.UNICODE);

    private List<String> lines() {
        return frame.render(app.view(), WIDTH, HEIGHT).stream().map(AttributedString::toString).toList();
    }

    private void press(Key.Kind kind) {
        app.key(Key.of(kind));
    }

    private void type(char ch) {
        app.key(Key.character(ch));
    }

    @Test
    void fillsTheTerminalExactly() {
        List<String> lines = lines();
        assertThat(lines).hasSize(HEIGHT);
        assertThat(lines).allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(WIDTH));
        assertThat(lines.getFirst()).contains("signed in as jan");
    }

    @Test
    void theRootShowsTheCommandsAndTheirHelp() {
        List<String> lines = lines();
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("builds").contains("The builds."));
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("builds").contains("▸"));
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("$ qits"));
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("↑↓ move"));
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("output"));
    }

    @Test
    void drillingInGrowsTheCommandLineAndBackShrinksIt() {
        press(Key.Kind.ENTER);
        press(Key.Kind.ENTER);
        assertThat(app.selection().commandLine()).isEqualTo("qits builds runs");
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("$ qits builds runs"));
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("builds ▸ runs"));
        press(Key.Kind.ESCAPE);
        assertThat(app.selection().commandLine()).isEqualTo("qits builds");
    }

    @Test
    void requiredRowsAreMarkedAndComeFirst() {
        press(Key.Kind.ENTER);
        press(Key.Kind.ENTER);
        List<String> rows = lines().stream().filter(line -> line.contains("--")).toList();
        assertThat(rows.getFirst()).contains("* --project");
        assertThat(rows.get(1)).contains("--limit").contains("20");
    }

    @Test
    void slashFiltersTheListAndEscapeClearsIt() {
        press(Key.Kind.ENTER);
        press(Key.Kind.ENTER);
        type('/');
        type('l');
        type('i');
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("/li"));
        assertThat(lines().stream().filter(line -> line.contains("--")).toList())
                .singleElement().asString().contains("--limit");
        press(Key.Kind.ESCAPE);
        assertThat(lines().stream().filter(line -> line.contains("--")).toList()).hasSize(2);
    }

    @Test
    void qQuitsButNotWhileTheFilterIsOpen() {
        type('/');
        type('q');
        assertThat(app.running()).isTrue();
        press(Key.Kind.ESCAPE);
        type('q');
        assertThat(app.running()).isFalse();
    }

    @Test
    void kAndJMoveTheSelection() {
        press(Key.Kind.ENTER);
        press(Key.Kind.ENTER);
        type('j');
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("--limit").contains("▸"));
        type('k');
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("--project").contains("▸"));
    }

    @Test
    void theAsciiFormHasNoUnicodeAtAll() {
        List<String> ascii = new Frame(Glyphs.ASCII).render(app.view(), WIDTH, HEIGHT).stream()
                .map(AttributedString::toString).toList();
        assertThat(ascii).allSatisfy(line ->
                assertThat(line.chars().allMatch(c -> c < 128)).as(line).isTrue());
        assertThat(ascii).anySatisfy(line -> assertThat(line).contains("builds").contains(">"));
    }

    @Test
    void aTerminalTooSmallIsRefusedInOneLine() {
        assertThat(Frame.fits(79, 24)).isFalse();
        assertThat(Frame.fits(80, 23)).isFalse();
        assertThat(Frame.fits(80, 24)).isTrue();
        assertThat(Frame.tooSmall(60, 20)).isEqualTo("qits tui needs at least 80x24 and this terminal is 60x20.");
    }

    @Test
    void aCommandLineLongEnoughToFillTheScreenStillLeavesBothBoxes() {
        press(Key.Kind.ENTER);
        press(Key.Kind.ENTER);
        app.selection().set("project", "x".repeat(4000));
        List<String> lines = lines();
        assertThat(lines).hasSize(HEIGHT);
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("↑↓ move"));
        assertThat(lines).anySatisfy(line -> assertThat(line).contains(" output "));
    }

    @Test
    void aTallTerminalStillSplitsInHalf() {
        List<String> lines = frame.render(app.view(), 120, 50).stream()
                .map(AttributedString::toString).toList();
        assertThat(lines).hasSize(50);
        int outputTop = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(" output ")) {
                outputTop = i;
            }
        }
        assertThat(outputTop).isEqualTo(50 - 25);
    }
}
