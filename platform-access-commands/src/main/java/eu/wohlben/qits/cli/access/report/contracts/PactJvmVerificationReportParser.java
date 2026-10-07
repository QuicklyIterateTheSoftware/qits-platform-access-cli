package eu.wohlben.qits.cli.access.report.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.report.ReportJson;
import eu.wohlben.qits.cli.access.report.ReportParser;
import eu.wohlben.qits.cli.access.report.StepContext;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The provider side: the pacts a provider verified, from Pact-JVM's JSON verification report. The
 * pacts come off the test classpath (consumer pact jars pinned in the pom), never from the tree, so
 * only the verifier knows which it ran. The java archetypes' QA maven step switches the report on
 * with {@code -Dpact.verification.reports=json -Dpact.verification.reportDir=.qits-reports/pact-verification}.
 *
 * <p>The format, as Pact-JVM 4.6.21's {@code JsonReporter} writes it (measured on qits-projects-service,
 * 2026-10-07): one file per provider, {@code <provider>.json}, holding every consumer. Its
 * {@code execution[]} gets one entry per JUnit 5 invocation, that is per interaction, each with the
 * {@code consumer} ({@code name}, {@code source.url}) and {@code interactions[]} of one element: the
 * whole {@code interaction} as Pact-JVM holds it (v4 form: {@code type}, {@code description},
 * {@code providerStates}, {@code request}, {@code response}) and its {@code verification.result},
 * {@code OK} or {@code failed}. A report already in the directory for the same provider is read and
 * appended to, so a rerun in the same tree lists an interaction twice; the last one counts.
 */
public final class PactJvmVerificationReportParser implements ReportParser<ContractFile> {

    static final String DIRECTORY = ".qits-reports/pact-verification";

    @Override
    public String kind() {
        return ContractsReportKind.ID;
    }

    @Override
    public String language() {
        return "java";
    }

    @Override
    public String tool() {
        return "pact-jvm";
    }

    @Override
    public List<Path> discover(Path root) {
        return PactFileParser.jsonFiles(root.resolve(DIRECTORY));
    }

    @Override
    public ContractFile parse(Path file, StepContext step) throws IOException {
        JsonNode report = ReportJson.MAPPER.readTree(file.toFile());
        if (report == null || !report.isObject()) {
            throw new IOException("not a Pact-JVM verification report: not a JSON object");
        }
        String provider = Pacts.name(report, "provider");
        JsonNode executions = report.path("execution");
        if (provider == null || !executions.isArray()) {
            throw new IOException("not a Pact-JVM verification report: no provider or no execution");
        }
        String source = step.relative(file);
        // consumer -> interaction identity -> the last one the report lists
        Map<String, Map<String, InteractionEntry>> byConsumer = new LinkedHashMap<>();
        for (JsonNode execution : executions) {
            String consumer = Pacts.name(execution, "consumer");
            if (consumer == null) {
                continue;
            }
            Map<String, InteractionEntry> interactions = byConsumer.computeIfAbsent(consumer, c -> new LinkedHashMap<>());
            for (JsonNode verified : execution.path("interactions")) {
                JsonNode interaction = verified.path("interaction");
                if (!interaction.isObject()) {
                    continue;
                }
                InteractionEntry entry = Pacts.interaction(interaction, result(verified.path("verification")));
                interactions.put(ContractChanges.identity(entry), entry);
            }
        }
        List<PactEntry> pacts = new ArrayList<>();
        byConsumer.forEach((consumer, interactions) -> pacts.add(new PactEntry(PactEntry.PROVIDER, consumer, provider,
                null, source, List.copyOf(interactions.values()))));
        return new ContractFile(new ContractSource(language(), tool(), source, "pact-jvm-json-report"),
                ContractFile.PROVIDER, pacts, null);
    }

    /** {@code OK}, {@code FAILED} for any other result the verifier wrote, null when it wrote none. */
    private static String result(JsonNode verification) {
        JsonNode result = verification.path("result");
        if (!result.isTextual() || result.asText().isBlank()) {
            return null;
        }
        return result.asText().toUpperCase(Locale.ROOT).equals(InteractionEntry.OK) ? InteractionEntry.OK
                : InteractionEntry.FAILED;
    }
}
