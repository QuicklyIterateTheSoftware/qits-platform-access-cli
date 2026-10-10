package eu.wohlben.qits.cli.access.agents;

import java.util.Locale;
import java.util.Map;

/**
 * The marker in front of a work id in the status line: the block glyph, then the status square.
 * <p>
 * <b>A hand-kept copy</b> of qits-coding-agents' {@code EntityStatusSquare} and {@code
 * AgentRemoteControl.BLOCKED_GLYPH} / {@code AGENT_WAITING_GLYPH}, the palette of Claude's session
 * names, which is itself the twin of the UI's {@code STATUS_TONES}. That library brings vert.x with
 * it, so the CLI copies the table instead. A change to one is a change to all three, or a person
 * reads one colour on the board and another here for the same piece of work.
 */
final class StatusMarker {

    static final String BLOCKED_GLYPH = "❗";
    static final String AGENT_WAITING_GLYPH = "⁉️";

    /** The service's word for a block that is only an agent waiting on a person. */
    static final String AGENT_WAITING = "AGENT_WAITING";

    private static final Map<String, String> SQUARES = Map.of(
            "REPORTED", "⬜",
            "REFINED", "🟪",
            "READY_FOR_DEV", "🟪",
            "IMPLEMENTING", "🟦",
            "IMPLEMENTED", "🟦",
            "VERIFYING", "🟦",
            "VERIFIED", "✅",
            "DONE", "🟩",
            "DROPPED", "⬜");

    private StatusMarker() {
    }

    /**
     * {@code ❗🟦}, or empty when nothing is known. A status this table does not know has no square
     * rather than a wrong one. With a glyph and no square the glyph stands alone, and the caller's
     * space follows it, as in the session names.
     */
    static String of(String status, boolean blocked, String blockSource) {
        String square = status == null ? null : SQUARES.get(status.strip().toUpperCase(Locale.ROOT));
        String glyph = !blocked ? "" : AGENT_WAITING.equals(blockSource) ? AGENT_WAITING_GLYPH : BLOCKED_GLYPH;
        return glyph + (square == null ? "" : square);
    }
}
