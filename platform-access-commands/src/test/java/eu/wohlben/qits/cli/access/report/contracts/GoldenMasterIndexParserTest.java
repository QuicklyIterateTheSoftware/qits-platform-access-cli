package eu.wohlben.qits.cli.access.report.contracts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** qits-githost-service's golden-master index, and one of a format version this CLI does not read. */
class GoldenMasterIndexParserTest {

    @TempDir
    Path root;

    private final GoldenMasterIndexParser parser = new GoldenMasterIndexParser();

    @Test
    void discoversTheIndexAtTheRootOnly() {
        assertThat(parser.discover(root)).isEmpty();
        ContractFixtures.copy("contracts/golden-masters/index.json", root, "service/golden-masters/index.json");
        assertThat(parser.discover(root)).isEmpty();

        ContractFixtures.copy("contracts/golden-masters/index.json", root, "golden-masters/index.json");
        assertThat(parser.discover(root)).containsExactly(root.resolve("golden-masters/index.json"));
    }

    @Test
    void readsTheProviderAndItsStatesWithTheirOperations() throws IOException {
        Path file = ContractFixtures.copy("contracts/golden-masters/index.json", root, "golden-masters/index.json");

        ContractFile parsed = parser.parse(file, ContractFixtures.step(root));

        assertThat(parsed.source()).isEqualTo(new ContractSource("json", "qits-golden-masters",
                "golden-masters/index.json", "golden-master-index-v1"));
        assertThat(parsed.side()).isEqualTo(ContractFile.PROVIDER_STATES);
        assertThat(parsed.pacts()).isEmpty();
        ProviderStatesInventory states = parsed.providerStates();
        assertThat(states.provider()).isEqualTo("qits-githost");
        assertThat(states.states()).extracting(ProviderStateEntry::name).containsExactly(
                "a repository counted at an older commit", "a repository not counted yet",
                "a repository with counted lines", "a repository with no commit", "no repository with the given id",
                "two repositories, one counted");
        assertThat(states.states().getFirst().operations()).containsExactly("listLoc");
        assertThat(states.states()).allSatisfy(s -> assertThat(s.operations()).isNotEmpty());
    }

    @Test
    void anotherFormatVersionIsRefused() {
        Path file = ContractFixtures.copy("contracts/golden-masters/index-format-2.json", root,
                "golden-masters/index.json");

        assertThatThrownBy(() -> parser.parse(file, ContractFixtures.step(root))).isInstanceOf(IOException.class)
                .hasMessage("golden-master index formatVersion 2, only 1 is read");
    }
}
