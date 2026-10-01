package eu.wohlben.qits.cli.access.platform;

import java.util.concurrent.Callable;

/**
 * A command that calls the platform. A {@link CliFailure} becomes one line on stderr and its exit
 * code; nothing else of it is printed.
 */
public abstract class PlatformCommand implements Callable<Integer> {

    private CliContext context;

    /** A test sets its own before the command runs. */
    public void useContext(CliContext context) {
        this.context = context;
    }

    protected CliContext context() {
        if (context == null) {
            context = CliContext.system();
        }
        return context;
    }

    @Override
    public final Integer call() throws Exception {
        CliContext ctx = context();
        try {
            return execute(ctx);
        } catch (CliFailure failure) {
            ctx.err().println(failure.getMessage());
            ctx.err().flush();
            return failure.exitCode();
        } finally {
            ctx.out().flush();
        }
    }

    protected abstract int execute(CliContext context) throws CliFailure, InterruptedException;

    /** {@code table} or {@code json}; null is {@code table}. */
    protected static boolean json(String output) throws CliFailure {
        if (output == null || output.equals("table")) {
            return false;
        }
        if (output.equals("json")) {
            return true;
        }
        throw new CliFailure("--output must be table or json, not '" + output + "'.", CliFailure.USAGE);
    }
}
