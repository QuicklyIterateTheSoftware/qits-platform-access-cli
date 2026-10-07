package eu.wohlben.qits.cli.access.report.contracts;

import eu.wohlben.qits.cli.access.report.Baseline;
import eu.wohlben.qits.cli.access.report.ChangedLines;
import eu.wohlben.qits.cli.access.report.ReportJson;
import eu.wohlben.qits.cli.access.report.RepositoryRef;
import eu.wohlben.qits.cli.access.report.StepContext;
import eu.wohlben.qits.cli.access.report.TestCaseLocators;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The fixtures under {@code src/test/resources/report/contracts}: copies of real pacts (qits-landing-app's
 * five, its projects pact cut to its first three interactions; qits-workspaces-service's), the
 * Pact-JVM 4.6.21 verification report qits-projects-service's ConsumerPactVerificationTest wrote on
 * 2026-10-07 (and a copy with one interaction failed, as JsonReporter writes a status mismatch),
 * qits-githost-service's golden-master index, hand-written v3 and v2 pacts, and the shared diff
 * fixtures {@code diff/}, which qits-ui-components copies verbatim.
 */
final class ContractFixtures {

    static final Baseline BASELINE = new Baseline("2026.1003.52637", "base-run", "rr-1", "abc123");

    private ContractFixtures() {
    }

    static Path resource(String name) {
        try {
            return Path.of(ContractFixtures.class.getResource("/report/" + name).toURI());
        } catch (URISyntaxException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** {@code report/<from>} copied to {@code root/<to>}. */
    static Path copy(String from, Path root, String to) {
        Path target = root.resolve(to);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(resource(from), target);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return target;
    }

    /** A vitest report at the root, so the step counts as one that ran tests. */
    static void tested(Path root) {
        copy("tree/.qits-reports/vitest-app.xml", root, ".qits-reports/vitest-app.xml");
    }

    static StepContext step(Path root) {
        return step(root, Optional.empty());
    }

    static StepContext step(Path root, Optional<Baseline> baseline) {
        return new StepContext(root, "run-1", 1, new RepositoryRef("8f1c2d3e-0000-4000-8000-000000000001", "qits-fx"),
                "0123456789abcdef0123456789abcdef01234567", 0, baseline, ChangedLines.unavailable(),
                TestCaseLocators.registered());
    }

    static ContractsReport payload(String name) {
        try {
            return ReportJson.MAPPER.readValue(resource("contracts/" + name).toFile(), ContractsReport.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
