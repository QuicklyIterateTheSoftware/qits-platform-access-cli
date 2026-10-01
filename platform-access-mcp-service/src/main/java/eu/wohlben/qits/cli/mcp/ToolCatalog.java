package eu.wohlben.qits.cli.mcp;

import eu.wohlben.qits.cli.tui.api.Input;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import eu.wohlben.qits.cli.tui.api.TuiCommands;
import picocli.CommandLine;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * The tools, read off the picocli tree and nothing else.
 * <p>
 * One tool per command that does something when named last: every leaf, and a parent that also runs
 * on its own. Which of them are not tools is said once, by the {@code @TuiCommand(interaction)} the
 * CLI already carries, and the NEAREST declaration wins: a command's own, else its parent's, and so
 * on up. That is what lets a {@code PLAIN} query be a tool under a {@code STREAMING} stream, while a
 * {@code CI_ONLY} group still reaches the unannotated commands under it.
 * <p>
 * Nothing here names a command. A command added to the CLI is a tool here with no change, and its
 * options are its schema; the contract test holds that.
 */
public final class ToolCatalog {

    /** The property a payload command's JSON document goes in. Never an option name: no option is called that. */
    public static final String PAYLOAD = "payload";

    /** Appended to a payload command's description: the CLI's own way to the schema, empty stdin. */
    static final String PAYLOAD_HINT = "Call without `payload` to get the payload's schema.";

    /** What a call answers in when it says nothing else: the form a program reads. */
    static final String JSON = "json";

    /**
     * One tool.
     *
     * @param path        the command path below the root, as picocli spells each name
     * @param inputSchema the JSON schema of the arguments, as plain maps and lists
     * @param arguments   how each schema property goes back onto the command line
     * @param payload     whether the command reads a JSON document on stdin
     * @param outputJson  the name of the output option the runner sets to {@code json} when the call
     *                    did not choose, or null when the command offers no JSON
     */
    public record Tool(String name, List<String> path, String description, Map<String, Object> inputSchema,
                       List<Argument> arguments, boolean payload, String outputJson) {

        public Tool {
            path = List.copyOf(path);
            arguments = List.copyOf(arguments);
        }

        public Optional<Argument> argument(String property) {
            return arguments.stream().filter(a -> a.property().equals(property)).findFirst();
        }
    }

    /** What a JSON value becomes on the command line. */
    public enum Kind {
        STRING, INTEGER, NUMBER, BOOLEAN, ARRAY
    }

    /**
     * One property of a tool's schema, and how it goes back onto the command line.
     *
     * @param property the schema's name for it
     * @param option   the option's longest name ({@code --project}), or null for a positional
     * @param index    a positional's place among the positionals, or -1 for an option
     * @param kind     the schema type
     * @param flag     a boolean option that takes no value: true is the name alone, false is nothing
     */
    public record Argument(String property, String option, int index, Kind kind, boolean flag) {

        public boolean positional() {
            return option == null;
        }
    }

    /** A command that is not a tool, and why: one line of the server's instructions. */
    public record Exclusion(String command, Interaction interaction, String reason) {

        public String line() {
            return "- " + command + ": " + reason + ".";
        }
    }

    private final List<Tool> tools;
    private final List<Exclusion> excluded;

    private ToolCatalog(List<Tool> tools, List<Exclusion> excluded) {
        this.tools = List.copyOf(tools);
        this.excluded = List.copyOf(excluded);
    }

    /** The whole tree under {@code root}, read once. The root itself is never a tool. */
    public static ToolCatalog of(CommandLine root) {
        List<Tool> tools = new ArrayList<>();
        List<Exclusion> excluded = new ArrayList<>();
        CommandSpec spec = root.getCommandSpec();
        for (CommandSpec child : children(spec)) {
            walk(child, List.of(child.name()), spec.name(), Interaction.PLAIN, tools, excluded);
        }
        return new ToolCatalog(tools, excluded);
    }

    public List<Tool> tools() {
        return tools;
    }

    public List<Exclusion> excluded() {
        return excluded;
    }

    public Optional<Tool> tool(String name) {
        return tools.stream().filter(t -> t.name().equals(name)).findFirst();
    }

    /**
     * The server's {@code instructions}: what the tools are, and every command that is not one with
     * its reason, a line each. Built from the same walk as the tools, so it cannot drift from them.
     */
    public String instructions() {
        StringBuilder text = new StringBuilder();
        text.append("The qits platform's command line, served as tools: one tool per `qits` command, named by its ")
                .append("path joined with `_` (`qits work update` is `work_update`). Each call runs the command as ")
                .append("you, with the bearer this connection carries: what your credential may not do on the CLI, ")
                .append("it may not do here. The result is what the command prints; `-o json` is the default where ")
                .append("a command offers it. A failed command is a result with isError set, its stderr and its ")
                .append("exit code.\n\n");
        text.append("Not served here (").append(excluded.size()).append("):\n");
        excluded.forEach(e -> text.append(e.line()).append('\n'));
        return text.toString().stripTrailing();
    }

    /** Why a command with this declaration is not a tool, or null when it is one. */
    static String reason(Interaction interaction) {
        return switch (interaction) {
            case BROWSER -> "needs a browser and a person";
            case STREAMING -> "runs until stopped";
            case CI_ONLY -> "CI-step only, reads local files";
            case LOCAL -> "only means something on the caller's machine";
            case PLAIN -> null;
        };
    }

    private static void walk(CommandSpec spec, List<String> path, String root, Interaction above, List<Tool> tools,
                             List<Exclusion> excluded) {
        TuiCommand declared = TuiCommands.declaredOn(spec.userObject());
        Interaction interaction = declared == null ? above : declared.interaction();
        List<CommandSpec> children = children(spec);
        if (runs(spec, children)) {
            String reason = reason(interaction);
            if (reason != null) {
                excluded.add(new Exclusion(root + " " + String.join(" ", path), interaction, reason));
            } else {
                tools.add(tool(spec, path));
            }
        }
        for (CommandSpec child : children) {
            List<String> below = new ArrayList<>(path);
            below.add(child.name());
            walk(child, below, root, interaction, tools, excluded);
        }
    }

    /**
     * Whether a command does something when it is named last: a leaf, or a parent that also runs on
     * its own. A group in this tree is a {@link Runnable} that refuses without a subcommand; a command
     * that runs answers an exit code, so it is a {@link Callable}. The TUI's {@code CommandNode} reads
     * the same shape.
     */
    static boolean runs(CommandSpec spec, List<CommandSpec> children) {
        return children.isEmpty() || spec.userObject() instanceof Callable<?>;
    }

    /** Each subcommand once, under its own name: picocli keys aliases into the same map. */
    static List<CommandSpec> children(CommandSpec spec) {
        Map<String, CommandSpec> byName = new LinkedHashMap<>();
        for (CommandLine child : spec.subcommands().values()) {
            byName.putIfAbsent(child.getCommandSpec().name(), child.getCommandSpec());
        }
        return List.copyOf(byName.values());
    }

    private static Tool tool(CommandSpec spec, List<String> path) {
        boolean payload = TuiCommands.inputOf(spec.userObject()) == Input.PAYLOAD;
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        List<Argument> arguments = new ArrayList<>();
        String outputJson = null;

        for (OptionSpec option : spec.options()) {
            if (!served(option)) {
                continue;
            }
            String property = strip(option.longestName());
            Kind kind = kind(option);
            boolean flag = kind == Kind.BOOLEAN && option.arity().max() == 0;
            Map<String, Object> schema = schema(option, kind);
            if (isOutput(option) && offersJson(option)) {
                outputJson = option.longestName();
                // The runner answers in JSON unless asked otherwise, so that is the default a caller
                // sees, not the CLI's own.
                schema.put("default", JSON);
            }
            properties.put(property, schema);
            if (option.required()) {
                required.add(property);
            }
            arguments.add(new Argument(property, option.longestName(), -1, kind, flag));
        }

        int index = 0;
        for (PositionalParamSpec positional : spec.positionalParameters()) {
            String property = label(positional.paramLabel());
            Kind kind = kind(positional);
            properties.put(property, schema(positional, kind));
            if (positional.arity().min() >= 1) {
                required.add(property);
            }
            arguments.add(new Argument(property, null, index++, kind, false));
        }

        if (payload) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("description", "The JSON document the command reads on stdin. " + PAYLOAD_HINT);
            properties.put(PAYLOAD, schema);
        }

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("type", "object");
        input.put("properties", properties);
        if (!required.isEmpty()) {
            input.put("required", required);
        }
        return new Tool(String.join("_", path), path, description(spec, payload), input, arguments, payload,
                outputJson);
    }

    /**
     * Whether an option is a tool argument. Not help and version, which only print, and never an
     * address: an option ending in {@code -url} points a command at another service, and a server
     * that forwards its caller's bearer would send that bearer wherever the caller said (owner,
     * 2026-10-01). The service resolves every address itself.
     */
    static boolean served(OptionSpec option) {
        if (option.usageHelp() || option.versionHelp()) {
            return false;
        }
        for (String name : option.names()) {
            if (name.toLowerCase(Locale.ROOT).endsWith("-url")) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Object> schema(ArgSpec arg, Kind kind) {
        Map<String, Object> schema = new LinkedHashMap<>();
        if (kind == Kind.ARRAY) {
            schema.put("type", "array");
            Map<String, Object> items = new LinkedHashMap<>();
            Class<?> element = element(arg);
            items.put("type", type(scalar(element)));
            enumOf(element).ifPresent(constants -> items.put("enum", constants));
            schema.put("items", items);
        } else {
            schema.put("type", type(kind));
            enumOf(arg.type()).ifPresent(constants -> schema.put("enum", constants));
        }
        // picocli fills these two in when it renders the help; a schema is not rendered by picocli.
        String description = text(arg.description(), " ")
                .replace("${DEFAULT-VALUE}", String.valueOf(arg.defaultValue()))
                .replace("${COMPLETION-CANDIDATES}", candidates(arg));
        if (!description.isEmpty()) {
            schema.put("description", description);
        }
        if (arg.defaultValue() != null && kind != Kind.ARRAY) {
            schema.put("default", typed(arg.defaultValue(), kind));
        }
        return schema;
    }

    private static String candidates(ArgSpec arg) {
        List<String> values = new ArrayList<>();
        if (arg.completionCandidates() != null) {
            arg.completionCandidates().forEach(values::add);
        }
        return String.join(", ", values);
    }

    private static Kind kind(ArgSpec arg) {
        Class<?> type = arg.type();
        if (type.isArray() || Collection.class.isAssignableFrom(type) || arg.arity().max() > 1) {
            return Kind.ARRAY;
        }
        return scalar(type);
    }

    private static Kind scalar(Class<?> type) {
        if (type == boolean.class || type == Boolean.class) {
            return Kind.BOOLEAN;
        }
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class
                || type == short.class || type == Short.class) {
            return Kind.INTEGER;
        }
        if (type == double.class || type == Double.class || type == float.class || type == Float.class) {
            return Kind.NUMBER;
        }
        return Kind.STRING;
    }

    private static Class<?> element(ArgSpec arg) {
        if (arg.type().isArray()) {
            return arg.type().getComponentType();
        }
        Class<?>[] auxiliary = arg.auxiliaryTypes();
        return auxiliary == null || auxiliary.length == 0 ? String.class : auxiliary[0];
    }

    private static String type(Kind kind) {
        return switch (kind) {
            case STRING -> "string";
            case INTEGER -> "integer";
            case NUMBER -> "number";
            case BOOLEAN -> "boolean";
            case ARRAY -> "array";
        };
    }

    private static Optional<List<String>> enumOf(Class<?> type) {
        if (type == null || !type.isEnum()) {
            return Optional.empty();
        }
        List<String> constants = new ArrayList<>();
        for (Object constant : type.getEnumConstants()) {
            constants.add(String.valueOf(constant));
        }
        return Optional.of(constants);
    }

    private static Object typed(String value, Kind kind) {
        try {
            return switch (kind) {
                case INTEGER -> Long.parseLong(value.strip());
                case NUMBER -> Double.parseDouble(value.strip());
                case BOOLEAN -> Boolean.parseBoolean(value.strip());
                default -> value;
            };
        } catch (NumberFormatException notANumber) {
            return value;
        }
    }

    static boolean isOutput(OptionSpec option) {
        return List.of(option.names()).contains("--output");
    }

    /**
     * Whether the output option offers {@code json}: among its completion candidates or an enum's
     * constants when it has them, else among the choices its label spells ({@code table|json},
     * {@code <text|json>}).
     */
    static boolean offersJson(OptionSpec option) {
        Iterable<String> candidates = option.completionCandidates();
        if (candidates != null) {
            for (String candidate : candidates) {
                if (JSON.equalsIgnoreCase(candidate)) {
                    return true;
                }
            }
            return false;
        }
        String label = option.paramLabel() == null ? "" : option.paramLabel();
        for (String choice : label.replace("<", "").replace(">", "").split("\\|")) {
            if (JSON.equalsIgnoreCase(choice.strip())) {
                return true;
            }
        }
        return false;
    }

    /** {@code --release-request} is {@code release-request}, {@code -o} is {@code o}. */
    static String strip(String name) {
        String stripped = name;
        while (stripped.startsWith("-")) {
            stripped = stripped.substring(1);
        }
        return stripped;
    }

    /**
     * A positional's name: its label without the angle brackets, a space written as a hyphen
     * ({@code <run id>} is {@code run-id}), because clients refuse a property name with a space.
     */
    static String label(String paramLabel) {
        String stripped = paramLabel.strip();
        if (stripped.startsWith("<") && stripped.endsWith(">")) {
            stripped = stripped.substring(1, stripped.length() - 1);
        }
        return stripped.strip().replaceAll("\\s+", "-");
    }

    /**
     * The usage text a person reads: the description, then the footer under its heading. picocli's
     * own line breaks become line breaks.
     */
    private static String description(CommandSpec spec, boolean payload) {
        CommandLine.Model.UsageMessageSpec usage = spec.usageMessage();
        StringBuilder text = new StringBuilder(text(usage.description(), "\n"));
        // Not stripped in front: an example is a line with two spaces before it, the first one too.
        String footer = String.join("\n", lines(usage.footer())).replaceAll("^(\\s*\\n)+", "").stripTrailing();
        if (!footer.isBlank()) {
            String heading = format(usage.footerHeading() == null ? "" : usage.footerHeading()).strip();
            text.append("\n\n");
            if (!heading.isEmpty()) {
                text.append(heading).append('\n');
            }
            text.append(footer);
        }
        if (payload) {
            text.append("\n\n").append(PAYLOAD_HINT);
        }
        return text.toString().strip();
    }

    private static String text(String[] lines, String separator) {
        return String.join(separator, lines(lines)).strip();
    }

    private static List<String> lines(String[] lines) {
        List<String> kept = new ArrayList<>();
        if (lines != null) {
            for (String line : lines) {
                kept.add(format(line == null ? "" : line));
            }
        }
        return kept;
    }

    /** picocli's markup in help text: {@code %n} a line break, {@code %%} a percent sign. */
    private static String format(String line) {
        return line.replace("%n", "\n").replace("%%", "%");
    }
}
