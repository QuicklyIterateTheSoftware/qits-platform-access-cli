package eu.wohlben.qits.cli.access.projects;

import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliPactTest;

/**
 * The consumer half of the qits-projects contract: {@link ProjectsContract}'s rows against pact mock
 * servers, and {@code pacts/qits-platform-access-cli_qits-projects-service.json}. See {@link
 * CliPactTest}.
 */
class ProjectsConsumerPactTest extends CliPactTest {

    @Override
    protected CliPact contract() {
        return ProjectsContract.CONTRACT;
    }
}
