package eu.wohlben.qits.cli.tui;

import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.Output;
import eu.wohlben.qits.cli.tui.model.CommandNode;
import eu.wohlben.qits.cli.tui.model.OptionRow;
import eu.wohlben.qits.cli.tui.model.Selection;
import eu.wohlben.qits.cli.tui.run.CommandRunner;
import eu.wohlben.qits.cli.tui.run.History;
import eu.wohlben.qits.cli.tui.screen.ChoiceList;
import eu.wohlben.qits.cli.tui.screen.Key;
import eu.wohlben.qits.cli.tui.screen.View;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The whole of the TUI's behaviour, with no terminal in it: keys go in, a {@link View} comes out.
 * <p>
 * Keeping the state here and the painting in {@code Frame} is what makes the screen provable — a
 * test presses keys and asserts lines, and never needs a pseudo-terminal. It is also what keeps the
 * TUI generic: everything this class knows about a command it read from {@link CommandNode}.
 * <p>
 * The upper box shows one of a few things at a time — the list, the history — and {@link Screen}
 * says which. They all paint through the same rows, so a new one costs no frame code.
 */
public class TuiApp {

    /** What the upper box is showing. */
    protected enum Screen {
        /** Subcommands, or the option rows of a leaf. */
        LIST,
        /** The values an option may take, searchable. */
        CHOICES,
        /** One line of text for an option that has no list. */
        FIELD,
        /** The commands run this session. */
        HISTORY,
        /** A yes-or-no question that has to be answered before something happens. */
        CONFIRM
    }

    private static final String LIST_HINT =
            "↑↓ move · ⏎ choose · ␛ back · / search · ⌃R run · q quit";
    private static final String FILTER_HINT =
            "type to filter · ⏎ choose · ␛ clear";
    private static final String RUNNING_HINT =
            "⌃C stop · ↑↓ move · ⏎ choose · ␛ back · q quit";
    private static final String HISTORY_HINT =
            "⏎ run again · e edit · ␛ close";
    private static final String CHOICES_HINT =
            "type to search · ↑↓ move · ⏎ take · ⌃L refetch · ␛ leave unchanged";
    private static final String FIELD_HINT =
            "type the value · ⏎ accept · ␛ leave unchanged";
    private static final String CONFIRM_HINT =
            "y run it · n or ␛ do not";

    /** Said before a browser command runs, because the browser opens somewhere else. */
    static final String OPENS_A_BROWSER = "this opens a browser";

    /** Asked before a command that belongs in a CI step is run anywhere else. */
    static final String OUTSIDE_CI = "run outside CI? y/n";

    /** What a hidden field shows instead of what was typed. */
    private static final char HIDDEN = '\u2022';

    private final CommandRunner runner;
    private final History history;
    private Selection selection;
    private Screen screen = Screen.LIST;
    private String header;
    private int selected;
    private String filter;
    private String message;
    private boolean running = true;

    /** The row an open editor belongs to, and what that editor holds. */
    private OptionRow editing;
    private ChoiceList choices;
    private StringBuilder typing;
    private boolean hidden;
    private String question;

    public TuiApp(CommandNode root, String header) {
        this(root, header, new CommandRunner());
    }

    public TuiApp(CommandNode root, String header, CommandRunner runner) {
        this.selection = new Selection(root);
        this.history = new History(root);
        this.header = header;
        this.runner = runner;
    }

    public CommandRunner runner() {
        return runner;
    }

    public History history() {
        return history;
    }

    public Selection selection() {
        return selection;
    }

    public boolean running() {
        return running;
    }

    protected Screen screen() {
        return screen;
    }

    protected void setHeader(String header) {
        this.header = header;
    }

    protected void message(String message) {
        this.message = message;
    }

    /** Put the cursor on a named row, which is how a refusal points at what is missing. */
    protected void jumpTo(String key) {
        List<View.Row> rows = rows();
        for (int i = 0; i < rows.size(); i++) {
            String at = rowKeyAt(i);
            if (at != null && at.equals(key)) {
                selected = i;
                return;
            }
        }
    }

    public View view() {
        List<View.Row> rows = rows();
        if (selected >= rows.size()) {
            selected = Math.max(0, rows.size() - 1);
        }
        return new View(header, title(), rows, rows.isEmpty() ? -1 : selected, typedText(),
                selection.commandLine(), message, runner.title(), runner.lines(), hint());
    }

    protected String title() {
        return switch (screen) {
            case HISTORY -> "history";
            case CONFIRM -> selection.pathLine(" ▸ ");
            case CHOICES, FIELD -> editing.name();
            case LIST -> selection.pathLine(" ▸ ");
        };
    }

    protected String hint() {
        return switch (screen) {
            case HISTORY -> HISTORY_HINT;
            case CHOICES -> CHOICES_HINT;
            case FIELD -> FIELD_HINT;
            case CONFIRM -> CONFIRM_HINT;
            case LIST -> filter != null ? FILTER_HINT : runner.running() ? RUNNING_HINT : LIST_HINT;
        };
    }

    /** What is being typed right now, whatever is open, or null when nothing is. */
    private String typedText() {
        return switch (screen) {
            case CHOICES -> choices.typed();
            case FIELD, HISTORY, CONFIRM -> null;
            case LIST -> filter == null ? null : "/" + filter;
        };
    }

    /** The list as the screen shows it. */
    protected List<View.Row> rows() {
        if (screen == Screen.CONFIRM) {
            return List.of(View.Row.of("", question));
        }
        if (screen == Screen.CHOICES) {
            return choices.rows();
        }
        if (screen == Screen.FIELD) {
            return List.of(View.Row.of("", shownTyping()));
        }
        if (screen == Screen.HISTORY) {
            List<View.Row> rows = new ArrayList<>();
            for (History.Entry entry : history.entries()) {
                rows.add(View.Row.of("", entry.commandLine()));
            }
            return rows;
        }
        return keep(listRows());
    }

    private List<View.Row> listRows() {
        List<View.Row> rows = new ArrayList<>();
        CommandNode node = selection.current();
        if (!node.leaf()) {
            List<CommandNode> children = new ArrayList<>(node.children());
            // A command that is decoration here is sorted last as well as dimmed: it is not what
            // anybody came for, and the list reads better without it in the middle.
            children.sort(Comparator.comparing(child -> dim(child) ? 1 : 0));
            for (CommandNode child : children) {
                rows.add(new View.Row(" ", child.name(), child.description(), "", dim(child)));
            }
            return rows;
        }
        for (OptionRow row : node.rows()) {
            rows.add(new View.Row(row.required() ? "*" : " ", row.name(), shownValue(row),
                    String.join(" ", row.choices()), false));
        }
        return rows;
    }

    /** Whether a command is shown as decoration: one that belongs in a CI step and nowhere else. */
    protected boolean dim(CommandNode child) {
        return child.interaction() == Interaction.CI_ONLY;
    }

    /** What is chosen, else the default, else nothing to say yet. */
    protected String shownValue(OptionRow row) {
        String chosen = selection.value(row.key());
        if (chosen != null) {
            if (row.flag()) {
                return "on";
            }
            return row.interactive() ? "(asked for when it runs)" : chosen;
        }
        if (row.defaultValue() != null && !row.defaultValue().isBlank()) {
            return row.defaultValue();
        }
        return row.required() ? "" : "(any)";
    }

    /** The rows the filter leaves, or all of them when no filter is open. */
    private List<View.Row> keep(List<View.Row> rows) {
        if (filter == null || filter.isEmpty()) {
            return rows;
        }
        String wanted = filter.toLowerCase(Locale.ROOT);
        List<View.Row> kept = new ArrayList<>();
        for (View.Row row : rows) {
            String text = (row.name() + " " + row.value() + " " + row.extra()).toLowerCase(Locale.ROOT);
            if (text.contains(wanted)) {
                kept.add(row);
            }
        }
        return kept;
    }

    /**
     * The model key of the visible row at {@code index} — an option's {@link OptionRow#key()} or a
     * subcommand's name — which is what the filter makes necessary: the visible list is not the
     * model's list.
     */
    protected String rowKeyAt(int index) {
        List<View.Row> rows = rows();
        if (index < 0 || index >= rows.size() || screen == Screen.HISTORY) {
            return null;
        }
        String name = rows.get(index).name();
        if (selection.current().leaf()) {
            OptionRow row = selection.current().rows().stream()
                    .filter(r -> r.name().equals(name)).findFirst().orElse(null);
            return row == null ? null : row.key();
        }
        return name;
    }

    /** Handle one keypress. Returns false when the TUI is finished. */
    public boolean key(Key key) {
        message = null;
        switch (key.kind()) {
            case NONE -> {
                return running;
            }
            case EOF -> running = false;
            case UP -> move(-1);
            case DOWN -> move(1);
            case ENTER -> choose();
            case ESCAPE, LEFT -> back();
            case BACKSPACE -> backspace();
            case CTRL_R -> run();
            case CTRL_C -> stop();
            case CTRL_P -> showHistory();
            case CTRL_L -> refetch();
            case CHAR -> typed(key.ch());
            default -> {
                // Nothing else moves the screen.
            }
        }
        return running;
    }

    protected void backspace() {
        if (screen == Screen.CHOICES) {
            choices.backspace();
            select(0);
            return;
        }
        if (screen == Screen.FIELD) {
            if (!typing.isEmpty()) {
                typing.deleteCharAt(typing.length() - 1);
            }
            return;
        }
        if (filter != null && !filter.isEmpty()) {
            filter = filter.substring(0, filter.length() - 1);
            selected = 0;
        }
    }

    protected void typed(char ch) {
        if (screen == Screen.CONFIRM) {
            if (ch == 'y') {
                screen = Screen.LIST;
                question = null;
                start();
            } else if (ch == 'n') {
                back();
            }
            return;
        }
        if (screen == Screen.CHOICES) {
            choices.type(ch);
            select(0);
            return;
        }
        if (screen == Screen.FIELD) {
            typing.append(ch);
            return;
        }
        if (screen == Screen.HISTORY) {
            if (ch == 'e') {
                editFromHistory();
            } else if (ch == 'q') {
                running = false;
            }
            return;
        }
        if (filter != null) {
            filter = filter + ch;
            selected = 0;
            return;
        }
        switch (ch) {
            case '/' -> {
                filter = "";
                selected = 0;
            }
            case 'k' -> move(-1);
            case 'j' -> move(1);
            case 'q' -> running = false;
            default -> {
                // A key the list does not use.
            }
        }
    }

    protected void move(int by) {
        if (screen == Screen.CHOICES) {
            choices.move(by);
            select(choices.selected());
            return;
        }
        int size = rows().size();
        if (size == 0) {
            return;
        }
        selected = Math.floorMod(selected + by, size);
    }

    /** What a text field shows: the typing, or a dot per character when the value is a secret. */
    private String shownTyping() {
        String text = hidden ? String.valueOf(HIDDEN).repeat(typing.length()) : typing.toString();
        return text + "_";
    }

    /** {@code ⏎}: into a subcommand, onto the selected option's editor, or an earlier command again. */
    protected void choose() {
        if (screen == Screen.CHOICES) {
            String taken = choices.chosen();
            if (taken != null) {
                chose(editing, taken);
            }
            closeEditor();
            return;
        }
        if (screen == Screen.FIELD) {
            chose(editing, typing.toString());
            closeEditor();
            return;
        }
        if (screen == Screen.HISTORY) {
            History.Entry entry = entryAt(selected);
            if (entry != null) {
                selection = history.selectionOf(entry);
                screen = Screen.LIST;
                selected = 0;
                start();
            }
            return;
        }
        String key = rowKeyAt(selected);
        if (key == null) {
            return;
        }
        if (!selection.current().leaf()) {
            CommandNode child = selection.current().child(key);
            if (child != null) {
                enter(child);
            }
            return;
        }
        OptionRow row = selection.current().row(key);
        if (row != null) {
            edit(row);
        }
    }

    /**
     * Walk into a command. A command that declares an output form has it put on its {@code --output}
     * option here, so the command line shows it and what runs is what was shown.
     */
    private void enter(CommandNode child) {
        selection.enter(child);
        filter = null;
        selected = 0;
        OptionRow output = child.rows().stream().filter(row -> "output".equals(row.key())).findFirst().orElse(null);
        if (child.output() != Output.TEXT && output != null && selection.value(output.key()) == null) {
            selection.set(output.key(), child.output().name().toLowerCase(Locale.ROOT));
        }
    }

    /** {@code e} in the history: the command goes back into the picker, and nothing runs. */
    private void editFromHistory() {
        History.Entry entry = entryAt(selected);
        if (entry == null) {
            return;
        }
        selection = history.selectionOf(entry);
        screen = Screen.LIST;
        selected = 0;
        filter = null;
    }

    private History.Entry entryAt(int index) {
        List<History.Entry> entries = history.entries();
        return index < 0 || index >= entries.size() ? null : entries.get(index);
    }

    /**
     * {@code ⏎} on an option row. A flag has nothing to edit — it is on or off — and everything else
     * opens either the searchable list of what it may be, or a field to type into.
     */
    protected void edit(OptionRow row) {
        if (row.flag()) {
            chose(row, Boolean.parseBoolean(selection.value(row.key())) ? null : "true");
            return;
        }
        editing = row;
        if (row.hasChoices()) {
            screen = Screen.CHOICES;
            choices = new ChoiceList(row.choices().stream().map(ChoiceList.Choice::of).toList());
            select(0);
            return;
        }
        openField(row.interactive() ? "" : orEmpty(selection.value(row.key())), row.interactive());
    }

    /** A one-line text field, hidden when what goes in it is a secret. */
    protected void openField(String value, boolean secret) {
        screen = Screen.FIELD;
        typing = new StringBuilder(value);
        hidden = secret;
        select(0);
    }

    /** A value was taken. Subclasses hook here to drop what depended on the row that changed. */
    protected void chose(OptionRow row, String value) {
        selection.set(row.key(), value);
    }

    protected void closeEditor() {
        screen = Screen.LIST;
        editing = null;
        choices = null;
        typing = null;
        hidden = false;
    }

    /** The row an open editor belongs to, or null when none is open. */
    protected OptionRow editing() {
        return editing;
    }

    protected ChoiceList choices() {
        return choices;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    protected void back() {
        if (screen == Screen.CONFIRM) {
            screen = Screen.LIST;
            question = null;
            return;
        }
        if (screen == Screen.CHOICES || screen == Screen.FIELD) {
            closeEditor();
            return;
        }
        if (screen == Screen.HISTORY) {
            screen = Screen.LIST;
            selected = 0;
            return;
        }
        if (filter != null) {
            filter = null;
            selected = 0;
            return;
        }
        if (selection.back()) {
            selected = 0;
        }
    }

    /**
     * {@code ⌃R}: run what the command line shows. A command that is missing a required value is
     * not run at all — the screen says which one and puts the cursor on it, which is a shorter way
     * to be told than picocli's own refusal in the output box.
     */
    protected void run() {
        if (screen != Screen.LIST) {
            return;
        }
        OptionRow missing = selection.firstUnsetRequired();
        if (missing != null) {
            clearFilter();
            message(missing.name() + " is required");
            jumpTo(missing.key());
            return;
        }
        if (!selection.current().leaf()) {
            message("pick a command first");
            return;
        }
        if (selection.current().interaction() == Interaction.CI_ONLY) {
            screen = Screen.CONFIRM;
            question = OUTSIDE_CI;
            selected = 0;
            return;
        }
        start();
    }

    /** Start the child and remember what was run. */
    protected void start() {
        if (selection.current().interaction() == Interaction.BROWSER) {
            message(OPENS_A_BROWSER);
        }
        history.add(selection);
        runner.start(selection.argv(), streaming(), selection.secrets());
    }

    /** Whether the chosen command runs until it is stopped, and so has no exit code to wait for. */
    protected boolean streaming() {
        return selection.current().interaction() == Interaction.STREAMING;
    }

    /** {@code ⌃C}: stop the child, and stay. */
    protected void stop() {
        runner.stop();
    }

    /** {@code ⌃P}: the commands run this session, in place of the list. */
    protected void showHistory() {
        if (screen == Screen.CHOICES || screen == Screen.FIELD) {
            return;
        }
        if (screen == Screen.HISTORY) {
            screen = Screen.LIST;
            selected = 0;
            return;
        }
        if (history.isEmpty()) {
            message("nothing has run yet");
            return;
        }
        screen = Screen.HISTORY;
        filter = null;
        selected = 0;
    }

    /** {@code ⌃L}. */
    protected void refetch() {
    }

    protected int selected() {
        return selected;
    }

    protected void select(int index) {
        selected = index;
    }


    protected String filter() {
        return filter;
    }

    protected void clearFilter() {
        filter = null;
    }
}
