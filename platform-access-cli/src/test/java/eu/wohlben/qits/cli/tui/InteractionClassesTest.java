package eu.wohlben.qits.cli.tui;

import eu.wohlben.qits.cli.access.AccessCli;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.Output;
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

/** What {@code @TuiCommand} makes the screen do, and nothing more than that. */
class InteractionClassesTest {

    @eu.wohlben.qits.cli.tui.api.TuiCommand(interaction = Interaction.STREAMING)
    @CommandLine.Command(name = "flowing", description = "Runs until stopped.")
    static class Flowing {
    }

    @eu.wohlben.qits.cli.tui.api.TuiCommand(interaction = Interaction.BROWSER)
    @CommandLine.Command(name = "opener", description = "Opens a browser.")
    static class Opener {
    }

    @eu.wohlben.qits.cli.tui.api.TuiCommand(interaction = Interaction.CI_ONLY)
    @CommandLine.Command(name = "instep", description = "Only in a CI step.")
    static class InStep {
    }

    @eu.wohlben.qits.cli.tui.api.TuiCommand(output = Output.JSON)
    @CommandLine.Command(name = "asjson", description = "Answers as JSON.")
    static class AsJson {

        @CommandLine.Option(names = "--output", description = "table or json.")
        String output;
    }

    @CommandLine.Command(name = "quiet", description = "Says nothing about itself.")
    static class Quiet {
    }

    @CommandLine.Command(name = "qits", description = "The root.",
            subcommands = {Flowing.class, Opener.class, InStep.class, AsJson.class, Quiet.class})
    static class Root {
    }

    private final CommandNode root = CommandNode.of(new CommandLine(new Root()).getCommandSpec());
    private final CommandRunner runner = new CommandRunner("/bin/true");
    private final TuiApp app = new TuiApp(root, "qits tui", runner);
    private final Frame frame = new Frame(Glyphs.UNICODE);

    private List<String> lines() {
        return frame.render(app.view(), 80, 24).stream().map(AttributedString::toString).toList();
    }

    private void enter(String name) {
        while (app.selection().depth() > 0) {
            app.key(Key.of(Key.Kind.ESCAPE));
        }
        for (int i = 0; i < 20; i++) {
            if (lines().stream().anyMatch(line -> line.contains(name) && line.contains("▸"))) {
                app.key(Key.of(Key.Kind.ENTER));
                return;
            }
            app.key(Key.of(Key.Kind.DOWN));
        }
        throw new AssertionError("no row " + name);
    }

    @Test
    void anUnannotatedCommandIsPlainTextAndStillWorks() {
        assertThat(root.child("quiet").interaction()).isEqualTo(Interaction.PLAIN);
        assertThat(root.child("quiet").output()).isEqualTo(Output.TEXT);
        enter("quiet");
        app.key(Key.of(Key.Kind.CTRL_R));
        assertThat(app.history().entries()).singleElement()
                .satisfies(entry -> assertThat(entry.commandLine()).isEqualTo("qits quiet"));
    }

    @Test
    void aStreamingCommandSaysSoAndOffersTheStopKey() {
        enter("flowing");
        app.key(Key.of(Key.Kind.CTRL_R));
        assertThat(runner.title()).isEqualTo("streaming");
    }

    @Test
    void aBrowserCommandSaysItOpensOneAndStillRuns() {
        enter("opener");
        app.key(Key.of(Key.Kind.CTRL_R));
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("this opens a browser"));
        assertThat(app.history().entries()).hasSize(1);
    }

    @Test
    void aCiOnlyCommandIsSortedLastAndAsksBeforeItRuns() {
        List<String> names = app.view().rows().stream().map(row -> row.name()).toList();
        assertThat(names).endsWith("instep");
        assertThat(app.view().rows().getLast().dim()).isTrue();

        enter("instep");
        app.key(Key.of(Key.Kind.CTRL_R));
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("run outside CI? y/n"));
        assertThat(app.history().entries()).isEmpty();

        app.key(Key.character('n'));
        assertThat(app.history().entries()).isEmpty();

        app.key(Key.of(Key.Kind.CTRL_R));
        app.key(Key.character('y'));
        assertThat(app.history().entries()).singleElement()
                .satisfies(entry -> assertThat(entry.commandLine()).isEqualTo("qits instep"));
    }

    @Test
    void anOutputFormIsPutOnTheCommandsOwnOutputOption() {
        enter("asjson");
        assertThat(app.selection().commandLine()).isEqualTo("qits asjson --output json");
    }

    @Test
    void theRealCliDeclaresTheClassesTheEpicNames() {
        CommandNode qits = CommandNode.of(new CommandLine(new AccessCli()).getCommandSpec());
        assertThat(qits.child("events").interaction()).isEqualTo(Interaction.STREAMING);
        assertThat(qits.child("observe").interaction()).isEqualTo(Interaction.STREAMING);
        assertThat(qits.child("session-daemon").interaction()).isEqualTo(Interaction.STREAMING);
        assertThat(qits.child("login").interaction()).isEqualTo(Interaction.BROWSER);
        assertThat(qits.child("git-login").interaction()).isEqualTo(Interaction.BROWSER);
        assertThat(qits.child("artifacts").child("publish").interaction()).isEqualTo(Interaction.CI_ONLY);
        assertThat(qits.child("ci").interaction()).isEqualTo(Interaction.PLAIN);
    }
}
