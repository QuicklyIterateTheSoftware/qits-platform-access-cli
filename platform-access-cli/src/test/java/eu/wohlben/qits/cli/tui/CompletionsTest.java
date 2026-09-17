package eu.wohlben.qits.cli.tui;

import eu.wohlben.qits.cli.tui.api.Completes;
import eu.wohlben.qits.cli.tui.api.CompletionSource;
import eu.wohlben.qits.cli.tui.complete.Completions;
import eu.wohlben.qits.cli.tui.model.CommandNode;
import eu.wohlben.qits.cli.tui.run.CommandRunner;
import eu.wohlben.qits.cli.tui.screen.Frame;
import eu.wohlben.qits.cli.tui.screen.Glyphs;
import eu.wohlben.qits.cli.tui.screen.Key;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sources, their dependencies and their cache, with stand-in sources: what is proven is the
 * contract and the screen, not any call to the platform.
 */
class CompletionsTest {

    static final AtomicInteger THINGS = new AtomicInteger();
    static final AtomicInteger PARTS = new AtomicInteger();
    static volatile boolean partsFail;

    public static class ThingSource implements CompletionSource {

        @Override
        public List<Choice> choices(Map<String, String> chosen) {
            THINGS.incrementAndGet();
            return List.of(new Choice("one", "one  the first"), new Choice("two", "two  the second"));
        }
    }

    public static class PartSource implements CompletionSource {

        @Override
        public Set<String> dependsOn() {
            return Set.of("thing");
        }

        @Override
        public List<Choice> choices(Map<String, String> chosen) {
            PARTS.incrementAndGet();
            if (partsFail) {
                throw new IllegalStateException("401");
            }
            return List.of(Choice.of(chosen.get("thing") + "-a"), Choice.of(chosen.get("thing") + "-b"));
        }
    }

    public static class LoopA implements CompletionSource {

        @Override
        public Set<String> dependsOn() {
            return Set.of("b");
        }

        @Override
        public List<Choice> choices(Map<String, String> chosen) {
            return List.of();
        }
    }

    public static class LoopB implements CompletionSource {

        @Override
        public Set<String> dependsOn() {
            return Set.of("a");
        }

        @Override
        public List<Choice> choices(Map<String, String> chosen) {
            return List.of();
        }
    }

    @CommandLine.Command(name = "leaf", description = "A leaf.")
    static class Leaf {

        // Declared below --part on purpose: the screen has to put it above, and only the source's
        // dependsOn() says so.
        @Completes(PartSource.class)
        @CommandLine.Option(names = "--part", description = "A part of the thing.")
        String part;

        @Completes(ThingSource.class)
        @CommandLine.Option(names = "--thing", description = "The thing.")
        String thing;

        @Completes(ThingSource.class)
        @CommandLine.Option(names = "--secret", interactive = true, description = "Typed, not shown.")
        String secret;
    }

    @CommandLine.Command(name = "circle", description = "Sources that chase each other.")
    static class Circle {

        @Completes(LoopA.class)
        @CommandLine.Option(names = "--a", description = "One.")
        String a;

        @Completes(LoopB.class)
        @CommandLine.Option(names = "--b", description = "The other.")
        String b;
    }

    @CommandLine.Command(name = "qits", subcommands = Leaf.class, description = "The root.")
    static class Root {
    }

    @CommandLine.Command(name = "qits", subcommands = Circle.class, description = "The root.")
    static class CircleRoot {
    }

    private static final Completions.Resolver RESOLVER = type -> {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException impossible) {
            throw new IllegalStateException(impossible);
        }
    };

    private final CommandNode root = CommandNode.of(new CommandLine(new Root()).getCommandSpec());
    private final TuiApp app = new TuiApp(root, "qits tui", new CommandRunner("/bin/true"),
            new Completions(RESOLVER));
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

    /** The fetch is on another thread; the screen collects it when it paints. */
    private void settled() {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (lines().stream().anyMatch(line -> line.contains(TuiApp.LOADING))) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the source never answered");
            }
            sleep();
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private void on(String name) {
        press(Key.Kind.ENTER);
        jump(name);
    }

    private void jump(String name) {
        for (int i = 0; i < 20; i++) {
            if (lines().stream().anyMatch(line -> line.contains(name) && line.contains("▸"))) {
                return;
            }
            press(Key.Kind.DOWN);
        }
        throw new AssertionError("no row " + name);
    }

    @Test
    void aDependencyIsListedAboveTheRowThatNeedsIt() {
        press(Key.Kind.ENTER);
        List<String> rows = lines().stream().filter(line -> line.contains("--")).toList();
        assertThat(rows.getFirst()).contains("--thing");
        assertThat(rows.get(1)).contains("--part");
    }

    @Test
    void aSourceFillsTheListAndTypingSearchesTheLabel() {
        THINGS.set(0);
        on("--thing");
        press(Key.Kind.ENTER);
        settled();
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("one  the first"));
        type("second");
        assertThat(lines().stream().filter(line -> line.contains("the first")).toList()).isEmpty();
        press(Key.Kind.ENTER);
        assertThat(app.selection().commandLine()).isEqualTo("qits leaf --thing two");
        assertThat(THINGS.get()).isEqualTo(1);
    }

    @Test
    void aReopenedDropdownIsInstantAndCtrlLAsksAgain() {
        THINGS.set(0);
        on("--thing");
        press(Key.Kind.ENTER);
        settled();
        press(Key.Kind.ESCAPE);
        press(Key.Kind.ENTER);
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("one  the first"));
        assertThat(lines()).noneSatisfy(line -> assertThat(line).contains(TuiApp.LOADING));
        assertThat(THINGS.get()).isEqualTo(1);

        press(Key.Kind.CTRL_L);
        settled();
        assertThat(THINGS.get()).isEqualTo(2);
    }

    @Test
    void aPickerWhoseDependencyIsUnsetCallsNothingAndSaysWhatToPick() {
        PARTS.set(0);
        on("--part");
        press(Key.Kind.ENTER);
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("pick --thing first"));
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("--thing").contains("▸"));
        assertThat(PARTS.get()).isZero();
    }

    @Test
    void pickingAProjectNarrowsWhatDependsOnItAndChangingItClearsThat() {
        PARTS.set(0);
        on("--thing");
        press(Key.Kind.ENTER);
        settled();
        press(Key.Kind.ENTER);
        assertThat(app.selection().value("thing")).isEqualTo("one");

        jump("--part");
        press(Key.Kind.ENTER);
        settled();
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("one-a"));
        press(Key.Kind.ENTER);
        assertThat(app.selection().commandLine()).isEqualTo("qits leaf --thing one --part one-a");

        jump("--thing");
        press(Key.Kind.ENTER);
        press(Key.Kind.DOWN);
        press(Key.Kind.ENTER);
        assertThat(app.selection().value("thing")).isEqualTo("two");
        assertThat(app.selection().value("part")).as("a part of the old thing is not a part of this one").isNull();

        jump("--part");
        press(Key.Kind.ENTER);
        settled();
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("two-a"));
        assertThat(PARTS.get()).as("a different thing is a fresh call").isEqualTo(2);
    }

    @Test
    void aSourceThatFailsBecomesAFieldWithItsReasonOnScreen() {
        partsFail = true;
        try {
            on("--thing");
            press(Key.Kind.ENTER);
            settled();
            press(Key.Kind.ENTER);
            jump("--part");
            press(Key.Kind.ENTER);
            settled();
            assertThat(lines()).anySatisfy(line -> assertThat(line).contains("could not list --part: 401"));
            type("typed by hand");
            press(Key.Kind.ENTER);
            assertThat(app.selection().value("part")).isEqualTo("typed by hand");
        } finally {
            partsFail = false;
        }
    }

    @Test
    void aSecretIsNeverLookedUp() {
        THINGS.set(0);
        on("--secret");
        press(Key.Kind.ENTER);
        type("hunter2");
        assertThat(lines()).noneSatisfy(line -> assertThat(line).contains("hunter2"));
        assertThat(THINGS.get()).isZero();
    }

    @Test
    void sourcesThatChaseEachOtherAreRefusedAtStartup() {
        CommandNode circle = CommandNode.of(new CommandLine(new CircleRoot()).getCommandSpec());
        assertThatThrownBy(() -> new TuiApp(circle, "qits tui", new CommandRunner("/bin/true"),
                new Completions(RESOLVER)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("circle");
    }
}
