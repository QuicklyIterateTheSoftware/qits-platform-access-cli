package eu.wohlben.qits.cli.access.report.contracts;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * The {@code contracts} payload, version 1: which contracts the step's tree holds, as consumer (the
 * committed {@code pacts/}), as provider (the pacts Pact-JVM verified), and the provider states the
 * golden-master index declares. Interactions are kept without bodies. Every record of this package is
 * written and read back by Jackson, hence the registrations.
 *
 * @param providerStates null when no golden-master index was found ({@code sides.providerStates} false)
 * @param truncated      more than {@value ContractsReportKind#MAX_INTERACTIONS} interactions in all,
 *                       and only the first are listed
 */
@RegisterForReflection
public record ContractsReport(List<ContractSource> sources, ContractSides sides,
                              ProviderStatesInventory providerStates, List<PactEntry> pacts,
                              List<SkippedFile> skipped, boolean truncated) {

    public ContractsReport {
        sources = sources == null ? List.of() : List.copyOf(sources);
        sides = sides == null ? new ContractSides(false, false, false) : sides;
        pacts = pacts == null ? List.of() : List.copyOf(pacts);
        skipped = skipped == null ? List.of() : List.copyOf(skipped);
    }

    /** Every interaction of every pact. */
    public int interactionCount() {
        return pacts.stream().mapToInt(p -> p.interactions().size()).sum();
    }

    /** The declared provider states; zero without an index. */
    public int stateCount() {
        return providerStates == null ? 0 : providerStates.states().size();
    }
}
