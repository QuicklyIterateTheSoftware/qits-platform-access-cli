package eu.wohlben.qits.cli.access.publish;

import eu.wohlben.qits.cli.access.daemon.Sleeper;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Running {@code qits publish ...} the way a step does: an environment, an argv (without the
 * leading {@code publish} — this harness starts at the {@link PublishCommand} tree itself), and
 * three things to assert on — the exit code, what went to stdout, and what went to stderr.
 */
final class Harness {

    private final Map<String, String> env = new LinkedHashMap<>();

    /** What one invocation produced. */
    record Run(int code, String out, String err) {

        boolean errContains(String fragment) {
            return err.contains(fragment);
        }
    }

    Harness with(String name, String value) {
        env.put(name, value);
        return this;
    }

    /** The variable almost every command needs, pointed at a stub. */
    Harness store(StubStore store) {
        return with("QITS_ARTIFACTS_URL", store.url());
    }

    Run run(String... argv) {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int code;
        try (PrintStream out = new PrintStream(outBuf, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(errBuf, true, StandardCharsets.UTF_8)) {
            CliContext context = new CliContext(Map.copyOf(env), java.io.InputStream.nullInputStream(), out,
                    err, Clock.systemUTC(), Sleeper.real(), name -> null, runnable -> {
            });
            CommandLine cli = new CommandLine(new PublishCommand(), new CommandLine.IFactory() {
                @Override
                public <K> K create(Class<K> type) throws Exception {
                    K made = CommandLine.defaultFactory().create(type);
                    if (made instanceof PlatformCommand command) {
                        command.useContext(context);
                    }
                    return made;
                }
            });
            // picocli's own output (--help, "Name a command.", a parse refusal) goes to whatever
            // streams the CommandLine itself holds — System.out/err by default — not to the
            // CliContext streams Console writes through. Both must point at the same buffers.
            cli.setOut(new PrintWriter(out, true, StandardCharsets.UTF_8));
            cli.setErr(new PrintWriter(err, true, StandardCharsets.UTF_8));
            code = cli.execute(argv);
        }
        return new Run(code, outBuf.toString(StandardCharsets.UTF_8), errBuf.toString(StandardCharsets.UTF_8));
    }
}
