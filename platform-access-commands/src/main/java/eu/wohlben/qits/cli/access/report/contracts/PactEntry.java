package eu.wohlben.qits.cli.access.report.contracts;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * One pact: a consumer and a provider, as the pact names them (repository names), seen from one side.
 *
 * @param role          {@link #CONSUMER} for a committed {@code pacts/} file, {@link #PROVIDER} for a
 *                      pact the verifier ran
 * @param specification the Pact specification version the pact declares ({@code 4.0}); null when it
 *                      declares none
 * @param source        the file it was read from, relative to the root
 */
@RegisterForReflection
public record PactEntry(String role, String consumer, String provider, String specification, String source,
                        List<InteractionEntry> interactions) {

    public static final String CONSUMER = "CONSUMER";
    public static final String PROVIDER = "PROVIDER";

    public PactEntry {
        interactions = interactions == null ? List.of() : List.copyOf(interactions);
    }

    PactEntry withInteractions(List<InteractionEntry> kept) {
        return new PactEntry(role, consumer, provider, specification, source, kept);
    }
}
