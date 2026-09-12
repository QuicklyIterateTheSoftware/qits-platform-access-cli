package eu.wohlben.qits.cli.access.platform;

/**
 * A command cannot go on. The message is for a person and holds no token; the command prints it to
 * stderr and exits with {@link #exitCode()}.
 */
public final class CliFailure extends Exception {

    /** The platform or the network refused or failed. */
    public static final int FAILED = 1;
    /** The command was used wrongly, or there is no usable session: the person has to act. */
    public static final int USAGE = 2;

    private final int exitCode;
    private final boolean retryable;

    public CliFailure(String message, int exitCode) {
        this(message, exitCode, false);
    }

    private CliFailure(String message, int exitCode, boolean retryable) {
        super(message, null, false, false);
        this.exitCode = exitCode;
        this.retryable = retryable;
    }

    /** A connection error or a 5xx: a stream tries again, a one-shot command gives up. */
    public static CliFailure retryable(String message) {
        return new CliFailure(message, FAILED, true);
    }

    public int exitCode() {
        return exitCode;
    }

    public boolean retryable() {
        return retryable;
    }
}
