package eu.wohlben.qits.cli.access.report.contracts;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Which sides this step reported on. A side that is false here, or in the baseline, is never compared:
 * its pairs are neither new nor removed.
 *
 * @param consumer       a {@code pacts/*.json} was found
 * @param provider       a Pact-JVM verification report was found
 * @param providerStates a golden-master index was found
 */
@RegisterForReflection
public record ContractSides(boolean consumer, boolean provider, boolean providerStates) {
}
