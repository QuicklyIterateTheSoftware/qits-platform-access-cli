package eu.wohlben.qits.cli.access.report.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.report.Highlight;
import eu.wohlben.qits.cli.access.report.ReportJson;
import eu.wohlben.qits.cli.access.report.ReportKind;
import eu.wohlben.qits.cli.access.report.ReportKinds;
import eu.wohlben.qits.cli.access.report.ReportParser;
import eu.wohlben.qits.cli.access.report.StepContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** The contracts kind over whole trees: when it reports, what it merges, its cap and its highlights. */
class ContractsReportKindTest {

    @TempDir
    Path root;

    private final List<String> warnings = new ArrayList<>();
    private final ReportKinds kinds = ReportKinds.standard(warnings::add);
    private final ContractsReportKind kind = (ContractsReportKind) kinds.kinds().stream()
            .filter(k -> k.id().equals("contracts")).findFirst().orElseThrow();

    @Test
    void theRegistryListsTheKindAndItsThreeParsers() {
        assertThat(kinds.kinds()).extracting(ReportKind::id).containsExactly("test-results", "coverage", "contracts",
                "entity-changes", "screenshots");
        assertThat(kinds.parsersOf("contracts")).extracting(ReportParser::language, ReportParser::tool)
                .containsExactly(tuple("json", "pact"), tuple("java", "pact-jvm"), tuple("json", "qits-golden-masters"));
        assertThat(kind.version()).isEqualTo(1);
        assertThat(kind.payloadType()).isEqualTo(ContractsReport.class);
    }

    @Test
    void aStepThatRanNoTestsReportsNothingWhateverContractsItHas() throws IOException {
        landingPacts();
        ContractFixtures.copy("contracts/golden-masters/index.json", root, "golden-masters/index.json");

        assertThat(collect()).isEmpty();
    }

    @Test
    void aStepThatRanTestsButHasNoContractsReportsNothing() throws IOException {
        ContractFixtures.tested(root);

        assertThat(collect()).isEmpty();
    }

    @Test
    void surefireReportsAlsoCountAsTestsRun() throws IOException {
        ContractFixtures.copy("tree/service/target.fixture/surefire-reports/TEST-eu.wohlben.qits.fx.ClockTest.xml",
                root, "service/target/surefire-reports/TEST-eu.wohlben.qits.fx.ClockTest.xml");
        ContractFixtures.copy("contracts/golden-masters/index.json", root, "golden-masters/index.json");

        assertThat(collect()).isPresent();
    }

    @Test
    void aLandingLikeTreeIsFiveConsumerPacts() throws IOException {
        ContractFixtures.tested(root);
        landingPacts();

        ContractsReport report = collect().orElseThrow();

        assertThat(report.sides()).isEqualTo(new ContractSides(true, false, false));
        assertThat(report.providerStates()).isNull();
        assertThat(report.pacts()).extracting(PactEntry::role, PactEntry::provider, p -> p.interactions().size())
                .containsExactly(tuple("CONSUMER", "qits-events-service", 2),
                        tuple("CONSUMER", "qits-githost-service", 5),
                        tuple("CONSUMER", "qits-maintenance-service", 2),
                        tuple("CONSUMER", "qits-projects-service", 3),
                        tuple("CONSUMER", "qits-workspaces-service", 4));
        assertThat(report.pacts()).allSatisfy(p -> {
            assertThat(p.consumer()).isEqualTo("qits-landing-app");
            assertThat(p.interactions()).isSortedAccordingTo(ContractsReportKind.INTERACTION_ORDER);
        });
        assertThat(report.sources()).hasSize(5).allSatisfy(s -> assertThat(s.format()).isEqualTo("pact-v4"));
        assertThat(report.skipped()).isEmpty();
        assertThat(report.truncated()).isFalse();
        assertThat(kind.describe(report)).isEqualTo("5 pacts, 16 interactions, 0 states");
        assertThat(warnings).isEmpty();
    }

    @Test
    void thePayloadIsTheEpicsShapeAndTheSameBytesEveryTime() throws IOException {
        ContractFixtures.tested(root);
        landingPacts();
        ContractFixtures.copy("contracts/pact-verification/qits-projects-service-failed.json", root,
                ".qits-reports/pact-verification/qits-projects-service.json");
        ContractFixtures.copy("contracts/golden-masters/index.json", root, "golden-masters/index.json");

        ContractsReport report = collect().orElseThrow();
        JsonNode json = ReportJson.MAPPER.valueToTree(report);

        assertThat(json.fieldNames()).toIterable()
                .containsExactly("sources", "sides", "providerStates", "pacts", "skipped", "truncated");
        assertThat(json.path("sources").get(0).fieldNames()).toIterable()
                .containsExactly("language", "tool", "file", "format");
        assertThat(json.path("sides").fieldNames()).toIterable()
                .containsExactly("consumer", "provider", "providerStates");
        assertThat(json.path("providerStates").fieldNames()).toIterable().containsExactly("provider", "states");
        assertThat(json.path("providerStates").path("states").get(0).fieldNames()).toIterable()
                .containsExactly("name", "operations");
        assertThat(json.path("pacts").get(0).fieldNames()).toIterable()
                .containsExactly("role", "consumer", "provider", "specification", "source", "interactions");
        assertThat(json.path("pacts").get(0).path("interactions").get(0).fieldNames()).toIterable()
                .containsExactly("description", "providerStates", "type", "method", "path", "status", "contentHash",
                        "verified");
        assertThat(report.sides()).isEqualTo(new ContractSides(true, true, true));
        assertThat(report.pacts()).extracting(PactEntry::role, PactEntry::consumer, PactEntry::provider).containsExactly(
                tuple("CONSUMER", "qits-landing-app", "qits-events-service"),
                tuple("CONSUMER", "qits-landing-app", "qits-githost-service"),
                tuple("CONSUMER", "qits-landing-app", "qits-maintenance-service"),
                tuple("CONSUMER", "qits-landing-app", "qits-projects-service"),
                tuple("CONSUMER", "qits-landing-app", "qits-workspaces-service"),
                tuple("PROVIDER", "qits-landing-app", "qits-projects-service"),
                tuple("PROVIDER", "qits-workspaces-service", "qits-projects-service"));
        assertThat(report.sources()).extracting(ContractSource::tool)
                .containsExactly("pact", "pact", "pact", "pact", "pact", "pact-jvm", "qits-golden-masters");
        assertThat(report.stateCount()).isEqualTo(6);
        assertThat(ReportJson.MAPPER.writeValueAsString(collect().orElseThrow()))
                .isEqualTo(ReportJson.MAPPER.writeValueAsString(report));
        assertThat(ReportJson.MAPPER.readValue(ReportJson.MAPPER.writeValueAsBytes(report), ContractsReport.class))
                .isEqualTo(report);
    }

    @Test
    void aFileThatDoesNotParseIsSkippedWithOneWarningAndTheRestIsRead() throws IOException {
        ContractFixtures.tested(root);
        landingPacts();
        Files.writeString(root.resolve("pacts/broken.json"), "{\"consumer\": ");
        ContractFixtures.copy("contracts/pact/not-a-pact.json", root, "pacts/package.json");
        ContractFixtures.copy("contracts/golden-masters/index-format-2.json", root, "golden-masters/index.json");

        ContractsReport report = collect().orElseThrow();

        assertThat(report.pacts()).hasSize(5);
        assertThat(report.skipped()).extracting(SkippedFile::file)
                .containsExactly("pacts/broken.json", "pacts/package.json", "golden-masters/index.json");
        assertThat(report.skipped().get(1).reason()).isEqualTo("not a pact: no consumer");
        assertThat(report.sides().providerStates()).isFalse();
        assertThat(warnings).hasSize(3);
        assertThat(warnings.get(1)).isEqualTo("contracts: skipped pacts/package.json (pact): not a pact: no consumer");
        assertThat(warnings.get(2)).isEqualTo("contracts: skipped golden-masters/index.json (qits-golden-masters): "
                + "golden-master index formatVersion 2, only 1 is read");
    }

    @Test
    void onlyUnreadableInputsAreStillReportedSoTheSkipIsSeen() throws IOException {
        ContractFixtures.tested(root);
        Files.createDirectories(root.resolve("pacts"));
        Files.writeString(root.resolve("pacts/broken.json"), "nope");

        ContractsReport report = collect().orElseThrow();

        assertThat(report.pacts()).isEmpty();
        assertThat(report.sides()).isEqualTo(new ContractSides(false, false, false));
        assertThat(report.skipped()).hasSize(1);
    }

    @Test
    void moreThanTwoThousandInteractionsAreCut() throws IOException {
        ContractFixtures.tested(root);
        Files.createDirectories(root.resolve("pacts"));
        for (String provider : List.of("a-service", "b-service", "c-service")) {
            StringBuilder interactions = new StringBuilder();
            for (int i = 0; i < 700; i++) {
                interactions.append(i == 0 ? "" : ",").append("{\"type\": \"Synchronous/HTTP\", \"description\": \"op ")
                        .append(String.format("%04d", i)).append("\", \"request\": {\"method\": \"GET\", \"path\": \"/")
                        .append(i).append("\"}, \"response\": {\"status\": 200}}");
            }
            Files.writeString(root.resolve("pacts/x_" + provider + ".json"), "{\"consumer\": {\"name\": \"x\"}, "
                    + "\"provider\": {\"name\": \"" + provider + "\"}, \"interactions\": [" + interactions + "], "
                    + "\"metadata\": {\"pactSpecification\": {\"version\": \"4.0\"}}}");
        }

        ContractsReport report = collect().orElseThrow();

        assertThat(report.truncated()).isTrue();
        assertThat(report.interactionCount()).isEqualTo(2000);
        assertThat(report.pacts()).extracting(p -> p.interactions().size()).containsExactly(700, 700, 600);
        assertThat(report.pacts().get(2).interactions().getLast().description()).isEqualTo("op 0599");
        assertThat(ReportJson.MAPPER.writeValueAsBytes(report).length).isLessThan(1024 * 1024);
    }

    @Test
    void withoutABaselineTheHighlightIsTheInventory() throws IOException {
        ContractFixtures.tested(root);
        landingPacts();
        ContractFixtures.copy("contracts/golden-masters/index.json", root, "golden-masters/index.json");
        ContractsReport report = collect().orElseThrow();

        assertThat(kind.highlight(report, Optional.empty(), ContractFixtures.step(root)))
                .containsExactly(new Highlight("info", "contracts: 5 pacts, 16 interactions, 6 states (no baseline)",
                        null, null, null));
    }

    @Test
    void againstTheSharedBaselineEveryChangeIsNamedInTheEpicsOrder() {
        ContractsReport report = ContractFixtures.payload("diff/report.json");
        ContractsReport baseline = ContractFixtures.payload("diff/baseline.json");

        List<Highlight> highlights = kind.highlight(report, Optional.of(baseline), withBaseline());

        assertThat(highlights).extracting(Highlight::severity, Highlight::text).containsExactly(
                tuple("warn", "new pact: qits-fx-service → qits-events-service"),
                tuple("warn", "now verifies pact: qits-workspaces-service → qits-fx-service"),
                tuple("warn", "provider state removed: a closed ledger"),
                tuple("info", "new provider state: one ledger"),
                tuple("info", "+1 interaction qits-fx-service → qits-projects-service"),
                tuple("info", "+1 interaction qits-landing-app → qits-fx-service"),
                tuple("info", "−1 interaction qits-fx-service → qits-projects-service"),
                tuple("info", "−1 interaction qits-landing-app → qits-fx-service"),
                tuple("info", "1 interaction changed qits-fx-service → qits-projects-service"),
                tuple("info", "pact removed: qits-fx-service → qits-githost-service"));
    }

    @Test
    void nothingChangedIsGoodAndNamesTheBaseline() {
        ContractsReport report = ContractFixtures.payload("diff/report.json");

        assertThat(kind.highlight(report, Optional.of(report), withBaseline()))
                .containsExactly(new Highlight("good", "contracts unchanged since 2026.1003.52637", null, null, null));
    }

    @Test
    void aProviderSideTheBaselineDidNotReportIsNotNew() {
        ContractsReport report = ContractFixtures.payload("diff/provider-side-absent/report.json");
        ContractsReport baseline = ContractFixtures.payload("diff/provider-side-absent/baseline.json");

        assertThat(kind.highlight(report, Optional.of(baseline), withBaseline())).extracting(Highlight::severity)
                .containsExactly("good");
    }

    @Test
    void moreThanTenLinesEndInTheCountOfTheRest() {
        ContractsReport baseline = report(List.of(), List.of());
        ContractsReport report = report(IntStream.range(0, 12).mapToObj(i -> pact("qits-app-" + i, "qits-x-service"))
                .toList(), List.of("s1", "s2", "s3"));

        List<Highlight> highlights = kind.highlight(report, Optional.of(baseline), withBaseline());

        assertThat(highlights).hasSize(10);
        assertThat(highlights.subList(0, 9)).allSatisfy(h -> assertThat(h.text()).startsWith("new pact: "));
        assertThat(highlights.get(9)).isEqualTo(new Highlight("info", "+4 more contract changes", null, null, null));
    }

    @Test
    void moreThanTwoStatesAreCounted() {
        ContractsReport baseline = report(List.of(), List.of("old 1", "old 2", "old 3", "kept"));
        ContractsReport report = report(List.of(), List.of("kept", "new 1", "new 2", "new 3"));

        assertThat(kind.highlight(report, Optional.of(baseline), withBaseline()))
                .extracting(Highlight::severity, Highlight::text)
                .containsExactly(tuple("warn", "3 provider states removed"), tuple("info", "3 new provider states"));
    }

    @Test
    void longNamesAreCutWithAnEllipsisSoBothEndsStayReadable() {
        String consumer = "qits-a-consumer-with-a-very-long-repository-name-indeed-service";
        String provider = "qits-a-provider-with-an-even-longer-repository-name-than-that-service";
        String state = "a provider state whose name goes on and on well past what a highlight line can hold";
        ContractsReport baseline = report(List.of(), List.of(state + " (old)"));
        ContractsReport report = report(List.of(pact(consumer, provider)), List.of(state));

        List<Highlight> highlights = kind.highlight(report, Optional.of(baseline), withBaseline());

        assertThat(highlights).allSatisfy(h -> assertThat(h.text().length()).isLessThanOrEqualTo(80));
        String pair = highlights.get(0).text();
        assertThat(pair).hasSize(80).startsWith("new pact: qits-a-consumer").contains(" → qits-a-provider")
                .endsWith("…");
        assertThat(pair.indexOf('…')).isLessThan(pair.indexOf('→'));
        assertThat(highlights.get(1).text()).hasSize(80).startsWith("provider state removed: a provider state")
                .endsWith("…");
        assertThat(ContractsReportKind.fit("x %s", "short")).isEqualTo("x short");
    }

    private Optional<ContractsReport> collect() throws IOException {
        return kind.collect(ContractFixtures.step(root), kinds.parsersOf("contracts"));
    }

    private StepContext withBaseline() {
        return ContractFixtures.step(root, Optional.of(ContractFixtures.BASELINE));
    }

    private void landingPacts() throws IOException {
        for (String provider : List.of("events", "githost", "maintenance", "projects", "workspaces")) {
            String name = "qits-landing-app_qits-" + provider + "-service.json";
            ContractFixtures.copy("contracts/landing/pacts/" + name, root, "pacts/" + name);
        }
    }

    private static PactEntry pact(String consumer, String provider) {
        return new PactEntry("CONSUMER", consumer, provider, "4.0", "pacts/" + consumer + "_" + provider + ".json",
                List.of(new InteractionEntry("op", List.of(), "Synchronous/HTTP", "GET", "/", 200, "sha256:0", null)));
    }

    private static ContractsReport report(List<PactEntry> pacts, List<String> states) {
        return new ContractsReport(List.of(), new ContractSides(true, false, true),
                new ProviderStatesInventory("qits-x", states.stream().map(s -> new ProviderStateEntry(s, List.of()))
                        .toList()), pacts, List.of(), false);
    }
}
