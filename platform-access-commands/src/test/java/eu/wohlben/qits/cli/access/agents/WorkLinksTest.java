package eu.wohlben.qits.cli.access.agents;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Which ids are linked, and how. */
class WorkLinksTest {

    private static final String DETAIL = "https://qits.wohlben.eu/projects/qits/work/detail/";

    private final WorkLinks links = new WorkLinks(List.of("qits", "qits-ci"), "https://qits.wohlben.eu",
            WorkLinks.Style.MARKDOWN);

    private String shown(String text) {
        return links.link(text, false).text();
    }

    @Test
    void anIdThatStandsAloneIsLinked() {
        assertThat(shown("See qits-1152 now.\n")).isEqualTo("See [qits-1152](" + DETAIL + "qits-1152) now.\n");
        assertThat(shown("(qits-1158).")).isEqualTo("([qits-1158](" + DETAIL + "qits-1158)).");
    }

    @Test
    void anIdInsideSomethingLongerStaysAsItIs() {
        for (String text : List.of("ticket/qits-1152", "qits-1152-agent-worktrees", "[qits-7](x)", "utf-8",
                "a.qits-3", "xqits-3", "qits-3x", "-qits-3", "qits-")) {
            assertThat(shown(text)).as(text).isEqualTo(text);
        }
    }

    @Test
    void theLongestSlugWins() {
        assertThat(shown("qits-ci-5")).isEqualTo("[qits-ci-5](https://qits.wohlben.eu/projects/qits-ci/work/detail/qits-ci-5)");
    }

    @Test
    void inlineCodeStaysAsItIs() {
        assertThat(shown("`qits-5` and qits-6")).isEqualTo("`qits-5` and [qits-6](" + DETAIL + "qits-6)");
    }

    @Test
    void aFencedBlockStaysAsItIsAcrossBatches() {
        WorkLinks.Result first = links.link("qits-1\n```\nqits-2\n", false);
        assertThat(first.text()).isEqualTo("[qits-1](" + DETAIL + "qits-1)\n```\nqits-2\n");
        assertThat(first.inFence()).isTrue();

        WorkLinks.Result second = links.link("qits-3\n~~~\nqits-4\n", first.inFence());
        assertThat(second.text()).isEqualTo("qits-3\n~~~\n[qits-4](" + DETAIL + "qits-4)\n");
        assertThat(second.inFence()).isFalse();
    }

    @Test
    void osc8IsATerminalHyperlink() {
        WorkLinks osc8 = new WorkLinks(List.of("qits"), "https://qits.wohlben.eu", WorkLinks.Style.OSC8);
        assertThat(osc8.link("qits-9", false).text())
                .isEqualTo("\u001b]8;;" + DETAIL + "qits-9\u001b\\qits-9\u001b]8;;\u001b\\");
    }

    @Test
    void theStyleIsReadByName() {
        assertThat(WorkLinks.Style.of(" OSC8 ")).isEqualTo(WorkLinks.Style.OSC8);
        assertThat(WorkLinks.Style.of("markdown")).isEqualTo(WorkLinks.Style.MARKDOWN);
        assertThat(WorkLinks.Style.of("html")).isNull();
    }
}
