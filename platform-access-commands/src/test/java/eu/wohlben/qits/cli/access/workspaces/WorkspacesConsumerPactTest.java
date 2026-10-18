package eu.wohlben.qits.cli.access.workspaces;

import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliPactTest;

/**
 * The consumer half of the qits-workspaces-service contract: {@link WorkspacesContract}'s rows against pact mock
 * servers, and {@code pacts/qits-platform-access-cli_qits-workspaces-service.json}. See {@link CliPactTest}.
 */
class WorkspacesConsumerPactTest extends CliPactTest {

    @Override
    protected CliPact contract() {
        return WorkspacesContract.CONTRACT;
    }
}
