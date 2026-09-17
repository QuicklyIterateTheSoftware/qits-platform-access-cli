package eu.wohlben.qits.cli.tui.screen;

import org.jline.utils.NonBlockingReader;

import java.io.IOException;

/**
 * One keypress, already made sense of. The escape sequences a terminal sends for the arrow keys are
 * read here and nowhere else, so the app that reacts to them is a function of an enum.
 *
 * @param kind what was pressed
 * @param ch   the character, when {@link Kind#CHAR}
 */
public record Key(Kind kind, char ch) {

    public enum Kind {
        UP, DOWN, ENTER, ESCAPE, LEFT, RIGHT, BACKSPACE, CHAR,
        /** Run. */
        CTRL_R,
        /** Stop what is running. */
        CTRL_C,
        /** History. */
        CTRL_P,
        /** Refetch a picker's values. */
        CTRL_L,
        /** The terminal closed under us. */
        EOF,
        /** Nothing arrived before the timeout — the frame repaints and the read starts again. */
        NONE
    }

    /** How long a read waits before it lets the caller repaint a running command's output. */
    public static final long TICK_MILLIS = 250;

    private static final int ESC = 27;

    public static Key of(Kind kind) {
        return new Key(kind, '\0');
    }

    public static Key character(char ch) {
        return new Key(Kind.CHAR, ch);
    }

    /**
     * The next keypress, or {@link Kind#NONE} after {@link #TICK_MILLIS}.
     * <p>
     * A lone {@code ESC} and the start of an arrow key's sequence are the same byte; they are told
     * apart by whether anything follows at once, which is why the second read has a short timeout
     * rather than none.
     */
    public static Key read(NonBlockingReader reader) throws IOException {
        int first = reader.read(TICK_MILLIS);
        if (first == NonBlockingReader.READ_EXPIRED) {
            return of(Kind.NONE);
        }
        if (first < 0) {
            return of(Kind.EOF);
        }
        return switch (first) {
            case ESC -> escape(reader);
            case '\r', '\n' -> of(Kind.ENTER);
            case 127, 8 -> of(Kind.BACKSPACE);
            case 3 -> of(Kind.CTRL_C);
            case 12 -> of(Kind.CTRL_L);
            case 16 -> of(Kind.CTRL_P);
            case 18 -> of(Kind.CTRL_R);
            default -> character((char) first);
        };
    }

    private static Key escape(NonBlockingReader reader) throws IOException {
        int second = reader.read(30L);
        if (second != '[' && second != 'O') {
            return of(Kind.ESCAPE);
        }
        int third = reader.read(30L);
        return switch (third) {
            case 'A' -> of(Kind.UP);
            case 'B' -> of(Kind.DOWN);
            case 'C' -> of(Kind.RIGHT);
            case 'D' -> of(Kind.LEFT);
            default -> of(Kind.ESCAPE);
        };
    }
}
