package eu.wohlben.qits.cli.tui.run;

import eu.wohlben.qits.cli.tui.model.CommandNode;
import eu.wohlben.qits.cli.tui.model.OptionRow;
import eu.wohlben.qits.cli.tui.model.Selection;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The commands run in this session, newest first.
 * <p>
 * In memory and nowhere else. A shell writes its history to a file and a person's shell history is
 * a known place for a secret to end up; this one ends with the process. For the same reason a value
 * typed into an {@code interactive()} option never enters the buffer at all — not hidden in it,
 * not at all — so re-running such a command asks for it again, which is the only correct answer.
 */
public final class History {

    /** A session's worth. Older than this and nobody is looking for it in a list. */
    static final int MAX = 50;

    private final CommandNode root;
    private final Deque<Entry> entries = new ArrayDeque<>();

    public History(CommandNode root) {
        this.root = root;
    }

    /**
     * One command as it was run.
     *
     * @param path   the subcommand names walked into, the first below the root first
     * @param values the values chosen, less any that were typed into a hidden field
     */
    public record Entry(List<String> path, Map<String, String> values, String commandLine) {

        public Entry {
            path = List.copyOf(path);
            values = Map.copyOf(values);
        }
    }

    /** Remember {@code selection}, secrets left out. The newest is first and a repeat is not kept twice. */
    public void add(Selection selection) {
        Selection safe = withoutSecrets(selection);
        Entry entry = new Entry(names(safe), safe.values(), safe.commandLine());
        entries.removeIf(held -> held.commandLine().equals(entry.commandLine()));
        entries.addFirst(entry);
        while (entries.size() > MAX) {
            entries.removeLast();
        }
    }

    public List<Entry> entries() {
        return new ArrayList<>(entries);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** The entry as a selection again, so {@code e} can put it back in the picker. */
    public Selection selectionOf(Entry entry) {
        Selection selection = new Selection(root);
        for (String name : entry.path()) {
            CommandNode child = selection.current().child(name);
            if (child == null) {
                break;
            }
            selection.enter(child);
        }
        entry.values().forEach(selection::set);
        return selection;
    }

    private Selection withoutSecrets(Selection selection) {
        Selection safe = new Selection(selection.root());
        selection.path().stream().skip(1).forEach(safe::enter);
        for (Map.Entry<String, String> value : ordered(selection).entrySet()) {
            OptionRow row = safe.current().row(value.getKey());
            if (row != null && row.interactive()) {
                continue;
            }
            safe.set(value.getKey(), value.getValue());
        }
        return safe;
    }

    private static Map<String, String> ordered(Selection selection) {
        return new LinkedHashMap<>(selection.values());
    }

    private static List<String> names(Selection selection) {
        return selection.path().stream().skip(1).map(CommandNode::name).toList();
    }
}
