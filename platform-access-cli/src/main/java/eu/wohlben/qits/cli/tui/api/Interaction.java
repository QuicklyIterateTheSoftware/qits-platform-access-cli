package eu.wohlben.qits.cli.tui.api;

/**
 * How a command behaves once it is started — the one thing picocli's model cannot say and the
 * screen has to know.
 * <p>
 * A command that declares none of this is {@link #PLAIN}, and works.
 */
public enum Interaction {

    /** Runs, prints, exits. */
    PLAIN,

    /** Runs until it is stopped: a stream has no exit code to wait for. */
    STREAMING,

    /** Opens a browser, so it needs a person and a desktop. */
    BROWSER,

    /** Only means anything inside a CI step, where the step's own credentials are. */
    CI_ONLY
}
