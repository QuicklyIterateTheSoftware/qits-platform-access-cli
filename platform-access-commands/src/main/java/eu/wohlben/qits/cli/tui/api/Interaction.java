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

    /**
     * Takes over the caller's terminal: raw mode, keystrokes relayed, until the command it is attached
     * to ends or the person detaches. The screen runs it like {@link #STREAMING}; the MCP service,
     * which has no terminal, offers none of them.
     */
    TERMINAL,

    /** Opens a browser, so it needs a person and a desktop. */
    BROWSER,

    /** Only means anything inside a CI step, where the step's own credentials are. */
    CI_ONLY,

    /**
     * Only means something on the caller's machine: it answers a program running beside it (Git, an
     * MCP client) or writes a file there. The screen treats it exactly like {@link #PLAIN}; it says
     * so for whoever runs the commands somewhere else, such as the MCP service, which offers none of
     * them.
     */
    LOCAL
}
