package eu.wohlben.qits.cli.access.events;

import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliPactTest;

/** The consumer half of the qits-events contract: {@link EventsContract}'s rows. See {@link CliPactTest}. */
class EventsConsumerPactTest extends CliPactTest {

    @Override
    protected CliPact contract() {
        return EventsContract.CONTRACT;
    }
}
