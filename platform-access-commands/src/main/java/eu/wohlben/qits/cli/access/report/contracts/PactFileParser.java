package eu.wohlben.qits.cli.access.report.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.report.ReportJson;
import eu.wohlben.qits.cli.access.report.ReportParser;
import eu.wohlben.qits.cli.access.report.StepContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The consumer side: the pacts a repository commits under {@code pacts/} at its root, flat, the one
 * directory every pact sits in ({@code CiContracts}). The repository's own tests prove each equals what
 * its code generates, and the release publishes exactly these. Never {@code target/} or
 * {@code node_modules/}: what a build leaves there is not what the repository promises.
 */
public final class PactFileParser implements ReportParser<ContractFile> {

    static final String DIRECTORY = "pacts";

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
        return "pact";
    }

    @Override
    public List<Path> discover(Path root) {
        return jsonFiles(root.resolve(DIRECTORY));
    }

    /** The {@code *.json} regular files directly in {@code directory}, in name order. */
    static List<Path> jsonFiles(Path directory) {
        List<Path> found = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return found;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : files) {
                if (Files.isRegularFile(file)) {
                    found.add(file);
                }
            }
        } catch (IOException | UncheckedIOException unreadable) {
            // A directory that cannot be listed holds nothing this parser can read.
        }
        found.sort(null);
        return found;
    }

    @Override
    public ContractFile parse(Path file, StepContext step) throws IOException {
        JsonNode pact = ReportJson.MAPPER.readTree(file.toFile());
        if (pact == null || !pact.isObject()) {
            throw new IOException("not a pact: not a JSON object");
        }
        String consumer = Pacts.name(pact, "consumer");
        if (consumer == null) {
            throw new IOException("not a pact: no consumer");
        }
        String provider = Pacts.name(pact, "provider");
        if (provider == null) {
            throw new IOException("not a pact: no provider");
        }
        String specification = Pacts.specification(pact);
        String source = step.relative(file);
        PactEntry entry = new PactEntry(PactEntry.CONSUMER, consumer, provider, specification, source,
                Pacts.interactions(pact, null));
        return new ContractFile(new ContractSource(language(), tool(), source,
                "pact-v" + Pacts.major(pact, specification)), ContractFile.CONSUMER, List.of(entry), null);
    }
}
