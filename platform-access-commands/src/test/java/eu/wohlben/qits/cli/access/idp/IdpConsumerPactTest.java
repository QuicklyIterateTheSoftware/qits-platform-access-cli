package eu.wohlben.qits.cli.access.idp;

import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliPactTest;

/**
 * The consumer half of the qits-idp contract: {@link IdpContract}'s rows against pact mock servers,
 * and {@code pacts/qits-platform-access-cli_qits-idp-service.json}. See {@link CliPactTest}.
 */
class IdpConsumerPactTest extends CliPactTest {

    @Override
    protected CliPact contract() {
        return IdpContract.CONTRACT;
    }
}
