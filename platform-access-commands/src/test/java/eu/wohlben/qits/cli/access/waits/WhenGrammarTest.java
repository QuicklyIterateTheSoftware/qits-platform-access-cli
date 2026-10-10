package eu.wohlben.qits.cli.access.waits;

import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.waits.WhenGrammar.Condition;
import eu.wohlben.qits.cli.access.waits.WhenGrammar.Op;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WhenGrammarTest {

    @Test
    void readsEveryForm() throws CliFailure {
        assertThat(WhenGrammar.parse("runId=r-1 branch^=release/ triggerEventId? !phase")).containsExactly(
                new Condition("runId", Op.EXACT, "r-1"),
                new Condition("branch", Op.PREFIX, "release/"),
                new Condition("triggerEventId", Op.EXISTS, null),
                new Condition("phase", Op.ABSENT, null));
    }

    @Test
    void readsArrayElementsAndNestedPaths() throws CliFailure {
        assertThat(WhenGrammar.parse("entities[].status=VERIFIED a.b[].c?")).containsExactly(
                new Condition("entities[].status", Op.EXACT, "VERIFIED"),
                new Condition("a.b[].c", Op.EXISTS, null));
    }

    @Test
    void readsQuotedValuesWithSpacesAndEscapes() throws CliFailure {
        assertThat(WhenGrammar.parse("  label=\"two words\"   note=\"say \\\"hi\\\"\" empty=\"\" ")).containsExactly(
                new Condition("label", Op.EXACT, "two words"),
                new Condition("note", Op.EXACT, "say \"hi\""),
                new Condition("empty", Op.EXACT, ""));
    }

    @Test
    void writesTheWireShape() throws CliFailure {
        assertThat(WhenGrammar.json(List.of(WhenGrammar.parse("runId=r-1 !phase"), WhenGrammar.parse("x^=y z?")))
                .toString()).isEqualTo("[[{\"path\":\"runId\",\"op\":\"exact\",\"value\":\"r-1\"},"
                + "{\"path\":\"phase\",\"op\":\"exists\",\"value\":false}],"
                + "[{\"path\":\"x\",\"op\":\"prefix\",\"value\":\"y\"},{\"path\":\"z\",\"op\":\"exists\",\"value\":true}]]");
    }

    @Test
    void writesAGroupBackAsItIsTyped() throws CliFailure {
        String typed = "runId=r-1 label=\"two words\" branch^=main z? !phase";
        assertThat(WhenGrammar.written(WhenGrammar.json(List.of(WhenGrammar.parse(typed))).get(0))).isEqualTo(typed);
    }

    @Test
    void readsTheLegacyAbsentOpBack() throws Exception {
        assertThat(WhenGrammar.written(new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree("[{\"path\":\"a\",\"op\":\"absent\"},{\"path\":\"b\",\"op\":\"EXISTS\",\"value\":true}]")))
                .isEqualTo("!a b?");
    }

    @Test
    void holdsTheLibrarysLimits() {
        assertThrows(CliFailure.class, () -> WhenGrammar.parse("a=1 a=2 a=3 a=4 a=5 a=6 a=7 a=8 a=9"));
        assertThrows(CliFailure.class, () -> WhenGrammar.parse("a=" + "x".repeat(1025)));
        assertThrows(CliFailure.class, () -> WhenGrammar.parse("a".repeat(257) + "=x"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "runId", "runId==x", "runId!=x", "runId~x", "runId>=3", "=x", "!", "!a=b",
            "a..b=x", "a[=x", "a[]x=y", ".a=x", "runId=", "runId=\"open", "runId=ab\"c", "runId=\"a\"b", "x?y",
            "entities[0].status=X", "1a=b", "-a=b", "$a=b", "a^=\"\"", "a.b.c.d.e.f.g.h.i=x", "!a?"})
    void refusesWhatItCannotRead(String group) {
        CliFailure refused = assertThrows(CliFailure.class, () -> WhenGrammar.parse(group));
        assertThat(refused.exitCode()).isEqualTo(CliFailure.USAGE);
        assertThat(refused.getMessage()).startsWith("--when");
    }
}
