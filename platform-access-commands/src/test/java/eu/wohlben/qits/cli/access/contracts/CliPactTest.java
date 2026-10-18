package eu.wohlben.qits.cli.access.contracts;

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
        contract.pact().assertEveryInteractionCarriesBothReferences();
        contract.pact().normalisedPact().path("interactions").forEach(interaction -> {
            var trigger = interaction.path("comments").path("references").path("qits-trigger");
            assertThat(trigger.path("kind").asText()).isEqualTo("command");
            assertThat(trigger.path("app").asText()).isEqualTo(CliPact.CONSUMER);
            assertThat(trigger.path("command").asText()).startsWith("qits ");
        });
    }
}
