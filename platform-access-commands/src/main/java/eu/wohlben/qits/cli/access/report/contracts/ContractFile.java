package eu.wohlben.qits.cli.access.report.contracts;

import java.util.List;

/**
 * What one file of the {@code contracts} kind parses into: its source line, the side it reports on,
 * and the pacts or the provider states it holds. Never written as JSON; {@link ContractsReportKind}
 * merges these. The side comes from the parser, not from what the file holds: a verification report
 * that lists no pact still says the provider side was reported, and verifies nothing.
 *
 * @param side           {@link #CONSUMER}, {@link #PROVIDER} or {@link #PROVIDER_STATES}
 * @param providerStates null unless the file is a golden-master index
 */
public record ContractFile(ContractSource source, String side, List<PactEntry> pacts,
                           ProviderStatesInventory providerStates) {

    public static final String CONSUMER = "consumer";
    public static final String PROVIDER = "provider";
    public static final String PROVIDER_STATES = "providerStates";

    public ContractFile {
        pacts = pacts == null ? List.of() : List.copyOf(pacts);
    }
}
