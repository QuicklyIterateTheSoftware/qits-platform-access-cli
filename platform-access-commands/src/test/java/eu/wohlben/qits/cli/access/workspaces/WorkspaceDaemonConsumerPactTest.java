package eu.wohlben.qits.cli.access.workspaces;

import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliPactTest;

/**
 * The consumer half of the qits-workspace-daemon contract: {@link WorkspaceDaemonContract}'s rows against pact mock
 * servers, and {@code pacts/qits-platform-access-cli_qits-workspace-daemon.json}. See {@link CliPactTest}.
 */
class WorkspaceDaemonConsumerPactTest extends CliPactTest {

    @Override
    protected CliPact contract() {
        return WorkspaceDaemonContract.CONTRACT;
    }
}
