package eu.wohlben.qits.cli.access.changelog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which work items a commit names, read off its subject the way the platform's commit convention
 * writes it: {@code feat(qits-893): ...}, {@code fix(qits-9, qits-10)!: ...}. One rule for every
 * reader, so a changelog and the bump message built from changelogs agree on what a subject names.
 * <p>
 * A subject whose scope is not a list of qualified ids ({@code chore(deps): ...}, {@code
 * bump(targeted): ...}) names nothing at all, rather than the parts of it that happen to look like
 * ids: a scope is either a ticket list or something else.
 */
public final class CommitSubjects {

    /** {@code <type>(<scope>)<!>:} at the start of the subject. */
    private static final Pattern HEAD = Pattern.compile("^([^()\\s:]*)\\(([^()]+)\\)(!?):");

    /** A qualified id: the project's slug, a dash, the item's number. The slug is everything before the last dash. */
    private static final Pattern ID = Pattern.compile("^([A-Za-z0-9][A-Za-z0-9-]*)-([0-9]{1,18})$");

    /**
     * A dependency-bump commit's subject: the bare {@code bump(<group>): <N> dependencies}, or the same
     * subject behind the {@code chore(<ids>):} scope the bump step's postlude puts in front of it.
     */
    private static final Pattern BUMP = Pattern.compile("^(chore\\([^()]+\\)!?: )?bump\\([^()]+\\): [0-9]+ dependencies$");

    /** Project slug first, as text; then the number, as a number, so qits-9 comes before qits-10. */
    public static final Comparator<String> ORDER = Comparator.comparing(CommitSubjects::slug)
            .thenComparingLong(CommitSubjects::number);

    private CommitSubjects() {
    }

    /** The first line of a commit message: its subject. */
    public static String subject(String message) {
        if (message == null) {
            return "";
        }
        int end = message.indexOf('\n');
        String line = end < 0 ? message : message.substring(0, end);
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    /** The ids a subject names, in the order it names them, each once; empty when it names none. */
    public static List<String> ids(String subject) {
        Matcher head = HEAD.matcher(subject == null ? "" : subject);
        if (!head.find()) {
            return List.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String part : head.group(2).split(",", -1)) {
            String id = part.strip();
            if (!isId(id)) {
                return List.of();
            }
            ids.add(id);
        }
        return List.copyOf(ids);
    }

    /** Every id the subjects name, each once, in {@link #ORDER}. */
    public static List<String> ids(List<String> subjects) {
        Set<String> all = new LinkedHashSet<>();
        for (String subject : subjects) {
            all.addAll(ids(subject));
        }
        return sorted(all);
    }

    /** The ids, each once, in {@link #ORDER}. */
    public static List<String> sorted(java.util.Collection<String> ids) {
        List<String> sorted = new ArrayList<>(new LinkedHashSet<>(ids));
        sorted.sort(ORDER);
        return List.copyOf(sorted);
    }

    /** Whether the text is one qualified id. */
    public static boolean isId(String text) {
        return text != null && ID.matcher(text).matches();
    }

    /**
     * Whether a subject is a dependency-bump commit's: {@code bump(targeted): 2 dependencies}, or the
     * same subject with a {@code chore(qits-1, qits-2):} scope in front, as {@code qits changelog
     * bump-message} writes it once a bump brings tickets in. Its scope, bare or chorified, is not the
     * release's own work: it is what the bumped dependencies' own changelogs already named, so no
     * reader counts it again (qits-1866).
     */
    public static boolean isBump(String subject) {
        return subject != null && BUMP.matcher(subject).matches();
    }

    /** The project slug of a qualified id: {@code qits} of {@code qits-893}. */
    public static String slug(String id) {
        Matcher matcher = ID.matcher(id);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("'" + id + "' is not a qualified id");
        }
        return matcher.group(1);
    }

    private static long number(String id) {
        Matcher matcher = ID.matcher(id);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("'" + id + "' is not a qualified id");
        }
        return Long.parseLong(matcher.group(2));
    }
}
