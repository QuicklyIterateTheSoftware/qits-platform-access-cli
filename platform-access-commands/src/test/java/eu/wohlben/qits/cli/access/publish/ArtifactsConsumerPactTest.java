package eu.wohlben.qits.cli.access.publish;

import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliPactTest;

/**
 * The consumer half of the qits-artifacts contract: {@link ArtifactsContract}'s rows against pact
 * mock servers, and {@code pacts/qits-platform-access-cli_qits-artifacts-service.json}. See {@link
 * CliPactTest}.
 */
class ArtifactsConsumerPactTest extends CliPactTest {

    @Override
    protected CliPact contract() {
        return ArtifactsContract.CONTRACT;
    }
}
