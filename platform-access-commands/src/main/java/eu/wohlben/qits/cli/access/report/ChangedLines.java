package eu.wohlben.qits.cli.access.report;

import java.util.Set;

/**
 * The lines the fold added or changed against the baseline's tag, per file: what diff coverage is
 * measured over. Computed lazily and at most once per submit, and only when there is a baseline; any
 * failure to compute it makes it {@link #available() unavailable}, never wrong and never thrown.
 * <p>
 * {@link GitChangedLines} is the one that measures; {@link #unavailable()} is every other case.
 */
public interface ChangedLines {

    /** False without a baseline, or when the diff could not be had. */
    boolean available();

    /** The changed files, relative to the root, with forward slashes. Empty when unavailable. */
    Set<String> files();

    /** The new-side line numbers (1-based) the fold added or changed in {@code file}; empty when none. */
    Set<Integer> lines(String file);

    /** Nothing to compare with. */
    static ChangedLines unavailable() {
        return Unavailable.INSTANCE;
    }

    /** The one unavailable answer. */
    enum Unavailable implements ChangedLines {
        INSTANCE;

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public Set<String> files() {
            return Set.of();
        }

        @Override
        public Set<Integer> lines(String file) {
            return Set.of();
        }
    }
}
