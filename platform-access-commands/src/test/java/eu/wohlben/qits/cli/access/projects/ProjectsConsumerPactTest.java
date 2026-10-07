package eu.wohlben.qits.cli.access.projects;

import au.com.dius.pact.consumer.ConsumerPactRunnerKt;
import au.com.dius.pact.consumer.PactVerificationResult;
import au.com.dius.pact.consumer.model.MockProviderConfig;
import au.com.dius.pact.core.model.PactSpecVersion;
import eu.wohlben.qits.cli.access.contracts.GoldenMasters;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <b>The consumer half of the qits-projects contract</b>: the real {@link ProjectsApi}, over the
 * real {@link PlatformClient}, pointed at a pact-jvm mock server that answers exactly what {@link
 * ProjectsContract}'s interaction for that row promises — and the row's own assertions on what the
 * command reads from it. A row whose request the client does not make (another path, another body,
 * another media type), or whose answer it cannot read, fails here; the committed pact file is
 * checked by {@code ProjectsPactFileTest}.
 * <p>
 * <b>pact-jvm's programmatic runner, one mock server per row</b>, as qits-workspaces-service's test
 * does: several rows send the identical request ({@code getWork} for details, update, transition
 * and status) and differ only in their trigger, which one mock server serving every interaction of
 * the pact could not tell apart. Plain JUnit: the commands are a plain jar, nothing to boot.
 */
class ProjectsConsumerPactTest {

    @Test
    void everyRowOfTheContractIsWhatTheCommandsAskAndUnderstand() {
        assertThat(ProjectsContract.CASES).isNotEmpty();
        List<String> failures = new ArrayList<>();
        for (ProjectsContract.Case row : ProjectsContract.CASES) {
            PactVerificationResult result = ConsumerPactRunnerKt.runConsumerTest(
                    ProjectsContract.pact(List.of(row)),
                    MockProviderConfig.createDefault(PactSpecVersion.V4),
                    (mockServer, context) -> {
                        // The bearer is whatever this home would send; the pact does not hold it.
                        ProjectsApi api = new ProjectsApi(new PlatformClient(() -> "pact-consumer-test"),
                                mockServer.getUrl());
                        row.call().run(api, GoldenMasters.params(row.state()),
                                GoldenMasters.operation(row.state(), row.operationId()));
                        return null;
                    });
            if (!(result instanceof PactVerificationResult.Ok)) {
                failures.add(row.description() + " [" + row.state() + "]: " + describe(result));
            }
        }
        if (!failures.isEmpty()) {
            fail(failures.size() + " contract row(s) failed:\n  " + String.join("\n  ", failures));
        }
    }

    private static String describe(PactVerificationResult result) {
        if (result instanceof PactVerificationResult.Error error) {
            return "error: " + error.getError();
        }
        return result.getDescription() + " — " + result;
    }
}
