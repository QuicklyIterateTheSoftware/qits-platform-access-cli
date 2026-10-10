package eu.wohlben.qits.cli.access.platform;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A table cell: one line, cut to its width, and never a terminal control sequence. */
class TableTest {

    @Test
    void escapeSequencesInACellDoNotReachTheTerminal() {
        String summary = "Release main\u001b[2J\u001b]0;owned\u0007 now\u009b31m";

        String cell = Table.cell(summary, 60);

        assertThat(cell).isEqualTo("Release main now");
        assertThat(cell.chars()).noneMatch(c -> c < 0x20 || (c >= 0x7f && c <= 0x9f));
    }

    @Test
    void lineBreaksBecomeOneSpace() {
        assertThat(Table.cell("first\n\tsecond", 60)).isEqualTo("first second");
    }

    @Test
    void nothingIsADash() {
        assertThat(Table.cell(null, 10)).isEqualTo("-");
        assertThat(Table.cell("\u001b[0m", 10)).isEqualTo("-");
    }

    @Test
    void aLongCellIsCutWithAnEllipsis() {
        assertThat(Table.cell("abcdefghij", 5)).isEqualTo("abcd…");
    }
}
