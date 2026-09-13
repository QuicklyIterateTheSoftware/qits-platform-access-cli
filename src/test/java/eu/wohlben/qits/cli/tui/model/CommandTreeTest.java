package eu.wohlben.qits.cli.tui.model;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The model, against a spec this test assembles. Not against the real CLI tree: what is proven here
 * is that picocli's model is read correctly, and that stays true when a command is added.
 */
class CommandTreeTest {

    enum Colour {RED, GREEN}

    @CommandLine.Command(name = "leaf", description = "A leaf command.")
    static class Leaf {

        @CommandLine.Parameters(index = "0", paramLabel = "<id>", description = "The thing.")
        String id;

        @CommandLine.Option(names = "--needed", required = true, description = "Must be given.")
        String needed;

        @CommandLine.Option(names = "--limit", defaultValue = "20", description = "How many.")
        int limit;

        @CommandLine.Option(names = "--colour", description = "Which colour.")
        Colour colour;

        @CommandLine.Option(names = "--loud", description = "A flag.")
        boolean loud;

        @CommandLine.Option(names = "--secret", interactive = true, description = "Typed, not shown.")
        String secret;

        @CommandLine.Option(names = "--inner", hidden = true, description = "Not for people.")
        String inner;
    }

    @CommandLine.Command(name = "group", description = "A group.", subcommands = Leaf.class)
    static class Group {
    }

    @CommandLine.Command(name = "qits", mixinStandardHelpOptions = true, subcommands = Group.class,
            description = "The root.")
    static class Root {
    }

    private CommandNode root() {
        return CommandNode.of(new CommandLine(new Root()).getCommandSpec());
    }

    @Test
    void readsTheSubcommandsAndTheirHelp() {
        CommandNode root = root();
        assertThat(root.name()).isEqualTo("qits");
        assertThat(root.children()).extracting(CommandNode::name).containsExactly("group");
        assertThat(root.child("group").description()).isEqualTo("A group.");
        assertThat(root.leaf()).isFalse();
    }

    @Test
    void leavesOutHelpAndHiddenOptions() {
        CommandNode leaf = root().child("group").child("leaf");
        assertThat(leaf.rows()).extracting(OptionRow::name)
                .doesNotContain("--help", "--version", "--inner");
    }

    @Test
    void positionalsFirstThenRequiredThenOptional() {
        CommandNode leaf = root().child("group").child("leaf");
        List<String> names = leaf.rows().stream().map(OptionRow::name).toList();
        assertThat(names).startsWith("<id>", "--needed");
        assertThat(names).contains("--limit", "--colour", "--loud", "--secret");
        assertThat(leaf.row("id").positional()).isTrue();
        assertThat(leaf.row("needed").required()).isTrue();
        assertThat(leaf.row("limit").defaultValue()).isEqualTo("20");
    }

    @Test
    void anEnumCarriesItsConstantsAndABooleanIsAFlag() {
        CommandNode leaf = root().child("group").child("leaf");
        assertThat(leaf.row("colour").choices()).containsExactly("RED", "GREEN");
        assertThat(leaf.row("loud").flag()).isTrue();
        assertThat(leaf.row("secret").interactive()).isTrue();
    }

    @Test
    void theCommandLineGrowsWithEveryChoice() {
        CommandNode root = root();
        Selection selection = new Selection(root);
        assertThat(selection.commandLine()).isEqualTo("qits");
        selection.enter(root.child("group"));
        selection.enter(root.child("group").child("leaf"));
        assertThat(selection.commandLine()).isEqualTo("qits group leaf");
        selection.set("needed", "a value");
        selection.set("id", "42");
        selection.set("loud", "true");
        assertThat(selection.commandLine()).isEqualTo("qits group leaf 42 --needed 'a value' --loud");
        assertThat(selection.argv()).containsExactly("group", "leaf", "42", "--needed", "a value", "--loud");
    }

    @Test
    void aRequiredRowWithNoValueRefusesTheRun() {
        CommandNode root = root();
        Selection selection = new Selection(root);
        selection.enter(root.child("group"));
        selection.enter(root.child("group").child("leaf"));
        assertThat(selection.firstUnsetRequired()).isNotNull();
        assertThat(selection.firstUnsetRequired().name()).isEqualTo("<id>");
        selection.set("id", "42");
        selection.set("needed", "x");
        assertThat(selection.firstUnsetRequired()).isNull();
    }

    @Test
    void goingBackDropsTheValuesThatSegmentOwned() {
        CommandNode root = root();
        Selection selection = new Selection(root);
        selection.enter(root.child("group"));
        selection.enter(root.child("group").child("leaf"));
        selection.set("needed", "x");
        assertThat(selection.back()).isTrue();
        assertThat(selection.value("needed")).isNull();
        assertThat(selection.pathLine(" > ")).isEqualTo("group");
        assertThat(selection.back()).isTrue();
        assertThat(selection.back()).isFalse();
        assertThat(selection.pathLine(" > ")).isEqualTo("qits");
    }
}
