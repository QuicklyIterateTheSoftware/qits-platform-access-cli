package eu.wohlben.qits.cli.access.report.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.report.ReportJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The identity rules, against the shared fixtures that qits-ui-components copies verbatim: its
 * {@code contractChanges(report, baseline)} must deep-equal {@code changes.json}, as this does.
 */
class ContractChangesTest {

    @ParameterizedTest
    @ValueSource(strings = {"diff/", "diff/provider-side-absent/"})
    void theSharedFixturesChangesAreExactlyChangesJson(String dir) throws IOException {
        ContractsReport report = ContractFixtures.payload(dir + "report.json");
        ContractsReport baseline = ContractFixtures.payload(dir + "baseline.json");
        JsonNode expected = ReportJson.MAPPER.readTree(ContractFixtures.resource("contracts/" + dir + "changes.json")
                .toFile());

        ContractChanges changes = ContractChanges.between(report, baseline);

        // The same bytes, so the key order is pinned as well as the content.
        assertThat(ReportJson.MAPPER.writeValueAsString(changes))
                .isEqualTo(ReportJson.MAPPER.writeValueAsString(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"diff/report.json", "diff/baseline.json", "diff/provider-side-absent/report.json",
            "diff/provider-side-absent/baseline.json"})
    void theSharedPayloadsAreVersionOnePayloadsAsTheCliWritesThem(String name) throws IOException {
        JsonNode file = ReportJson.MAPPER.readTree(ContractFixtures.resource("contracts/" + name).toFile());

        assertThat(ReportJson.MAPPER.writeValueAsString(ContractFixtures.payload(name)))
                .isEqualTo(ReportJson.MAPPER.writeValueAsString(file));
    }

    @Test
    void theSharedCaseCoversEveryKindOfChange() {
        ContractChanges changes = ContractChanges.between(ContractFixtures.payload("diff/report.json"),
                ContractFixtures.payload("diff/baseline.json"));

        assertThat(changes.newPairs()).extracting(ContractChanges.Pair::role).containsExactly("CONSUMER", "PROVIDER");
        assertThat(changes.removedPairs()).isNotEmpty();
        assertThat(changes.newInteractions()).isNotEmpty();
        assertThat(changes.removedInteractions()).isNotEmpty();
        assertThat(changes.changedInteractions()).isNotEmpty();
        assertThat(changes.newStates()).isNotEmpty();
        assertThat(changes.removedStates()).isNotEmpty();
        assertThat(changes.none()).isFalse();
    }

    @Test
    void aReportComparedWithItselfHasNoChanges() {
        ContractsReport report = ContractFixtures.payload("diff/report.json");

        assertThat(ContractChanges.between(report, report).none()).isTrue();
    }

    @Test
    void aSideNotReportedInTheReportRemovesNothing() {
        ContractsReport baseline = ContractFixtures.payload("diff/baseline.json");
        ContractsReport report = ContractFixtures.payload("diff/report.json");
        ContractsReport consumerOnly = new ContractsReport(report.sources(), new ContractSides(true, false, false),
                null, report.pacts().stream().filter(p -> p.role().equals("CONSUMER")).toList(), List.of(), false);

        ContractChanges changes = ContractChanges.between(consumerOnly, baseline);

        assertThat(changes.removedPairs()).allSatisfy(p -> assertThat(p.role()).isEqualTo("CONSUMER"));
        assertThat(changes.removedInteractions()).allSatisfy(i -> assertThat(i.role()).isEqualTo("CONSUMER"));
        assertThat(changes.newStates()).isEmpty();
        assertThat(changes.removedStates()).isEmpty();
    }

    @Test
    void theContentHashIgnoresKeyOrderAndWhatALibraryWritesBesideTheExchange() throws IOException {
        JsonNode plain = ReportJson.MAPPER.readTree("""
                {"description": "d", "request": {"method": "GET", "path": "/a", "query": {"x": ["1"]}},
                 "response": {"status": 200, "body": {"b": 1, "a": [2, {"z": 1, "y": 2}]}}}""");
        JsonNode reordered = ReportJson.MAPPER.readTree("""
                {"key": "30b0898a", "pending": false, "_id": "abc",
                 "comments": {"references": {"qits-call": {"app": "x"}}},
                 "response": {"body": {"a": [2, {"y": 2, "z": 1}], "b": 1}, "status": 200},
                 "request": {"query": {"x": ["1"]}, "path": "/a", "method": "GET"}, "description": "d"}""");
        JsonNode otherStatus = ReportJson.MAPPER.readTree("""
                {"description": "d", "request": {"method": "GET", "path": "/a", "query": {"x": ["1"]}},
                 "response": {"status": 201, "body": {"b": 1, "a": [2, {"z": 1, "y": 2}]}}}""");
        JsonNode otherOrderInAnArray = ReportJson.MAPPER.readTree("""
                {"description": "d", "request": {"method": "GET", "path": "/a", "query": {"x": ["1"]}},
                 "response": {"status": 200, "body": {"b": 1, "a": [{"z": 1, "y": 2}, 2]}}}""");

        String hash = ContentHash.of(plain, false);
        assertThat(hash).matches("sha256:[0-9a-f]{64}");
        assertThat(ContentHash.of(reordered, false)).isEqualTo(hash);
        assertThat(ContentHash.of(otherStatus, false)).isNotEqualTo(hash);
        assertThat(ContentHash.of(otherOrderInAnArray, false)).isNotEqualTo(hash);
        assertThat(ContentHash.canonical(plain.get("response")))
                .isEqualTo("{\"body\":{\"a\":[2,{\"y\":2,\"z\":1}],\"b\":1},\"status\":200}");
    }

    @Test
    void aMessagesHashIsItsContentsAndMetadataWithoutThePactLibrarysName() throws IOException {
        JsonNode message = ReportJson.MAPPER.readTree("""
                {"description": "m", "contents": {"id": 1}, "metadata": {"contentType": "application/json"}}""");
        JsonNode bumped = ReportJson.MAPPER.readTree("""
                {"description": "m", "key": "k", "contents": {"id": 1},
                 "metadata": {"pactJs": {"version": "13"}, "contentType": "application/json"}}""");
        JsonNode changed = ReportJson.MAPPER.readTree("""
                {"description": "m", "contents": {"id": 2}, "metadata": {"contentType": "application/json"}}""");

        assertThat(ContentHash.of(bumped, true)).isEqualTo(ContentHash.of(message, true));
        assertThat(ContentHash.of(changed, true)).isNotEqualTo(ContentHash.of(message, true));
    }
}
