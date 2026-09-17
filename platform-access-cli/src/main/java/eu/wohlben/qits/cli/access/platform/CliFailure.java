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
    private final int status;

    public CliFailure(String message, int exitCode) {
        this(message, exitCode, false, 0);
    }

    private CliFailure(String message, int exitCode, boolean retryable, int status) {
        super(message, null, false, false);
        this.exitCode = exitCode;
        this.retryable = retryable;
        this.status = status;
    }

    /** A connection error or a 5xx: a stream tries again, a one-shot command gives up. */
    public static CliFailure retryable(String message) {
        return new CliFailure(message, FAILED, true, 0);
    }

    /** The platform answered with this HTTP status. A command may say more about a status it expects. */
    public static CliFailure refused(String message, int status) {
        return new CliFailure(message, FAILED, false, status);
    }

    public int exitCode() {
        return exitCode;
    }

    public boolean retryable() {
        return retryable;
    }

    /** The HTTP status of a refusal; 0 when the failure is not one. */
    public int status() {
        return status;
    }
}
