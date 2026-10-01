package eu.wohlben.qits.cli.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cli.access.daemon.Sleeper;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.mcp.ToolCatalog.Argument;
import eu.wohlben.qits.cli.mcp.ToolCatalog.Tool;
import eu.wohlben.qits.cli.session.Credential;
import eu.wohlben.qits.cli.session.Mode;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * One tool call, run as the command it is: in this process, with a fresh {@link CommandLine}, the
 * caller's bearer as its credential, the payload as its stdin, and what it printed as the answer.
 * <p>
 * <b>Every command gets this call's context, and none falls back to the process's own.</b> The
 * factory hands it to each {@link PlatformCommand} picocli makes, which is every one in the tree and
 * so every one in the parsed chain, parents included. The process's own context would read this
 * service's environment and call the platform as the service; the service sets
 * {@link CliContext#MCP_SERVICE}, so a command that reached for it would fail rather than do that.
 * <p>
 * No shell is anywhere in this: an argument's value is one argv element, whatever it holds.
 */
public final class ToolRunner {

    /** picocli's own exit code for a command line it could not read; the CLI's {@code USAGE}. */
    static final int USAGE = 2;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Function<CommandLine.IFactory, CommandLine> tree;
    private final Map<String, String> environment;
    private final Clock clock;

    /**
     * @param tree        makes the whole tree with the factory it is given, once per call: command
     *                    objects hold what was parsed into them, so two calls never share one
     * @param environment what the commands see as their environment; see {@link #environment}
     */
    public ToolRunner(Function<CommandLine.IFactory, CommandLine> tree, Map<String, String> environment,
                      Clock clock) {
        this.tree = tree;
        this.environment = Map.copyOf(environment);
        this.clock = clock;
    }

    /**
     * The environment every call runs in, and the whole of it: in-platform, the environment's label
     * and the service addresses this service was configured with. Nothing from the caller, and none
     * of this process's own environment beyond those names — in particular no commissioned client,
     * which this service never has.
     *
     * @param config what this service was configured with, by variable name; null for an unset one
     */
    public static Map<String, String> environment(Function<String, String> config) {
        Map<String, String> env = new TreeMap<>();
        env.put(Mode.OVERRIDE, "true");
        for (String name : List.of("QITS_ENV", "QITS_PROJECTS_URL", "QITS_CI_URL", "QITS_EVENTS_URL",
                "QITS_OBSERVABILITY_URL")) {
            String value = config.apply(name);
            if (value != null && !value.isBlank()) {
                env.put(name, value.strip());
            }
        }
        return env;
    }

    /**
     * What a call answered.
     *
     * @param texts the text items of the result, in order
     */
    public record Result(boolean error, List<String> texts, int exit) {

        public Result {
            texts = List.copyOf(texts);
        }

        public String text() {
            return String.join("\n", texts);
        }
    }

    /** Runs {@code tool} with {@code arguments} as {@code credential}'s holder. */
    public Result run(Tool tool, Map<String, Object> arguments, Credential credential) {
        List<String> argv;
        byte[] stdin;
        try {
            argv = argv(tool, arguments == null ? Map.of() : arguments);
            stdin = stdin(tool, arguments == null ? Map.of() : arguments);
        } catch (IllegalArgumentException refused) {
            return new Result(true, List.of(refused.getMessage(), "exit " + USAGE), USAGE);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
        PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8);
        InputStream in = stdin == null ? InputStream.nullInputStream() : new ByteArrayInputStream(stdin);
        CliContext context = new CliContext(environment, in, outStream, errStream, clock, Sleeper.real(),
                TokenClient::new, stop -> {
                    // Nothing to install: a call ends when its command returns, and no signal is
                    // this call's to answer.
                }).withCredential(credential);

        CommandLine cli = tree.apply(new CommandLine.IFactory() {
            @Override
            public <K> K create(Class<K> type) throws Exception {
                K made = CommandLine.defaultFactory().create(type);
                if (made instanceof PlatformCommand command) {
                    command.useContext(context);
                }
                return made;
            }
        });
        // And whatever the tree was handed already made, which the factory never saw.
        handTo(cli.getCommandSpec(), context);
        cli.setOut(new PrintWriter(outStream, true, StandardCharsets.UTF_8));
        cli.setErr(new PrintWriter(errStream, true, StandardCharsets.UTF_8));
        // picocli's own message alone: the usage it would print after it is the tool's schema here.
        cli.setParameterExceptionHandler((refused, args) -> {
            errStream.println(refused.getMessage());
            return USAGE;
        });
        cli.setExecutionExceptionHandler((failed, commandLine, parsed) -> {
            errStream.println("The command failed: " + failed);
            return 1;
        });

        int exit = cli.execute(argv.toArray(String[]::new));
        outStream.flush();
        errStream.flush();
        return result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static void handTo(CommandLine.Model.CommandSpec spec, CliContext context) {
        if (spec.userObject() instanceof PlatformCommand command) {
            command.useContext(context);
        }
        for (CommandLine child : spec.subcommands().values()) {
            handTo(child.getCommandSpec(), context);
        }
    }

    /**
     * The MCP result of an exit: stdout as the answer, and stderr beside it when the command had
     * something to note; on failure stderr first, then the exit code, then whatever stdout holds.
     */
    static Result result(int exit, String stdout, String stderr) {
        List<String> texts = new ArrayList<>();
        if (exit == 0) {
            texts.add(stdout);
            if (!stderr.isBlank()) {
                texts.add(stderr);
            }
            return new Result(false, texts, exit);
        }
        texts.add(stderr);
        texts.add("exit " + exit);
        if (!stdout.isBlank()) {
            texts.add(stdout);
        }
        return new Result(true, texts, exit);
    }

    /**
     * The command line the arguments mean: the tool's path, each option as {@code --name=value}
     * (which a value starting with a dash cannot be mistaken in), a flag as its name, an array as the
     * option once per element, and the positionals last.
     */
    static List<String> argv(Tool tool, Map<String, Object> arguments) {
        for (String property : arguments.keySet()) {
            if (!property.equals(ToolCatalog.PAYLOAD) || !tool.payload()) {
                tool.argument(property).orElseThrow(() -> new IllegalArgumentException(
                        "Unknown argument '" + property + "' for " + tool.name() + "."));
            }
        }
        List<String> argv = new ArrayList<>(tool.path());
        boolean outputChosen = false;
        for (Argument argument : tool.arguments()) {
            if (argument.positional() || !arguments.containsKey(argument.property())) {
                continue;
            }
            Object value = arguments.get(argument.property());
            if (value == null) {
                continue;
            }
            if (argument.option().equals(tool.outputJson())) {
                outputChosen = true;
            }
            if (argument.flag()) {
                if (bool(argument, value)) {
                    argv.add(argument.option());
                }
                continue;
            }
            for (String one : values(argument, value)) {
                argv.add(argument.option() + "=" + one);
            }
        }
        if (tool.outputJson() != null && !outputChosen) {
            argv.add(tool.outputJson() + "=" + ToolCatalog.JSON);
        }
        List<String> positionals = new ArrayList<>();
        for (Argument argument : tool.arguments()) {
            if (argument.positional() && arguments.get(argument.property()) != null) {
                positionals.addAll(values(argument, arguments.get(argument.property())));
            }
        }
        if (!positionals.isEmpty()) {
            // Everything after `--` is a positional, so a value that starts with a dash stays one.
            argv.add("--");
            argv.addAll(positionals);
        }
        return argv;
    }

    /** The payload as the command reads it on stdin, or null when the call carried none. */
    static byte[] stdin(Tool tool, Map<String, Object> arguments) {
        Object payload = arguments.get(ToolCatalog.PAYLOAD);
        if (!tool.payload() || payload == null) {
            return null;
        }
        if (payload instanceof String text) {
            // Sent as written: the command says what is wrong with it, as it does on the CLI.
            return text.getBytes(StandardCharsets.UTF_8);
        }
        try {
            return JSON.writeValueAsBytes(payload);
        } catch (JsonProcessingException unwritable) {
            throw new IllegalArgumentException("The payload cannot be written as JSON.");
        }
    }

    private static List<String> values(Argument argument, Object value) {
        if (value instanceof Collection<?> many) {
            if (argument.kind() != ToolCatalog.Kind.ARRAY) {
                throw new IllegalArgumentException("'" + argument.property() + "' takes one value, not a list.");
            }
            List<String> values = new ArrayList<>();
            for (Object one : many) {
                values.add(scalar(argument, one));
            }
            return values;
        }
        return List.of(scalar(argument, value));
    }

    private static String scalar(Argument argument, Object value) {
        if (value instanceof Map<?, ?> || value instanceof Collection<?>) {
            throw new IllegalArgumentException("'" + argument.property() + "' takes a plain value.");
        }
        if (value instanceof Number number && argument.kind() == ToolCatalog.Kind.INTEGER
                && number.doubleValue() == Math.rint(number.doubleValue())) {
            return Long.toString(number.longValue());
        }
        return String.valueOf(value);
    }

    private static boolean bool(Argument argument, Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text && (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false"))) {
            return Boolean.parseBoolean(text);
        }
        throw new IllegalArgumentException("'" + argument.property() + "' is true or false.");
    }
}
