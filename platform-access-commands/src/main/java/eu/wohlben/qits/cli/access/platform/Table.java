package eu.wohlben.qits.cli.access.platform;

import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.session.Times;

import java.io.PrintStream;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    /**
     * One line, at most {@code max} characters; a dash for nothing. Terminal control characters and
     * escape sequences go first: a cell shows text another person wrote, such as a summary.
     */
    public static String cell(String value, int max) {
        String flat = SafeText.line(value).replaceAll("\\s+", " ").strip();
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

    /** A byte count for a person: GiB above one, else MiB above one, else the bare count; one decimal. */
    public static String bytes(long value) {
        double gib = value / (1024.0 * 1024.0 * 1024.0);
        if (gib >= 1) {
            return String.format(Locale.ROOT, "%.1f GiB", gib);
        }
        double mib = value / (1024.0 * 1024.0);
        if (mib >= 1) {
            return String.format(Locale.ROOT, "%.1f MiB", mib);
        }
        return value + " B";
    }
}
