package eu.wohlben.qits.cli.tui.screen;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The few characters the frame is drawn with, in the two forms a terminal may be able to show.
 * <p>
 * A terminal that cannot carry Unicode gets the ASCII form rather than a frame full of question
 * marks — the same choice {@code TuiUi} makes for its spinner in qits-bootstrap-cli. Nothing here
 * is decoration that carries meaning on its own: the marks are readable in both forms, and colour
 * is never the only difference between two rows.
 */
public final class Glyphs {

    public static final Glyphs UNICODE = new Glyphs(true);
    public static final Glyphs ASCII = new Glyphs(false);

    private static final Map<String, String> PLAIN = Map.ofEntries(
            Map.entry("┌", "+"), Map.entry("┐", "+"), Map.entry("└", "+"), Map.entry("┘", "+"),
            Map.entry("─", "-"), Map.entry("│", "|"),
            Map.entry("▸", ">"), Map.entry("·", "-"),
            Map.entry("↑↓", "up/dn"), Map.entry("⏎", "enter"), Map.entry("␛", "esc"),
            Map.entry("⌃", "^"), Map.entry("…", "..."));

    private final boolean unicode;

    private Glyphs(boolean unicode) {
        this.unicode = unicode;
    }

    /** UTF-8 on the terminal is the whole test; a {@code TERM} of {@code dumb} never gets here. */
    public static Glyphs forEncoding(Charset encoding) {
        return StandardCharsets.UTF_8.equals(encoding) ? UNICODE : ASCII;
    }

    public boolean unicode() {
        return unicode;
    }

    /** Every glyph in {@code text} in the form this terminal can show. */
    public String of(String text) {
        if (unicode) {
            return text;
        }
        String plain = text;
        for (Map.Entry<String, String> glyph : PLAIN.entrySet()) {
            plain = plain.replace(glyph.getKey(), glyph.getValue());
        }
        return plain;
    }

    public String topLeft() {
        return of("┌");
    }

    public String topRight() {
        return of("┐");
    }

    public String bottomLeft() {
        return of("└");
    }

    public String bottomRight() {
        return of("┘");
    }

    public String horizontal() {
        return of("─");
    }

    public String vertical() {
        return of("│");
    }

    public String cursor() {
        return of("▸");
    }

    public String dot() {
        return of("·");
    }
}
