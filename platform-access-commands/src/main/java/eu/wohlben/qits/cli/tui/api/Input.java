package eu.wohlben.qits.cli.tui.api;

/**
 * What a command reads on stdin. Said in code, next to the command, so something that runs the
 * commands without a terminal — the MCP service, which offers a {@link #PAYLOAD} or {@link #TEXT}
 * command a payload argument — knows which ones want a document, rather than keeping a list of their names.
 */
public enum Input {

    /** Reads nothing on stdin. */
    NONE,

    /** Reads one JSON document on stdin, and with none prints the schema of what it wants. */
    PAYLOAD,

    /**
     * Reads one value as plain text on stdin (a secret, which a flag would leave in the shell's
     * history), and with none refuses. No schema: the text is the value.
     */
    TEXT
}
