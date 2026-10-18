package eu.wohlben.qits.cli.access.ci;

import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliPactTest;

/** The consumer half of the qits-ci contract: {@link CiContract}'s rows. See {@link CliPactTest}. */
class CiConsumerPactTest extends CliPactTest {

    @Override
    protected CliPact contract() {
        return CiContract.CONTRACT;
    }
}
