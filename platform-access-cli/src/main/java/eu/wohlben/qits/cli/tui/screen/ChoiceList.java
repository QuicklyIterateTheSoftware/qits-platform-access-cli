package eu.wohlben.qits.cli.tui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A searchable list of values, in place of the picker's own list.
 * <p>
 * One widget for every list of values there is: an enum's constants, picocli's completion
 * candidates, and what a completion source returns from the platform. They differ only in where the
 * choices came from, which is the caller's business, so there is one of these rather than one per
 * kind.
 * <p>
 * A choice shows its {@link Choice#label()} and yields its {@link Choice#value()}. Typing filters by
 * the label, because the label is what a person recognises — a run's label carries its branch and
 * its status, and its value is the bare id.
 */
public final class ChoiceList {

    /**
     * One row of the list.
     *
     * @param value what goes on the command line
     * @param label what a person reads and what typing matches
     */
    public record Choice(String value, String label) {

        public static Choice of(String value) {
            return new Choice(value, value);
        }
    }

    private List<Choice> all;
    private String typed = "";
    private String note;
    private int selected;

    public ChoiceList(List<Choice> all) {
        this.all = List.copyOf(all);
    }

    /** The values arrived — from a source, or a refetch. The typing so far is kept. */
    public void choices(List<Choice> choices) {
        this.all = List.copyOf(choices);
        this.note = null;
        this.selected = 0;
    }

    /** One dim line above the list: {@code loading…}, or why there is nothing to show. */
    public void note(String note) {
        this.note = note;
    }

    public String note() {
        return note;
    }

    public String typed() {
        return typed;
    }

    public void type(char ch) {
        typed = typed + ch;
        selected = 0;
    }

    public void backspace() {
        if (!typed.isEmpty()) {
            typed = typed.substring(0, typed.length() - 1);
            selected = 0;
        }
    }

    public void move(int by) {
        List<Choice> shown = shown();
        if (shown.isEmpty()) {
            selected = 0;
            return;
        }
        selected = Math.floorMod(selected + by, shown.size());
    }

    /** What the highlighted row yields, or null when nothing matches what was typed. */
    public String chosen() {
        List<Choice> shown = shown();
        return selected < 0 || selected >= shown.size() ? null : shown.get(selected).value();
    }

    public int selected() {
        return selected;
    }

    /** The choices the typing leaves, in the order they arrived. */
    public List<Choice> shown() {
        if (typed.isEmpty()) {
            return all;
        }
        String wanted = typed.toLowerCase(Locale.ROOT);
        List<Choice> kept = new ArrayList<>();
        for (Choice choice : all) {
            if (choice.label().toLowerCase(Locale.ROOT).contains(wanted)) {
                kept.add(choice);
            }
        }
        return kept;
    }

    /** The choices as rows. The note is not one of them: it is a line, not something to pick. */
    public List<View.Row> rows() {
        List<View.Row> rows = new ArrayList<>();
        for (Choice choice : shown()) {
            rows.add(View.Row.of("", choice.label()));
        }
        return rows;
    }
}
