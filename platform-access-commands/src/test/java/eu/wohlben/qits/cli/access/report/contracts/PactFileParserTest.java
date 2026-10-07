package eu.wohlben.qits.cli.access.report.contracts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Committed pacts, v4 from pact-js and pact-jvm, hand-written v3 and v2, and a JSON that is not a pact. */
class PactFileParserTest {

    @TempDir
    Path root;

    private final PactFileParser parser = new PactFileParser();

    @Test
    void discoversOnlyTheFlatJsonFilesUnderPactsAtTheRoot() throws IOException {
        ContractFixtures.copy("contracts/pact/qits-old-app_qits-fx-service-v2.json", root, "pacts/b.json");
        ContractFixtures.copy("contracts/pact/qits-old-app_qits-fx-service-v2.json", root, "pacts/a.json");
        ContractFixtures.copy("contracts/pact/qits-old-app_qits-fx-service-v2.json", root, "pacts/nested/c.json");
        ContractFixtures.copy("contracts/pact/qits-old-app_qits-fx-service-v2.json", root, "target/pacts/d.json");
        ContractFixtures.copy("contracts/pact/qits-old-app_qits-fx-service-v2.json", root,
                "node_modules/x/pacts/e.json");
        ContractFixtures.copy("contracts/pact/qits-old-app_qits-fx-service-v2.json", root, "service/pacts/f.json");
        Files.writeString(root.resolve("pacts/README.md"), "not json");

        assertThat(parser.discover(root)).containsExactly(root.resolve("pacts/a.json"), root.resolve("pacts/b.json"));
        assertThat(parser.discover(root.resolve("absent"))).isEmpty();
    }

    @Test
    void readsPactJsV4() throws IOException {
        Path file = ContractFixtures.copy("contracts/landing/pacts/qits-landing-app_qits-githost-service.json", root,
                "pacts/qits-landing-app_qits-githost-service.json");

        ContractFile parsed = parser.parse(file, ContractFixtures.step(root));

        assertThat(parsed.source()).isEqualTo(new ContractSource("json", "pact",
                "pacts/qits-landing-app_qits-githost-service.json", "pact-v4"));
        assertThat(parsed.side()).isEqualTo(ContractFile.CONSUMER);
        assertThat(parsed.providerStates()).isNull();
        PactEntry pact = parsed.pacts().getFirst();
        assertThat(pact.role()).isEqualTo("CONSUMER");
        assertThat(pact.consumer()).isEqualTo("qits-landing-app");
        assertThat(pact.provider()).isEqualTo("qits-githost-service");
        assertThat(pact.specification()).isEqualTo("4.0");
        assertThat(pact.source()).isEqualTo("pacts/qits-landing-app_qits-githost-service.json");
        assertThat(pact.interactions()).hasSize(5);
        InteractionEntry first = pact.interactions().stream()
                .filter(i -> i.description().equals("show-project-loc: listLoc")).findFirst().orElseThrow();
        assertThat(first.providerStates()).containsExactly("a repository counted at an older commit");
        assertThat(first.type()).isEqualTo("Synchronous/HTTP");
        assertThat(first.method()).isEqualTo("GET");
        assertThat(first.path()).isEqualTo("/githost/api/loc");
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.contentHash()).matches("sha256:[0-9a-f]{64}");
        assertThat(first.verified()).isNull();
        assertThat(pact.interactions()).allSatisfy(i -> {
            assertThat(i.description()).isNotBlank();
            assertThat(i.providerStates()).isNotEmpty();
            assertThat(i.method()).isNotNull();
            assertThat(i.status()).isNotNull();
        });
    }

    @Test
    void readsPactJvmV4WithItsKeys() throws IOException {
        Path file = ContractFixtures.copy("contracts/pact/qits-workspaces-service_qits-projects-service.json", root,
                "pacts/qits-workspaces-service_qits-projects-service.json");

        PactEntry pact = parser.parse(file, ContractFixtures.step(root)).pacts().getFirst();

        assertThat(pact.consumer()).isEqualTo("qits-workspaces-service");
        assertThat(pact.provider()).isEqualTo("qits-projects-service");
        assertThat(pact.interactions()).hasSize(8);
        assertThat(pact.interactions()).anySatisfy(i -> {
            assertThat(i.description()).isEqualTo("captureWorkspace: getRepository");
            assertThat(i.providerStates()).containsExactly("a repository exists");
            assertThat(i.path()).isEqualTo("/projects/api/repositories/00000000-0000-4000-8000-000000000002");
        });
    }

    @Test
    void readsV3InteractionsAndMessages() throws IOException {
        Path file = ContractFixtures.copy("contracts/pact/qits-fx-app_qits-fx-service-v3.json", root,
                "pacts/qits-fx-app_qits-fx-service.json");

        ContractFile parsed = parser.parse(file, ContractFixtures.step(root));

        assertThat(parsed.source().format()).isEqualTo("pact-v3");
        PactEntry pact = parsed.pacts().getFirst();
        assertThat(pact.specification()).isEqualTo("3.0.0");
        List<InteractionEntry> interactions = pact.interactions();
        assertThat(interactions).extracting(InteractionEntry::description)
                .containsExactly("list ledgers", "delete a ledger", "a ledger closed event");
        InteractionEntry list = interactions.get(0);
        assertThat(list.providerStates()).containsExactly("two ledgers", "a signed-in user");
        assertThat(list.type()).isEqualTo("Synchronous/HTTP");
        assertThat(list.method()).isEqualTo("GET");
        assertThat(list.status()).isEqualTo(200);
        InteractionEntry message = interactions.get(2);
        assertThat(message.type()).isEqualTo("Asynchronous/Messages");
        assertThat(message.providerStates()).containsExactly("a closed ledger");
        assertThat(message.method()).isNull();
        assertThat(message.path()).isNull();
        assertThat(message.status()).isNull();
        assertThat(message.contentHash()).matches("sha256:[0-9a-f]{64}");
    }

    @Test
    void readsV2SingleProviderStates() throws IOException {
        Path file = ContractFixtures.copy("contracts/pact/qits-old-app_qits-fx-service-v2.json", root,
                "pacts/qits-old-app_qits-fx-service.json");

        ContractFile parsed = parser.parse(file, ContractFixtures.step(root));

        assertThat(parsed.source().format()).isEqualTo("pact-v2");
        assertThat(parsed.pacts().getFirst().interactions())
                .extracting(InteractionEntry::description, InteractionEntry::providerStates, InteractionEntry::status)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("get a ledger", List.of("one ledger"), 200),
                        org.assertj.core.groups.Tuple.tuple("health", List.of(), 200));
    }

    @Test
    void aJsonThatIsNotAPactIsRefusedWithItsReason() throws IOException {
        Path file = ContractFixtures.copy("contracts/pact/not-a-pact.json", root, "pacts/package.json");
        Path array = root.resolve("pacts/array.json");
        Files.writeString(array, "[1, 2]");
        Path providerless = root.resolve("pacts/providerless.json");
        Files.writeString(providerless, "{\"consumer\": {\"name\": \"a\"}, \"interactions\": []}");

        assertThatThrownBy(() -> parser.parse(file, ContractFixtures.step(root)))
                .isInstanceOf(IOException.class).hasMessage("not a pact: no consumer");
        assertThatThrownBy(() -> parser.parse(array, ContractFixtures.step(root)))
                .isInstanceOf(IOException.class).hasMessage("not a pact: not a JSON object");
        assertThatThrownBy(() -> parser.parse(providerless, ContractFixtures.step(root)))
                .isInstanceOf(IOException.class).hasMessage("not a pact: no provider");
    }
}
