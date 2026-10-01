package eu.wohlben.qits.cli.tui.api;

/**
 * The form a command's output is asked for in.
 * <p>
 * This is not a renderer: the child formats its own output and the screen shows lines. It only
 * decides which value the TUI puts on the command's {@code --output} option when it has one.
 */
public enum Output {

    /** Whatever the command prints by default. Nothing is appended. */
    TEXT,

    /** Aligned columns. */
    TABLE,

    /** The service's answer. */
    JSON
}
