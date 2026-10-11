package eu.wohlben.qits.cli.tui.api;

/**
 * What a command does to the platform when it runs.
 * <p>
 * {@link #WRITE} is the default, so a command nobody classified counts as one: a read-only caller
 * (the MCP service's {@code agentReadOnly=true}) never runs it. Mark a command {@link #READ} only
 * when it changes nothing anywhere: no record, no run, no token, no credential, no file.
 */
public enum Effect {

    /** Only reads. Safe for a caller that may change nothing. */
    READ,

    /** Changes something, or nobody has said that it does not. */
    WRITE
}
