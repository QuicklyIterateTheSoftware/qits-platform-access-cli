package eu.wohlben.qits.cli.access.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>The consumer half of one provider's contract</b> (ticket qits-1149), one subclass per provider.
 * <p>
 * {@link #everyRowIsWhatTheCommandAsksAndReads}: each live row's real client, over the real {@code
 * PlatformClient}, against a pact-jvm mock server answering that row's interaction and nothing else
 * (one server per row: two rows may send the same request and differ only in their command), with
 * the row's own assertions on what the command reads; and the client method the call went through
 * is the one the row names ({@link Reached}). A pending row is skipped, naming the provider state it
 * needs and its door.
 * <p>
 * {@link #theCommittedPactIsWhatTheRowsWrite}: the committed {@code pacts/} file, byte for byte;
 * {@code -Dgolden.update=true} writes it again. {@link #everyInteractionCarriesBothReferences}:
 * {@code qits-call} and a {@code qits-trigger} of kind {@code command} on every interaction.
 */
public abstract class CliPactTest {

    protected abstract CliPact contract();

    @TestFactory
    Stream<DynamicTest> everyRowIsWhatTheCommandAsksAndReads() {
        CliPact contract = contract();
        assertThat(contract.rows()).isNotEmpty();
        return contract.rows().stream().map(row -> DynamicTest.dynamicTest(
                row.description() + " [" + row.state() + "]", () -> {
                    Assumptions.assumeTrue(contract.recorded(row), () -> contract.needs(row));
                    Set<String> reached = Reached.during(() -> {
                        contract.run(row);
                        return null;
                    });
                    if (!reached.isEmpty()) {
                        assertThat(reached).as("%s: the client methods the call went through", row.description())
                                .contains(row.reaches());
                    }
                }));
    }

    @Test
    void theCommittedPactIsWhatTheRowsWrite() {
        contract().compareOrWritePactFile();
    }

    @Test
    void everyInteractionCarriesBothReferences() {
        CliPact contract = contract();
        Assumptions.assumeFalse(contract.live().isEmpty(), "no row of " + contract.provider() + " is recorded yet");
        // qits-pact-consumer's assertEveryInteractionCarriesBothReferences, over the pact the file
        // holds (CliPact#pact(List), corrected) rather than qits-pact-consumer's own, which pact-jvm
        // cannot write while a JSON string request body is in it uncorrected.
        JsonNode pact = contract.normalisedPact();
        assertThat(pact.path("consumer").path("name").asText()).isEqualTo(CliPact.CONSUMER);
        assertThat(pact.path("provider").path("name").asText()).isEqualTo(contract.provider());
        assertThat(pact.path("metadata").path("pactSpecification").path("version").asText()).isEqualTo("4.0");
        assertThat(pact.path("interactions")).as("one interaction per recorded row").hasSize(contract.live().size());
        pact.path("interactions").forEach(interaction -> {
            String description = interaction.path("description").asText();
            var references = interaction.path("comments").path("references");
            var call = references.path("qits-call");
            assertStrings(description, call, 2);
            assertThat(call.path("app").asText()).as(description).isEqualTo(contract.provider());
            assertThat(call.path("operationId").asText()).as(description).isNotBlank();
            var trigger = references.path("qits-trigger");
            assertStrings(description, trigger, 3);
            assertThat(trigger.path("kind").asText()).isEqualTo("command");
            assertThat(trigger.path("app").asText()).isEqualTo(CliPact.CONSUMER);
            assertThat(trigger.path("command").asText()).startsWith("qits ");
            assertThat(references.has("qits-via")).as("%s: the CLI calls the provider itself, no qits-via",
                    description).isFalse();
        });
    }

    private static void assertStrings(String description, JsonNode group, int size) {
        assertThat(group.isObject()).as("%s: reference group missing", description).isTrue();
        assertThat(group.size()).as("%s: %s holds other keys", description, group).isEqualTo(size);
        group.properties().forEach(e -> assertThat(e.getValue().isTextual() && !e.getValue().asText().isBlank())
                .as("%s: %s must be a non-blank string, got %s", description, e.getKey(), e.getValue()).isTrue());
    }
}
