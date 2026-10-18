package eu.wohlben.qits.cli.access.maintenance;

import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliPactTest;

/** The consumer half of the qits-maintenance contract: {@link MaintenanceContract}'s rows. See {@link CliPactTest}. */
class MaintenanceConsumerPactTest extends CliPactTest {

    @Override
    protected CliPact contract() {
        return MaintenanceContract.CONTRACT;
    }
}
