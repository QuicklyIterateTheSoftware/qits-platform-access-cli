package eu.wohlben.qits.cli.access.platform;

/**
 * Pieces of help text that every command shares, so the help and SKILL.md say them the same way.
 * Picocli puts every text through {@code String.format}: {@code %n} is a line break, and a percent
 * sign must be written {@code %%}.
 */
public final class HelpText {

    public static final String EXAMPLES = "%nExamples:%n";
    public static final String EXIT_CODES = "%nExit codes:%n";

    /** The exit codes of a platform command: {@link CliFailure#FAILED} and {@link CliFailure#USAGE}. */
    public static final String DONE = "0:Done.";
    public static final String REFUSED = "1:The platform refused (the message names the status), or cannot be reached.";
    public static final String USAGE = "2:Used wrongly, not signed in, or the session ended (run `qits login`).";

    private HelpText() {
    }
}
