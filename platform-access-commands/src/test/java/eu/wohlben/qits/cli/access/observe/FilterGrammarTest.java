package eu.wohlben.qits.cli.access.observe;

import eu.wohlben.qits.cli.access.observe.FilterGrammar.Condition;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilterGrammarTest {

    private static Condition only(String filter) throws CliFailure {
        List<Condition> conditions = FilterGrammar.parse(filter);
        assertThat(conditions).as(filter).hasSize(1);
        return conditions.getFirst();
    }

    @Test
    void everyOperator() throws Exception {
        assertThat(only("kind=log")).isEqualTo(new Condition("kind", null, "exact", "log"));
        assertThat(only("service^=qits-ci")).isEqualTo(new Condition("service", null, "prefix", "qits-ci"));
        assertThat(only("event?")).isEqualTo(new Condition("event", null, "exists", true));
        assertThat(only("!trace")).isEqualTo(new Condition("traceId", null, "exists", false));
        assertThat(only("level>=ERROR")).isEqualTo(new Condition("severity", null, "min", "ERROR"));
        assertThat(only("body~refused")).isEqualTo(new Condition("body", null, "contains", "refused"));
    }

    @Test
    void everyFieldHasItsWireName() throws Exception {
        assertThat(only("kind=span").field()).isEqualTo("kind");
        assertThat(only("service=qits-ci").field()).isEqualTo("service");
        assertThat(only("trace=4bf9").field()).isEqualTo("traceId");
        assertThat(only("span=00f0").field()).isEqualTo("spanId");
        assertThat(only("level>=INFO").field()).isEqualTo("severity");
        assertThat(only("body~x").field()).isEqualTo("body");
        assertThat(only("name^=GET").field()).isEqualTo("name");
        assertThat(only("status=ERROR").field()).isEqualTo("status");
        assertThat(only("event=exception").field()).isEqualTo("event");
        assertThat(only("attr.k=v")).isEqualTo(new Condition("attribute", "k", "exact", "v"));
        assertThat(only("resource.k=v")).isEqualTo(new Condition("resource", "k", "exact", "v"));
    }

    @Test
    void idsAreLowercaseAndSeveritiesAreNamesOrNumbers() throws Exception {
        assertThat(only("trace=4BF92F35").value()).isEqualTo("4bf92f35");
        assertThat(only("span^=00F0").value()).isEqualTo("00f0");
        assertThat(only("body~ABC").value()).isEqualTo("ABC");
        assertThat(only("level>=warn").value()).isEqualTo("WARN");
        assertThat(only("level>=Warning").value()).isEqualTo("WARN");
        assertThat(only("level>=fatal").value()).isEqualTo("FATAL");
        assertThat(only("level>=17").value()).isEqualTo(17);
        assertThat(only("level>=1").value()).isEqualTo(1);
        assertThat(only("level>=24").value()).isEqualTo(24);
    }

    @Test
    void attributeKeysKeepTheirDots() throws Exception {
        assertThat(only("attr.exception.type?")).isEqualTo(new Condition("attribute", "exception.type", "exists", true));
        assertThat(only("resource.service.instance.id=abc-1"))
                .isEqualTo(new Condition("resource", "service.instance.id", "exact", "abc-1"));
        assertThat(only("!attr.http.route")).isEqualTo(new Condition("attribute", "http.route", "exists", false));
        assertThat(only("attr.http.route=/a=b")).isEqualTo(new Condition("attribute", "http.route", "exact", "/a=b"));
        assertThat(only("resource.deployment.environment.name^=dev"))
                .isEqualTo(new Condition("resource", "deployment.environment.name", "prefix", "dev"));
        assertThat(only("attr.exception.message~timeout"))
                .isEqualTo(new Condition("attribute", "exception.message", "contains", "timeout"));
    }

    @Test
    void aQuotedValueMayHoldSpaces() throws Exception {
        assertThat(FilterGrammar.parse("kind=log body~\"connection refused\"")).containsExactly(
                new Condition("kind", null, "exact", "log"),
                new Condition("body", null, "contains", "connection refused"));
        assertThat(only("body~\"say \\\"hi\\\" \\\\o/\"").value()).isEqualTo("say \"hi\" \\o/");
        assertThat(only("attr.path=\"C:\\temp\"").value()).isEqualTo("C:\\temp");
        assertThat(only("name=\"GET  /x\"").value()).isEqualTo("GET  /x");
        assertThat(FilterGrammar.parse("  kind=log \t  service=qits-ci  ")).hasSize(2);
    }

    @Test
    void aStarAloneStreamsEverything() throws Exception {
        assertThat(FilterGrammar.parse("*")).isEmpty();
        assertThat(FilterGrammar.parse("  *  ")).isEmpty();
    }

    @Test
    void aMistakeIsAUsageErrorThatNamesTheCondition() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("foo=bar", "--filter: 'foo=bar' names the field 'foo', which qits does not know. Fields: kind,");
        cases.put("Kind=log", "'Kind=log' names the field 'Kind'");
        cases.put("attr=x", "'attr=x' names the field 'attr'");
        cases.put("attr.=x", "'attr.=x' names no key. Write attr.<key>");
        cases.put("resource.?", "'resource.?' names no key. Write resource.<key>");
        cases.put("kind!=log", "'kind!=log' has an operator qits does not know");
        cases.put("kind==log", "'kind==log' has an operator qits does not know");
        cases.put("level<=ERROR", "'level<=ERROR' has an operator qits does not know");
        cases.put("level>ERROR", "'level>ERROR' has an operator qits does not know");
        cases.put("level=ERROR", "'level=ERROR' does not work on level. The level takes >= only");
        cases.put("level~ERR", "'level~ERR' does not work on level");
        cases.put("level?", "'level?' does not work on level");
        cases.put("!level", "'!level' does not work on level");
        cases.put("service>=x", "'service>=x' uses >=, which works on level only");
        cases.put("level>=LOUD", "'level>=LOUD' names no severity. Give TRACE, DEBUG, INFO, WARN, ERROR, FATAL or a number 1-24.");
        cases.put("level>=25", "'level>=25' names no severity");
        cases.put("level>=0", "'level>=0' names no severity");
        cases.put("level>=", "'level>=' has an empty value");
        cases.put("kind=", "'kind=' has an empty value");
        cases.put("body~\"\"", "'body~\"\"' has an empty value");
        cases.put("kind", "'kind' has no operator");
        cases.put("=log", "'=log' names no field");
        cases.put("!", "'!' names no field");
        cases.put("!kind=log", "'!kind=log' is not a condition. !F takes a field alone");
        cases.put("event?x", "'event?x' has text after the ?");
        cases.put("body~\"open", "'body~\"open' has a quote that is not closed");
        cases.put("body~\"a\"b", "'body~\"a\"b' has text after the closing quote");
        cases.put("body~a\"b\"", "'body~a\"b\"' has a quote inside the value");
        cases.put("kind=log *", "--filter 'kind=log *': * stands alone");
        cases.put("   ", "--filter is empty");
        cases.put("", "--filter is empty");
        cases.forEach((filter, message) -> assertThatThrownBy(() -> FilterGrammar.parse(filter))
                .as(filter)
                .isInstanceOf(CliFailure.class)
                .hasMessageContaining(message)
                .satisfies(e -> assertThat(((CliFailure) e).exitCode()).isEqualTo(CliFailure.USAGE)));
    }

    /** The plan's three examples, on the wire. The server is built from the same text. */
    @Test
    void thePlansExamplesAsTheWireHasThem() throws Exception {
        String logErrors = "{\"conditions\":[{\"field\":\"kind\",\"op\":\"exact\",\"value\":\"log\"},"
                + "{\"field\":\"severity\",\"op\":\"min\",\"value\":\"ERROR\"}]}";
        String oneTrace = "{\"conditions\":[{\"field\":\"traceId\",\"op\":\"exact\",\"value\":\"4bf92f3577b34da6a3ce929d0e0e4736\"}]}";
        String ciExceptions = "{\"conditions\":[{\"field\":\"service\",\"op\":\"prefix\",\"value\":\"qits-ci\"},"
                + "{\"field\":\"attribute\",\"key\":\"exception.type\",\"op\":\"exists\",\"value\":true}]}";

        assertThat(frame("kind=log level>=ERROR")).isEqualTo("{\"subscribe\":[" + logErrors + "]}");
        assertThat(frame("trace=4bf92f3577b34da6a3ce929d0e0e4736")).isEqualTo("{\"subscribe\":[" + oneTrace + "]}");
        assertThat(frame("service^=qits-ci attr.exception.type?")).isEqualTo("{\"subscribe\":[" + ciExceptions + "]}");
        assertThat(frame("kind=log level>=ERROR", "trace=4bf92f3577b34da6a3ce929d0e0e4736", "service^=qits-ci attr.exception.type?"))
                .isEqualTo("{\"subscribe\":[" + logErrors + "," + oneTrace + "," + ciExceptions + "]}");
    }

    @Test
    void otherValuesOnTheWire() throws Exception {
        assertThat(frame("*")).isEqualTo("{\"subscribe\":[{\"conditions\":[]}]}");
        assertThat(frame("*", "kind=metric")).isEqualTo("{\"subscribe\":[{\"conditions\":[]},"
                + "{\"conditions\":[{\"field\":\"kind\",\"op\":\"exact\",\"value\":\"metric\"}]}]}");
        assertThat(frame("level>=17 !span resource.service.name~CI body~\"say \\\"hi\\\"\"")).isEqualTo(
                "{\"subscribe\":[{\"conditions\":[{\"field\":\"severity\",\"op\":\"min\",\"value\":17},"
                        + "{\"field\":\"spanId\",\"op\":\"exists\",\"value\":false},"
                        + "{\"field\":\"resource\",\"key\":\"service.name\",\"op\":\"contains\",\"value\":\"CI\"},"
                        + "{\"field\":\"body\",\"op\":\"contains\",\"value\":\"say \\\"hi\\\"\"}]}]}");
    }

    private static String frame(String... filters) throws CliFailure {
        List<List<Condition>> groups = new java.util.ArrayList<>();
        for (String filter : filters) {
            groups.add(FilterGrammar.parse(filter));
        }
        return FilterGrammar.subscribeFrame(groups);
    }
}
