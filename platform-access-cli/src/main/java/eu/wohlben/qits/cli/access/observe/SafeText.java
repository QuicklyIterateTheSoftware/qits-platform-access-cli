package eu.wohlben.qits.cli.access.observe;

import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.CharacterEscapes;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Streamed text made harmless for a terminal. Ingest takes records without a sign-in, so anyone who
 * can reach it chooses what a record says, escape sequences included: one could set the window
 * title, rewrite earlier lines, or hide text. Every streamed value passes through here before a
 * person sees it.
 */
public final class SafeText {

    private static final char ESC = 0x1B;
    private static final char BEL = 0x07;

    /**
     * Writes JSON with every control character escaped: C0, DEL, C1, the bidirectional controls
     * and the line and paragraph separators. The text stays the same to a JSON reader.
     */
    public static final ObjectMapper JSON = new ObjectMapper();

    static {
        JSON.getFactory().setCharacterEscapes(new Escapes());
    }

    private SafeText() {
    }

    /**
     * The text on one line: tab and line breaks become spaces; ESC sequences (CSI, OSC and the
     * other string sequences, the short ones) go whole; the other C0 and C1 characters, DEL and the
     * bidirectional controls go.
     */
    public static String line(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == ESC) {
                i = afterEscape(text, i + 1);
            } else if (c == 0x9B) {
                i = afterCsi(text, i + 1);
            } else if (c == 0x90 || c == 0x98 || c == 0x9D || c == 0x9E || c == 0x9F) {
                i = afterString(text, i + 1);
            } else {
                if (c == '\t' || c == '\n' || c == '\r' || c == 0x2028 || c == 0x2029) {
                    out.append(' ');
                } else if (!isControl(c)) {
                    out.append(c);
                }
                i++;
            }
        }
        return out.toString();
    }

    static boolean isControl(int c) {
        return c < 0x20 || (c >= 0x7F && c <= 0x9F) || isBidi(c) || c == 0x2028 || c == 0x2029;
    }

    private static boolean isBidi(int c) {
        return c == 0x061C || c == 0x200E || c == 0x200F || (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069);
    }

    /** Where the sequence that began with ESC ends. */
    private static int afterEscape(String s, int i) {
        if (i >= s.length()) {
            return i;
        }
        char c = s.charAt(i);
        if (c == '[') {
            return afterCsi(s, i + 1);
        }
        if (c == ']' || c == 'P' || c == 'X' || c == '^' || c == '_') {
            return afterString(s, i + 1);
        }
        while (i < s.length() && s.charAt(i) >= 0x20 && s.charAt(i) <= 0x2F) {
            i++;
        }
        // The final character; a control character is left for the main loop to drop.
        return i < s.length() && s.charAt(i) >= 0x30 && s.charAt(i) <= 0x7E ? i + 1 : i;
    }

    /** Parameters and intermediates (0x20-0x3F) up to the final character (0x40-0x7E). */
    private static int afterCsi(String s, int i) {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c >= 0x40 && c <= 0x7E) {
                return i + 1;
            }
            if (c < 0x20 || c > 0x3F) {
                return i;
            }
            i++;
        }
        return i;
    }

    /** OSC, DCS, SOS, PM and APC run to BEL or the string terminator (ESC \ or 0x9C). */
    private static int afterString(String s, int i) {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == BEL || c == 0x9C) {
                return i + 1;
            }
            if (c == ESC && i + 1 < s.length() && s.charAt(i + 1) == '\\') {
                return i + 2;
            }
            i++;
        }
        return i;
    }

    private static final class Escapes extends CharacterEscapes {
        private final int[] ascii;

        Escapes() {
            ascii = CharacterEscapes.standardAsciiEscapesForJSON();
            ascii[0x7F] = CharacterEscapes.ESCAPE_STANDARD;
        }

        @Override
        public int[] getEscapeCodesForAscii() {
            return ascii;
        }

        @Override
        public SerializableString getEscapeSequence(int ch) {
            return isControl(ch) ? new SerializedString(String.format("\\u%04X", ch)) : null;
        }
    }
}
