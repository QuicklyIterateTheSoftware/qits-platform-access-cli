package eu.wohlben.qits.cli.tui.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Where a person is in the tree, and what they have chosen there.
 * <p>
 * The path is the commands walked into, the root first. The values are keyed by {@link
 * OptionRow#key()} — {@code project}, not {@code --project} — which is the same key a completion
 * source names in {@code dependsOn()}, so the two never have to be translated into each other.
 * <p>
 * {@link #commandLine()} is what the screen shows under the picker and what {@link #argv()} runs.
 * One rendering, so what a person is taught is exactly what runs.
 */
public final class Selection {

    private final List<CommandNode> path = new ArrayList<>();
    private final Map<String, String> values = new LinkedHashMap<>();

    /**
     * How the rows are ordered — the same ordering the screen lists them in, so the command line
     * reads in the order a person filled it in. The identity by default: without completion sources
     * there is nothing to reorder.
     */
    private UnaryOperator<List<OptionRow>> order = UnaryOperator.identity();

    public Selection(CommandNode root) {
        path.add(root);
    }

    public void orderRowsBy(UnaryOperator<List<OptionRow>> order) {
        this.order = order == null ? UnaryOperator.identity() : order;
    }

    /** The rows of the current command, in the order the screen shows them. */
    public List<OptionRow> rows() {
        return order.apply(current().rows());
    }

    public CommandNode root() {
        return path.getFirst();
    }

    public CommandNode current() {
        return path.getLast();
    }

    public List<CommandNode> path() {
        return List.copyOf(path);
    }

    /** How deep below the root, so the frame knows whether going back does anything. */
    public int depth() {
        return path.size() - 1;
    }

    /** Walk into a subcommand. The values chosen so far stay: an inherited option keeps its value. */
    public void enter(CommandNode child) {
        path.add(child);
    }

    /**
     * Up one segment, dropping the values of the rows that segment owned but the one above does
     * not. Going back has to undo what going in offered, or the command line would keep an option
     * the command it now names does not take.
     */
    public boolean back() {
        if (path.size() <= 1) {
            return false;
        }
        path.removeLast();
        values.keySet().removeIf(key -> current().row(key) == null);
        return true;
    }

    public String value(String key) {
        return values.get(key);
    }

    public Map<String, String> values() {
        return Map.copyOf(values);
    }

    /** A null or blank value takes the row off the command line again. */
    public void set(String key, String value) {
        if (value == null || value.isBlank()) {
            values.remove(key);
        } else {
            values.put(key, value);
        }
    }

    public void clear(String key) {
        values.remove(key);
    }

    /** The first required row with no value, or null when the command can run. */
    public OptionRow firstUnsetRequired() {
        for (OptionRow row : rows()) {
            if (row.required() && !values.containsKey(row.key())) {
                return row;
            }
        }
        return null;
    }

    /** The path as the top of the upper box shows it: {@code ci ▸ runs}, or the root's own name. */
    public String pathLine(String separator) {
        if (path.size() == 1) {
            return root().name();
        }
        StringBuilder line = new StringBuilder();
        for (int i = 1; i < path.size(); i++) {
            if (i > 1) {
                line.append(separator);
            }
            line.append(path.get(i).name());
        }
        return line.toString();
    }

    /** {@code qits ci runs --project qits --repository qits-ci-service}. */
    public String commandLine() {
        StringBuilder line = new StringBuilder(root().name());
        for (String word : words()) {
            line.append(' ').append(quote(word));
        }
        return line.toString();
    }

    /** The command line without the binary's own name: what the fork is started with. */
    public List<String> argv() {
        return words();
    }

    /**
     * The values of the {@code interactive()} rows, in the order those rows appear in {@link
     * #argv()}, which is the order picocli will ask for them.
     * <p>
     * They are not in the argv, and that is the point: a child started with {@code --secret hunter2}
     * carries that word in {@code /proc/<pid>/cmdline}, where every process on the machine can read
     * it. picocli's own answer for such an option is to prompt, so the TUI gives it the option
     * without a value and writes the value on the child's standard input instead.
     */
    public List<String> secrets() {
        List<String> secrets = new ArrayList<>();
        for (OptionRow row : rows()) {
            String value = values.get(row.key());
            if (row.interactive() && value != null) {
                secrets.add(value);
            }
        }
        return secrets;
    }

    /**
     * Positionals in their row order, then the options that have a value. A flag is its name alone;
     * a positional is its bare value.
     */
    private List<String> words() {
        List<String> words = new ArrayList<>();
        for (int i = 1; i < path.size(); i++) {
            words.add(path.get(i).name());
        }
        for (OptionRow row : rows()) {
            String value = values.get(row.key());
            if (value == null) {
                continue;
            }
            if (row.interactive()) {
                // The name alone: picocli then asks for the value, and CommandRunner answers.
                words.add(row.name());
            } else if (row.positional()) {
                words.add(value);
            } else if (row.flag()) {
                if (Boolean.parseBoolean(value)) {
                    words.add(row.name());
                }
            } else {
                words.add(row.name());
                words.add(value);
            }
        }
        return words;
    }

    /** Shell quoting, and only where it is needed: a value with a space in it would be two words. */
    static String quote(String word) {
        if (!word.isEmpty() && word.chars().noneMatch(c -> c == ' ' || c == '\t' || c == '\'' || c == '"')) {
            return word;
        }
        return "'" + word.replace("'", "'\\''") + "'";
    }
}
