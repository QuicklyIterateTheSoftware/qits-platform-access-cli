package eu.wohlben.qits.cli.tui.screen;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * The screen, as lines. One function of a {@link View} and a size, so the whole layout is proven by
 * asserting what it returns — no terminal, no timing.
 *
 * <pre>
 *  qits tui · dev.wohlben.eu · signed in as jan
 *  ┌ ci ▸ runs ──────────────────────────────┐
 *  │ * --project        qits                 │
 *  │ ↑↓ move · ⏎ choose · ␛ back · q quit    │
 *  └─────────────────────────────────────────┘
 *  $ qits ci runs --project qits
 *  ┌ output ───────────────────────── exit 0 ┐
 *  │ RUN     REPOSITORY       STATUS         │
 *  └─────────────────────────────────────────┘
 * </pre>
 *
 * The split does not move: the output box takes half the height, and never fewer than {@link
 * #MIN_OUTPUT_ROWS} rows, so a stream always has somewhere to go.
 */
public final class Frame {

    /** Below this the frame is not drawn at all: a broken frame is worse than a sentence. */
    public static final int MIN_WIDTH = 80;
    public static final int MIN_HEIGHT = 24;

    /** The whole output box, borders included. */
    static final int MIN_OUTPUT_ROWS = 8;

    /** The upper box cannot be smaller than its two borders, one row and its key line. */
    static final int MIN_UPPER_ROWS = 5;

    /** Two borders and a row: the least a box can be and still be one. */
    static final int MIN_BOX_ROWS = 3;

    /** Where a row's value starts, so the names make a column. */
    static final int NAME_COLUMN = 18;

    /** The four border cells and the two spaces inside them: what a row does not get to use. */
    static final int BOX_MARGIN = 5;

    private final Glyphs glyphs;

    public Frame(Glyphs glyphs) {
        this.glyphs = glyphs;
    }

    /** Whether a terminal this size can hold the frame. */
    public static boolean fits(int width, int height) {
        return width >= MIN_WIDTH && height >= MIN_HEIGHT;
    }

    /** The one line a terminal too small is told instead. */
    public static String tooSmall(int width, int height) {
        return "qits tui needs at least " + MIN_WIDTH + "x" + MIN_HEIGHT + " and this terminal is "
                + width + "x" + height + ".";
    }

    public List<AttributedString> render(View view, int width, int height) {
        int messageRows = blank(view.message()) ? 0 : 1;
        // The command line wraps rather than truncates, but it cannot have the whole screen: a
        // value long enough to fill it would otherwise leave no boxes to put it in.
        int roomForCommand = Math.max(1, height - 1 - messageRows - MIN_UPPER_ROWS - MIN_BOX_ROWS);
        List<String> commandLines = wrap("$ " + view.commandLine(), width);
        if (commandLines.size() > roomForCommand) {
            commandLines = commandLines.subList(0, roomForCommand);
        }
        int betweenBoxes = commandLines.size() + messageRows;
        int output = Math.max(MIN_BOX_ROWS, Math.min(Math.max(MIN_OUTPUT_ROWS, height / 2),
                height - 1 - betweenBoxes - MIN_UPPER_ROWS));
        int upper = Math.max(MIN_UPPER_ROWS, height - 1 - betweenBoxes - output);

        List<AttributedString> lines = new ArrayList<>(height);
        lines.add(plain(cut(" " + glyphs.of(view.header()), width)));
        lines.addAll(upperBox(view, width, upper));
        commandLines.forEach(line -> lines.add(plain(cut(line, width))));
        if (!blank(view.message())) {
            lines.add(dim(cut("  " + view.message(), width)));
        }
        lines.addAll(outputBox(view, width, output));
        return lines;
    }

    /** The picker: its border, the rows it holds, and the key line that is always its last row. */
    private List<AttributedString> upperBox(View view, int width, int rows) {
        List<AttributedString> lines = new ArrayList<>(rows);
        lines.add(plain(border(view.title(), listTitleRight(view), width, true)));
        int body = rows - 3;
        int first = firstVisible(view.selected(), view.rows().size(), body);
        for (int i = 0; i < body; i++) {
            int index = first + i;
            if (index >= view.rows().size()) {
                lines.add(boxed(plain(""), width));
                continue;
            }
            lines.add(boxed(row(view.rows().get(index), index == view.selected(), width - BOX_MARGIN), width));
        }
        lines.add(boxed(dim(cut(glyphs.of(view.hint() == null ? "" : view.hint()), width - BOX_MARGIN)), width));
        lines.add(plain(border("", "", width, false)));
        return lines;
    }

    /** What the upper border says on its right: what is being typed, so it is always visible. */
    private String listTitleRight(View view) {
        return view.filter() == null ? "" : view.filter();
    }

    private List<AttributedString> outputBox(View view, int width, int rows) {
        List<AttributedString> lines = new ArrayList<>(rows);
        lines.add(plain(border("output", view.outputTitle() == null ? "" : view.outputTitle(), width, true)));
        int body = rows - 2;
        List<String> shown = view.output().size() <= body
                ? view.output()
                : view.output().subList(view.output().size() - body, view.output().size());
        for (int i = 0; i < body; i++) {
            String text = i < shown.size() ? shown.get(i) : "";
            lines.add(boxed(plain(cut(text, width - BOX_MARGIN)), width));
        }
        lines.add(plain(border("", "", width, false)));
        return lines;
    }

    /**
     * The window of the list that holds the highlighted row, keeping it away from the edges while
     * there is list to either side of it.
     */
    static int firstVisible(int selected, int size, int body) {
        if (size <= body || selected < 0) {
            return 0;
        }
        int first = selected - body / 2;
        return Math.max(0, Math.min(first, size - body));
    }

    /**
     * {@code * --project        qits            RED GREEN}: the mark, the name in its column, then
     * the cursor in front of the value.
     * <p>
     * A row with no name is one whose value is the whole row — a history line, a choice — and there
     * the cursor goes in front of it instead, so it is not left sitting in the middle of the text.
     */
    private AttributedString row(View.Row row, boolean selected, int width) {
        StringBuilder text = new StringBuilder();
        text.append(row.mark() == null || row.mark().isBlank() ? " " : row.mark()).append(' ');
        String name = row.name() == null ? "" : row.name();
        text.append(name);
        for (int i = name.length(); !name.isEmpty() && i < NAME_COLUMN; i++) {
            text.append(' ');
        }
        text.append(selected ? glyphs.cursor() : " ").append(' ');
        String value = row.value() == null ? "" : row.value();
        text.append(value);
        String line = cut(text.toString(), width);
        if (!blank(row.extra())) {
            StringBuilder padded = new StringBuilder(line);
            while (padded.length() < Math.min(width, NAME_COLUMN + 24)) {
                padded.append(' ');
            }
            line = cut(padded + " " + row.extra(), width);
        }
        AttributedStyle style = AttributedStyle.DEFAULT;
        if (row.dim()) {
            style = style.faint();
        }
        if (selected) {
            style = style.inverse();
        }
        return new AttributedStringBuilder().style(style).append(pad(line, width)).toAttributedString();
    }

    /** {@code ┌ title ─────── right ┐}, or a plain rule when neither is given. */
    private String border(String title, String right, int width, boolean top) {
        String left = top ? glyphs.topLeft() : glyphs.bottomLeft();
        String end = top ? glyphs.topRight() : glyphs.bottomRight();
        StringBuilder line = new StringBuilder(" ").append(left);
        if (!blank(title)) {
            line.append(' ').append(glyphs.of(title)).append(' ');
        }
        String tail = blank(right) ? "" : " " + glyphs.of(right) + " ";
        int inner = Math.max(0, width - 2 - line.length() + 1 - tail.length());
        line.append(glyphs.horizontal().repeat(inner)).append(tail).append(end);
        return cut(line.toString(), width);
    }

    /** One row inside a box, padded to the border so the right-hand edge is a straight line. */
    private AttributedString boxed(AttributedString content, int width) {
        int room = width - BOX_MARGIN;
        AttributedStringBuilder line = new AttributedStringBuilder()
                .append(" " + glyphs.vertical() + " ")
                .append(content);
        for (int i = content.length(); i < room; i++) {
            line.append(' ');
        }
        return line.append(" " + glyphs.vertical()).toAttributedString();
    }

    private static AttributedString plain(String text) {
        return new AttributedString(text);
    }

    private static AttributedString dim(String text) {
        return new AttributedString(text, AttributedStyle.DEFAULT.faint());
    }

    /** The command line is never abbreviated, so it wraps instead. */
    static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        int room = Math.max(1, width - 1);
        String rest = text;
        while (rest.length() > room) {
            lines.add(" " + rest.substring(0, room));
            rest = rest.substring(room);
        }
        lines.add(" " + rest);
        return lines;
    }

    private static String cut(String text, int width) {
        return text.length() <= width ? text : text.substring(0, Math.max(0, width));
    }

    private static String pad(String text, int width) {
        return text.length() >= width ? text : text + " ".repeat(width - text.length());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
