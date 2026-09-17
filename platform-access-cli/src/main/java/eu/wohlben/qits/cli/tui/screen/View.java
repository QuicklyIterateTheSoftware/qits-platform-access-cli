package eu.wohlben.qits.cli.tui.screen;

import java.util.List;

/**
 * Everything the frame paints, already decided. {@link Frame} turns it into lines and knows nothing
 * else — no commands, no session, no process.
 * <p>
 * The list is whatever the upper box is showing at this moment: subcommands, option rows, the
 * choices of an open picker, or the history. One shape, so a new kind of list costs no frame code.
 *
 * @param header      the line above the frame, which says where and as whom
 * @param title       what the upper box is called: the path, or the name of an open picker
 * @param rows        the list, top to bottom
 * @param selected    the highlighted row, or -1 when the list is empty
 * @param filter      what has been typed to narrow the list — {@code /} in the list, the typing in
 *                    an open picker — or null when nothing is being typed
 * @param commandLine the command the choices have built, shown between the boxes and never cut
 * @param message     one line under the command line — a refusal or a source's failure — or null
 * @param outputTitle the right of the output box's border: {@code running}, {@code exit 0}
 * @param output      the lines of the last run, oldest first
 * @param hint        the key line at the foot of the upper box
 */
public record View(
        String header,
        String title,
        List<Row> rows,
        int selected,
        String filter,
        String commandLine,
        String message,
        String outputTitle,
        List<String> output,
        String hint) {

    /**
     * One row of the list.
     *
     * @param mark  the leftmost cell: {@code *} for a required option, else a space
     * @param name  the command's or the option's name
     * @param value what is chosen, or the default, or the description of a subcommand
     * @param extra what follows dim: an enum's constants, a choice's label
     * @param dim   the whole row is decoration — a CI-only command, an unreachable one
     */
    public record Row(String mark, String name, String value, String extra, boolean dim) {

        public static Row of(String name, String value) {
            return new Row(" ", name, value, "", false);
        }
    }

    public View {
        rows = rows == null ? List.of() : List.copyOf(rows);
        output = output == null ? List.of() : List.copyOf(output);
    }
}
