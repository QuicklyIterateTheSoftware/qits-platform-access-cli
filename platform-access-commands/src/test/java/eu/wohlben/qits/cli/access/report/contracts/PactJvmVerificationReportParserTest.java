package eu.wohlben.qits.cli.access.report.contracts;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.report.ReportJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pact-JVM 4.6.21's JSON verification report, as qits-projects-service's ConsumerPactVerificationTest
 * wrote it: one file for the provider, both consumers in it, one execution per interaction.
 */
class PactJvmVerificationReportParserTest {

    private static final String REPORT = ".qits-reports/pact-verification/qits-projects-service.json";

    @TempDir
    Path root;

    private final PactJvmVerificationReportParser parser = new PactJvmVerificationReportParser();

    @Test
    void discoversTheReportsInTheVerificationDirectory() {
        ContractFixtures.copy("contracts/pact-verification/qits-projects-service.json", root, REPORT);
        ContractFixtures.copy("contracts/pact-verification/qits-projects-service.json", root,
                ".qits-reports/pact-verification/nested/x.json");
        ContractFixtures.copy("contracts/pact-verification/qits-projects-service.json", root,
                "service/target/pact/reports/qits-projects-service.json");

        assertThat(parser.discover(root)).containsExactly(root.resolve(REPORT));
    }

    @Test
    void theMeasuredReportIsTwoProviderPactsEveryInteractionVerified() throws IOException {
        Path file = ContractFixtures.copy("contracts/pact-verification/qits-projects-service.json", root, REPORT);

        ContractFile parsed = parser.parse(file, ContractFixtures.step(root));

        assertThat(parsed.source()).isEqualTo(new ContractSource("java", "pact-jvm", REPORT, "pact-jvm-json-report"));
        assertThat(parsed.side()).isEqualTo(ContractFile.PROVIDER);
        assertThat(parsed.pacts()).extracting(PactEntry::role, PactEntry::consumer, PactEntry::provider,
                        PactEntry::source, p -> p.interactions().size())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("PROVIDER", "qits-landing-app", "qits-projects-service",
                                REPORT, 5),
                        org.assertj.core.groups.Tuple.tuple("PROVIDER", "qits-workspaces-service",
                                "qits-projects-service", REPORT, 8));
        // The report does not say which specification a pact declared.
        assertThat(parsed.pacts()).allSatisfy(p -> assertThat(p.specification()).isNull());
        List<InteractionEntry> all = parsed.pacts().stream().flatMap(p -> p.interactions().stream()).toList();
        assertThat(all).allSatisfy(i -> {
            assertThat(i.description()).isNotBlank();
            assertThat(i.providerStates()).isNotEmpty();
            assertThat(i.type()).isEqualTo("Synchronous/HTTP");
            assertThat(i.method()).isNotNull();
            assertThat(i.status()).isNotNull();
            assertThat(i.contentHash()).matches("sha256:[0-9a-f]{64}");
            assertThat(i.verified()).isEqualTo("OK");
        });
        assertThat(all).anySatisfy(i -> {
            assertThat(i.description()).isEqualTo("list-projects: listProjects");
            assertThat(i.providerStates()).containsExactly("a project exists");
            assertThat(i.method()).isEqualTo("GET");
            assertThat(i.path()).isEqualTo("/projects/api/projects");
            assertThat(i.status()).isEqualTo(200);
        });
    }

    @Test
    void aFailedInteractionIsFailed() throws IOException {
        Path file = ContractFixtures.copy("contracts/pact-verification/qits-projects-service-failed.json", root, REPORT);

        List<PactEntry> pacts = parser.parse(file, ContractFixtures.step(root)).pacts();

        assertThat(pacts.get(0).interactions()).allSatisfy(i -> assertThat(i.verified()).isEqualTo("OK"));
        assertThat(pacts.get(1).interactions()).filteredOn(i -> "FAILED".equals(i.verified())).hasSize(1);
        assertThat(pacts.get(1).interactions()).filteredOn(i -> "OK".equals(i.verified())).hasSize(7);
    }

    @Test
    void anInteractionTheReportListsTwiceCountsOnceAsItsLastResult() throws IOException {
        ObjectNode report = (ObjectNode) ReportJson.MAPPER.readTree(
                ContractFixtures.resource("contracts/pact-verification/qits-projects-service.json").toFile());
        ArrayNode executions = (ArrayNode) report.get("execution");
        ObjectNode again = executions.get(0).deepCopy();
        ((ObjectNode) again.get("interactions").get(0).get("verification")).put("result", "failed");
        executions.add(again);
        Path file = root.resolve(REPORT);
        Files.createDirectories(file.getParent());
        ReportJson.MAPPER.writeValue(file.toFile(), report);

        List<PactEntry> pacts = parser.parse(file, ContractFixtures.step(root)).pacts();

        assertThat(pacts.get(0).interactions()).hasSize(5)
                .filteredOn(i -> "FAILED".equals(i.verified())).hasSize(1);
    }

    @Test
    void aFileThatIsNotAReportIsRefused() throws IOException {
        Path file = root.resolve(REPORT);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"provider\": {\"name\": \"x\"}}");

        assertThatThrownBy(() -> parser.parse(file, ContractFixtures.step(root))).isInstanceOf(IOException.class)
                .hasMessageContaining("not a Pact-JVM verification report");
    }
}
