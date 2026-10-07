package eu.wohlben.qits.cli.access.report.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.report.ReportJson;
import eu.wohlben.qits.cli.access.report.ReportParser;
import eu.wohlben.qits.cli.access.report.StepContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The provider states a provider declares: {@code golden-masters/index.json}, which the java services'
 * {@code GoldenMasterRecordingTest} writes and compares against {@code ProviderStates}. The one
 * machine-readable list of them; reading {@code @State} out of Java source would be a scan of string
 * constants. Only {@code formatVersion: 1} is read; another is skipped with a warning.
 */
public final class GoldenMasterIndexParser implements ReportParser<ContractFile> {

    static final String FILE = "golden-masters/index.json";
    static final int FORMAT_VERSION = 1;

    @Override
    public String kind() {
        return ContractsReportKind.ID;
    }

    @Override
    public String language() {
        return "json";
    }

    @Override
    public String tool() {
        return "qits-golden-masters";
    }

    @Override
    public List<Path> discover(Path root) {
        Path index = root.resolve(FILE);
        return Files.isRegularFile(index) ? List.of(index) : List.of();
    }

    @Override
    public ContractFile parse(Path file, StepContext step) throws IOException {
        JsonNode index = ReportJson.MAPPER.readTree(file.toFile());
        if (index == null || !index.isObject()) {
            throw new IOException("not a golden-master index: not a JSON object");
        }
        JsonNode version = index.path("formatVersion");
        if (!version.canConvertToInt() || version.asInt() != FORMAT_VERSION) {
            throw new IOException("golden-master index formatVersion " + (version.isMissingNode() ? "missing"
                    : version.asText()) + ", only " + FORMAT_VERSION + " is read");
        }
        JsonNode provider = index.path("provider");
        if (!provider.isTextual() || provider.asText().isBlank()) {
            throw new IOException("not a golden-master index: no provider");
        }
        List<ProviderStateEntry> states = new ArrayList<>();
        for (JsonNode state : index.path("states")) {
            JsonNode name = state.path("name");
            if (!name.isTextual()) {
                continue;
            }
            List<String> operations = new ArrayList<>();
            for (JsonNode operation : state.path("operations")) {
                JsonNode id = operation.path("operationId");
                if (id.isTextual() && !operations.contains(id.asText())) {
                    operations.add(id.asText());
                }
            }
            states.add(new ProviderStateEntry(name.asText(), operations));
        }
        states.sort(Comparator.comparing(ProviderStateEntry::name));
        return new ContractFile(new ContractSource(language(), tool(), step.relative(file),
                "golden-master-index-v" + FORMAT_VERSION), ContractFile.PROVIDER_STATES, List.of(),
                new ProviderStatesInventory(provider.asText(), states));
    }
}
