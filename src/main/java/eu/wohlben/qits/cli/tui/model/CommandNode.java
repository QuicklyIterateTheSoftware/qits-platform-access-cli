package eu.wohlben.qits.cli.tui.model;

import eu.wohlben.qits.cli.tui.api.Completes;
import eu.wohlben.qits.cli.tui.api.CompletionSource;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.Output;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One command of the tree, as the screen shows it: a name, a line of help, the commands under it
 * and the rows a person fills in.
 * <p>
 * Built from picocli's {@link CommandSpec} and nothing else. This is the whole of the TUI's
 * knowledge about commands, and it holds no command's name in its source: a command added to the
 * CLI appears here because the spec has it.
 */
public final class CommandNode {

    private final String name;
    private final String description;
    private final List<CommandNode> children;
    private final List<OptionRow> rows;
    private final Interaction interaction;
    private final Output output;

    private CommandNode(String name, String description, List<CommandNode> children, List<OptionRow> rows,
                        Interaction interaction, Output output) {
        this.name = name;
        this.description = description;
        this.children = List.copyOf(children);
        this.rows = List.copyOf(rows);
        this.interaction = interaction;
        this.output = output;
    }

    /** The whole tree under {@code spec}, subcommands and all. */
    public static CommandNode of(CommandSpec spec) {
        TuiCommand declared = declaration(spec);
        return new CommandNode(spec.name(), firstLine(spec), children(spec), rows(spec),
                declared == null ? Interaction.PLAIN : declared.interaction(),
                declared == null ? Output.TEXT : declared.output());
    }

    /**
     * The command's own {@code @TuiCommand}, or null.
     * <p>
     * The superclasses are walked because a command is a CDI bean and what the container hands back
     * may be a generated subclass of the annotated class rather than the class itself.
     */
    private static TuiCommand declaration(CommandSpec spec) {
        Object command = spec.userObject();
        if (command == null) {
            return null;
        }
        for (Class<?> type = command.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            TuiCommand declared = type.getAnnotation(TuiCommand.class);
            if (declared != null) {
                return declared;
            }
        }
        return null;
    }

    /**
     * A subcommand appears once, under its primary name. picocli keys aliases into the same map, so
     * the map is walked by value and the spec's own {@code name()} decides.
     */
    private static List<CommandNode> children(CommandSpec spec) {
        Map<String, CommandSpec> byName = new LinkedHashMap<>();
        for (CommandLine child : spec.subcommands().values()) {
            CommandSpec childSpec = child.getCommandSpec();
            if (!childSpec.usageMessage().hidden()) {
                byName.putIfAbsent(childSpec.name(), childSpec);
            }
        }
        List<CommandNode> nodes = new ArrayList<>(byName.size());
        byName.values().forEach(child -> nodes.add(of(child)));
        return nodes;
    }

    /**
     * Positional parameters first, in their index order, then the options: required before
     * optional, each group in the order the command declares them. Stable, so the list does not
     * reshuffle under the reader between two paints.
     */
    private static List<OptionRow> rows(CommandSpec spec) {
        List<OptionRow> positionals = new ArrayList<>();
        for (PositionalParamSpec positional : spec.positionalParameters()) {
            if (!positional.hidden()) {
                positionals.add(row(positional, OptionRow.Kind.POSITIONAL, positional.paramLabel(),
                        completes(spec, null, positional.paramLabel())));
            }
        }
        List<OptionRow> options = new ArrayList<>();
        for (OptionSpec option : spec.options()) {
            if (option.hidden() || option.usageHelp() || option.versionHelp()) {
                continue;
            }
            options.add(row(option, OptionRow.Kind.OPTION, option.longestName(),
                    completes(spec, option.longestName(), null)));
        }
        options.sort(Comparator.comparing((OptionRow row) -> row.required() ? 0 : 1));
        List<OptionRow> all = new ArrayList<>(positionals);
        all.addAll(options);
        return all;
    }

    private static OptionRow row(ArgSpec arg, OptionRow.Kind kind, String name,
                                 Class<? extends CompletionSource> source) {
        return new OptionRow(kind, name, firstLine(arg.description()), arg.required(), arg.defaultValue(),
                typeName(arg), isFlag(arg), arg.interactive(), choices(arg),
                // A value typed into a hidden field is a secret, and a secret is never looked up.
                arg.interactive() ? null : source);
    }

    /**
     * The {@code @Completes} beside this argument's declaration, or null.
     * <p>
     * picocli's model does not carry the field an argument was read from, so the field is found
     * again — by the option's long name, or by a positional's label. The parent commands are
     * searched too, because an option declared with {@code ScopeType.INHERIT} appears in this spec
     * while its field lives on the command above.
     */
    private static Class<? extends CompletionSource> completes(CommandSpec spec, String optionName, String label) {
        for (CommandSpec owner = spec; owner != null; owner = owner.parent()) {
            Object command = owner.userObject();
            if (command == null) {
                continue;
            }
            for (Class<?> type = command.getClass(); type != null && type != Object.class;
                 type = type.getSuperclass()) {
                for (Field field : declaredFields(type)) {
                    if (!declares(field, optionName, label)) {
                        continue;
                    }
                    Completes completes = field.getAnnotation(Completes.class);
                    if (completes != null) {
                        return completes.value();
                    }
                }
            }
        }
        return null;
    }

    private static boolean declares(Field field, String optionName, String label) {
        if (optionName != null) {
            CommandLine.Option option = field.getAnnotation(CommandLine.Option.class);
            return option != null && Arrays.asList(option.names()).contains(optionName);
        }
        CommandLine.Parameters positional = field.getAnnotation(CommandLine.Parameters.class);
        return positional != null && positional.paramLabel().equals(label);
    }

    /** A native image without this class's fields registered simply has no completion here. */
    private static Field[] declaredFields(Class<?> type) {
        try {
            return type.getDeclaredFields();
        } catch (RuntimeException | LinkageError notInTheImage) {
            return new Field[0];
        }
    }

    /**
     * What the value may be, when the model already knows: an enum's constants, else whatever
     * {@code completionCandidates()} offers. Neither needs a call to the platform.
     */
    private static List<String> choices(ArgSpec arg) {
        Class<?> type = arg.type();
        if (type != null && type.isEnum()) {
            try {
                List<String> constants = new ArrayList<>();
                for (Object constant : type.getEnumConstants()) {
                    constants.add(String.valueOf(constant));
                }
                return constants;
            } catch (RuntimeException | LinkageError notInTheImage) {
                // getEnumConstants() reads values() reflectively, which a native image only allows
                // for a registered enum. An option whose type was left out is typed instead of
                // picked — a smaller screen, never a crash. See AGENTS.md, Native rules.
                return List.of();
            }
        }
        Iterable<String> candidates = arg.completionCandidates();
        if (candidates == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        candidates.forEach(values::add);
        return values;
    }

    /** A boolean option is a flag: it is turned on and off, never typed into. */
    private static boolean isFlag(ArgSpec arg) {
        Class<?> type = arg.type();
        return type == boolean.class || type == Boolean.class;
    }

    private static String typeName(ArgSpec arg) {
        Class<?> type = arg.type();
        return type == null ? "String" : type.getSimpleName();
    }

    private static String firstLine(CommandSpec spec) {
        return firstLine(spec.usageMessage().description());
    }

    private static String firstLine(String[] lines) {
        if (lines == null || lines.length == 0 || lines[0] == null) {
            return "";
        }
        return lines[0].replace("%n", " ").strip();
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public List<CommandNode> children() {
        return children;
    }

    /** The rows of this command. A command with subcommands usually has none of its own. */
    public List<OptionRow> rows() {
        return rows;
    }

    /** How this command behaves once started. {@link Interaction#PLAIN} unless it says otherwise. */
    public Interaction interaction() {
        return interaction;
    }

    /** Which {@code --output} value the TUI puts on this command, when it takes one. */
    public Output output() {
        return output;
    }

    /** A command with nothing under it: the list shows its rows instead of more commands. */
    public boolean leaf() {
        return children.isEmpty();
    }

    public CommandNode child(String childName) {
        return children.stream().filter(c -> c.name().equals(childName)).findFirst().orElse(null);
    }

    public OptionRow row(String key) {
        return rows.stream().filter(r -> r.key().equals(key)).findFirst().orElse(null);
    }

    @Override
    public String toString() {
        return "CommandNode[" + name + ", " + children.size() + " children, " + rows.size() + " rows]";
    }
}
