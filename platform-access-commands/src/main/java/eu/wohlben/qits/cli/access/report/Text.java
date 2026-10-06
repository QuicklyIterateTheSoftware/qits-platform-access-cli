package eu.wohlben.qits.cli.access.report;

import java.nio.charset.StandardCharsets;

/** Caps on what a payload carries, in UTF-8 bytes, cut on a character boundary. */
final class Text {

    private Text() {
    }

    /** {@code value} in at most {@code bytes} UTF-8 bytes; null stays null. */
    static String cap(String value, int bytes) {
        if (value == null || value.length() * 3 <= bytes) {
            return value;
        }
        if (value.getBytes(StandardCharsets.UTF_8).length <= bytes) {
            return value;
        }
        String marker = "…";
        int budget = bytes - marker.getBytes(StandardCharsets.UTF_8).length;
        int used = 0;
        int end = 0;
        while (end < value.length()) {
            int cp = value.codePointAt(end);
            int size = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (used + size > budget) {
                break;
            }
            used += size;
            end += Character.charCount(cp);
        }
        return value.substring(0, end) + marker;
    }

    /** Null for null, empty or blank; the text stripped otherwise. */
    static String orNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
