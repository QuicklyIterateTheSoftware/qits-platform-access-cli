package eu.wohlben.qits.cli.tui.api;

/**
 * What a command reads on stdin. Said in code, next to the command, so something that runs the
 * commands without a terminal — the MCP service, which offers a {@link #PAYLOAD} command a payload
 * argument — knows which ones want a document, rather than keeping a list of their names.
 */
public enum Input {

    /** Reads nothing on stdin. */
    NONE,

    /** Reads one JSON document on stdin, and with none prints the schema of what it wants. */
    PAYLOAD
}
