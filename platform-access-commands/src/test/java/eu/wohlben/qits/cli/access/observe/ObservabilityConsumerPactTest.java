package eu.wohlben.qits.cli.access.observe;

import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliPactTest;

/** The consumer half of the qits-observability contract: {@link ObservabilityContract}'s rows. See {@link CliPactTest}. */
class ObservabilityConsumerPactTest extends CliPactTest {

    @Override
    protected CliPact contract() {
        return ObservabilityContract.CONTRACT;
    }
}
