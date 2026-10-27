package eu.wohlben.qits.cli.access.contracts;

import eu.wohlben.qits.cli.access.TestCli;
import eu.wohlben.qits.cli.access.daemon.Sleeper;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.session.Mode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/**
 * <b>A command run for a contract row</b>: {@code qits …} in the test's process, as the MCP service
 * runs it ({@code QITS_PLATFORM=true} in the environment, so no session file is read and every
 * address comes from a {@code QITS_<APP>_URL}), calling with {@link Reached#CREDENTIAL}. For a row
 * whose client is a command class rather than an {@code *Api}: the command itself makes the request.
 */
public final class InPlatformCli {

    public record Result(int exit, String out, String err) {
    }

    private InPlatformCli() {
    }

    /** {@code args} with {@code env} on top of an in-platform environment. */
    public static Result run(Map<String, String> env, String... args) {
        return run(Clock.systemUTC(), env, args);
    }

    /** The same, with the command's now read from {@code clock}: a window the recording holds in its future. */
    public static Result run(Clock clock, Map<String, String> env, String... args) {
        Map<String, String> all = new HashMap<>(env);
        all.put(Mode.OVERRIDE, "true");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliContext context = new CliContext(Map.copyOf(all), InputStream.nullInputStream(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                clock, Sleeper.real(), name -> null, stop -> { }).withCredential(Reached.CREDENTIAL);
        int exit = TestCli.execute(context, args);
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }
}
