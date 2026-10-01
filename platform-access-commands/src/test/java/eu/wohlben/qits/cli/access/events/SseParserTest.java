package eu.wohlben.qits.cli.access.events;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SseParserTest {

    private static List<SseParser.Event> parse(String... lines) {
        SseParser parser = new SseParser();
        List<SseParser.Event> events = new ArrayList<>();
        for (String line : lines) {
            parser.accept(line).ifPresent(events::add);
        }
        return events;
    }

    @Test
    void anEventIsItsIdAndData() {
        assertThat(parse(": open", "id:e-1", "data:{\"name\":\"BuildSuccessful\"}", ""))
                .containsExactly(new SseParser.Event("e-1", null, "{\"name\":\"BuildSuccessful\"}"));
    }

    @Test
    void commentsAreNoEvents() {
        assertThat(parse(": open", "", ": keepalive", "", ":", "")).isEmpty();
    }

    @Test
    void dataLinesJoinWithANewline() {
        assertThat(parse("data: {", "data:   \"a\": 1", "data:}", "")).extracting(SseParser.Event::data)
                .containsExactly("{\n  \"a\": 1\n}");
    }

    @Test
    void oneSpaceAfterTheColonIsNotPartOfTheValue() {
        assertThat(parse("data: x", "")).extracting(SseParser.Event::data).containsExactly("x");
        assertThat(parse("data:  x", "")).extracting(SseParser.Event::data).containsExactly(" x");
        assertThat(parse("data", "")).extracting(SseParser.Event::data).containsExactly("");
    }

    @Test
    void theIdCarriesOverAndTheTypeDoesNot() {
        assertThat(parse("id: 1", "event: special", "retry: 5000", "data: a", "", "data: b", ""))
                .containsExactly(new SseParser.Event("1", "special", "a"), new SseParser.Event("1", null, "b"));
    }

    @Test
    void anEventWithoutItsEmptyLineIsDropped() {
        assertThat(parse("id: 1", "data: cut off")).isEmpty();
    }
}
