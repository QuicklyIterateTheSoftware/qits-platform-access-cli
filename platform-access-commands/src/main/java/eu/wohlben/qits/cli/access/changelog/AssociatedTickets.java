package eu.wohlben.qits.cli.access.changelog;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The last line of every published changelog: {@code associated tickets: `qits-9` `qits-10`}. It is
 * the one part of a changelog a program reads back — the bump message of a dependant names the
 * tickets its upgrade brings in from it — so it has a grammar, and this class is both its writer and
 * its reader.
 * <p>
 * The line is always there, bare when the release resolved no ticket. A document without it is not
 * a changelog this platform wrote, and {@link #parse} says so rather than reading it as "no tickets".
 */
public final class AssociatedTickets {

    static final String PREFIX = "associated tickets:";

    private static final Pattern LINE =
            Pattern.compile("^associated tickets:((?: `[A-Za-z0-9][A-Za-z0-9-]*-[0-9]{1,18}`)*)$");

    private static final Pattern ID = Pattern.compile("`([^`]+)`");

    /** A document whose last line is not the associated-tickets line. */
    public static final class Malformed extends IllegalArgumentException {
        Malformed(String message) {
            super(message);
        }
    }

    private AssociatedTickets() {
    }

    /** The line for these ids, in the order given; bare when there are none. */
    public static String line(List<String> ids) {
        StringBuilder line = new StringBuilder(PREFIX);
        for (String id : ids) {
            line.append(" `").append(id).append('`');
        }
        return line.toString();
    }

    /**
     * The ids the document's last non-empty line names, in its order; empty when the line is bare.
     *
     * @throws Malformed when the last non-empty line is not an associated-tickets line, or there is none
     */
    public static List<String> parse(String markdown) {
        String last = null;
        for (String line : (markdown == null ? "" : markdown).split("\\r?\n", -1)) {
            if (!line.isBlank()) {
                last = line;
            }
        }
        if (last == null) {
            throw new Malformed("the changelog is empty");
        }
        Matcher matcher = LINE.matcher(last);
        if (!matcher.matches()) {
            throw new Malformed("the changelog's last line is not `" + PREFIX + " ...`");
        }
        List<String> ids = new ArrayList<>();
        Matcher id = ID.matcher(matcher.group(1));
        while (id.find()) {
            ids.add(id.group(1));
        }
        return List.copyOf(ids);
    }
}
