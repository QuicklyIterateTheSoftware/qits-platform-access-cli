package eu.wohlben.qits.cli.access.platform;

import eu.wohlben.qits.cli.access.session.Times;

import java.io.PrintStream;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/** Aligned columns for a person to read. Two spaces between columns; the last is not padded. */
public final class Table {

    private Table() {
    }

    public static void print(PrintStream out, String indent, List<String> headers, List<List<String>> rows) {
        List<List<String>> all = new ArrayList<>();
        if (headers != null) {
            all.add(headers);
        }
        all.addAll(rows);
        int columns = all.stream().mapToInt(List::size).max().orElse(0);
        int[] widths = new int[columns];
        for (List<String> row : all) {
            for (int i = 0; i < row.size(); i++) {
                widths[i] = Math.max(widths[i], row.get(i).length());
            }
        }
        for (List<String> row : all) {
            StringBuilder line = new StringBuilder(indent);
            for (int i = 0; i < row.size(); i++) {
                line.append(row.get(i));
                if (i < row.size() - 1) {
                    line.append(" ".repeat(widths[i] - row.get(i).length() + 2));
                }
            }
            out.println(line.toString().stripTrailing());
        }
    }

    /** One line, at most {@code max} characters; a dash for nothing. */
    public static String cell(String value, int max) {
        String flat = value == null ? "" : value.replaceAll("\\s+", " ").strip();
        if (flat.isEmpty()) {
            return "-";
        }
        return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
    }

    /** A service time in the local zone; the text as it came when it is not a time. */
    public static String time(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        try {
            return Times.local(Instant.parse(value));
        } catch (DateTimeParseException notATime) {
            return value;
        }
    }
}
