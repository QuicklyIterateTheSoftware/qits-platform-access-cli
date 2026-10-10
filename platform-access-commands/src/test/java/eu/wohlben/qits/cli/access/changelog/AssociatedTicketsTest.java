package eu.wohlben.qits.cli.access.changelog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class AssociatedTicketsTest {

    @Test
    void theLastNonEmptyLineIsRead() {
        assertThat(AssociatedTickets.parse("# x\n\nassociated tickets: `qits-9` `qits-10`\n\n\n"))
                .containsExactly("qits-9", "qits-10");
        assertThat(AssociatedTickets.parse("# x\r\n\r\nassociated tickets:\r\n")).isEmpty();
    }

    @Test
    void whatItWritesItReads() {
        assertThat(AssociatedTickets.line(List.of())).isEqualTo("associated tickets:");
        assertThat(AssociatedTickets.parse(AssociatedTickets.line(List.of("a-1", "b-2")))).containsExactly("a-1", "b-2");
    }

    @Test
    void anythingElseIsMalformed() {
        for (String text : List.of("", "\n\n", "# x\n\nsomething else", "associated tickets: `qits-1`\nmore",
                "associated tickets: qits-1", "associated tickets:  `qits-1`", "associated tickets: `qits`",
                "associated tickets: `qits-1` ")) {
            assertThatThrownBy(() -> AssociatedTickets.parse(text)).as(text)
                    .isInstanceOf(AssociatedTickets.Malformed.class);
        }
    }
}
