package eu.wohlben.qits.cli.access.publish;

import static org.assertj.core.api.Assertions.assertThat;

import eu.wohlben.qits.cli.access.changelog.AssociatedTickets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The document's format, line by line, on strings. */
class ChangelogTest {

    private static final Changelog.Release RELEASE =
            new Changelog.Release("qits-ci", "2026.1012.91502", "rr-1", Instant.parse("2026-10-12T09:15:02.987Z"));

    @Test
    void everySectionInItsPlace() {
        String text = Changelog.render(RELEASE,
                List.of(new Changelog.Commit("aaa1111", "feat(qits-9): one", "Alice"),
                        new Changelog.Commit("bbb2222", "fix(qits-10): two", "Bob")),
                List.of(new Changelog.Ticket("qits-9", "Nine", "https://qits.wohlben.eu/projects/qits/work/detail/qits-9"),
                        new Changelog.Ticket("qits-10", null, null)),
                List.of(new Changelog.Highlight("coverage", "INFO", "80% covered")));

        assertThat(text).isEqualTo("""
                # qits-ci 2026.1012.91502

                Release request `rr-1`, released 2026-10-12T09:15:02Z.

                ## Commits

                - `aaa1111` feat(qits-9): one (Alice)
                - `bbb2222` fix(qits-10): two (Bob)

                ## Tickets

                - [`qits-9`](https://qits.wohlben.eu/projects/qits/work/detail/qits-9) Nine
                - `qits-10` (no such work item)

                ## Report highlights

                - **coverage** INFO: 80% covered

                associated tickets: `qits-9`
                """);
        assertThat(AssociatedTickets.parse(text)).containsExactly("qits-9");
    }

    @Test
    void emptySectionsSayNoneAndNoHighlightsLeaveTheirSectionOut() {
        String text = Changelog.render(new Changelog.Release("r", "1", "rr", null), List.of(), List.of(), List.of());

        assertThat(text).isEqualTo("""
                # r 1

                Release request `rr`.

                ## Commits

                None.

                ## Tickets

                None.

                associated tickets:
                """);
        assertThat(AssociatedTickets.parse(text)).isEmpty();
    }

    @Test
    void freeTextStaysOnItsLine() {
        String text = Changelog.render(RELEASE, List.of(new Changelog.Commit("a", "s", "A\nB")),
                List.of(new Changelog.Ticket("qits-1", "two\r\nlines", "l")), List.of());

        assertThat(text).contains("- `a` s (A B)\n").contains("- [`qits-1`](l) two lines\n");
    }
}
