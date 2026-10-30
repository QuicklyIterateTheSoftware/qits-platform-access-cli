package eu.wohlben.qits.cli.access.deployments;

import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliPactTest;

/** The consumer half of the qits-deployments contract: {@link DeploymentsContract}'s rows. See {@link CliPactTest}. */
class DeploymentsConsumerPactTest extends CliPactTest {

    @Override
    protected CliPact contract() {
        return DeploymentsContract.CONTRACT;
    }
}
