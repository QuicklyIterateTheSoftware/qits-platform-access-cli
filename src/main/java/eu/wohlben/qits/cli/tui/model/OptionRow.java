package eu.wohlben.qits.cli.tui.model;

import eu.wohlben.qits.cli.tui.api.CompletionSource;

import java.util.List;

/**
 * One editable row of a leaf command: an option, or a positional parameter.
 * <p>
 * Everything here comes from picocli's own model, so a command that gains an option gains a row
 * without anything being written here. {@link #key()} is the name the rest of the TUI uses to talk
 * about a row — the long name without its dashes, which is also what a completion source names in
 * {@code dependsOn()}.
 */
public record OptionRow(
        Kind kind,
        String name,
        String description,
        boolean required,
        String defaultValue,
        String typeName,
        boolean flag,
        boolean interactive,
        List<String> choices,
        Class<? extends CompletionSource> completionSource) {

    public enum Kind {
        /** A named option: {@code --project}. */
        OPTION,
        /** A positional parameter, shown by its {@code paramLabel}: {@code <run id>}. */
        POSITIONAL
    }

    public OptionRow {
        choices = choices == null ? List.of() : List.copyOf(choices);
    }

    /**
     * The name without dashes or angle brackets: {@code --project} is {@code project}, {@code <run
     * id>} is {@code run id}. Chosen values are keyed by it, and so is a completion source's
     * {@code dependsOn()}.
     */
    public String key() {
        String stripped = name;
        while (stripped.startsWith("-")) {
            stripped = stripped.substring(1);
        }
        if (stripped.startsWith("<") && stripped.endsWith(">")) {
            stripped = stripped.substring(1, stripped.length() - 1);
        }
        return stripped;
    }

    public boolean positional() {
        return kind == Kind.POSITIONAL;
    }

    /** Whether a value is picked from a list this row already knows, without asking the platform. */
    public boolean hasChoices() {
        return !choices.isEmpty();
    }

    /** Whether the platform fills this row's values, through a completion source. */
    public boolean completed() {
        return completionSource != null;
    }
}
