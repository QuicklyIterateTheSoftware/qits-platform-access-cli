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
 * @param via           the library that made the calls on the consumer's behalf, from the pact's
 *                      {@code qits-via} comment; null when no interaction carries one
 */
@RegisterForReflection
public record PactEntry(String role, String consumer, String provider, String specification, String source,
                        List<InteractionEntry> interactions, Via via) {

    public static final String CONSUMER = "CONSUMER";
    public static final String PROVIDER = "PROVIDER";

    public PactEntry {
        interactions = interactions == null ? List.of() : List.copyOf(interactions);
    }

    PactEntry withInteractions(List<InteractionEntry> kept) {
        return new PactEntry(role, consumer, provider, specification, source, kept, via);
    }

    /**
     * The library a pact's calls went through on the service's behalf, from {@code comments.references
     * .qits-via}, next to the {@code qits-call} and {@code qits-trigger} references the same comment
     * carries.
     *
     * @param repository the library's repository name
     * @param artifact   the library's published coordinates ({@code groupId:artifactId} for Maven);
     *                   null when the reference omits it
     * @param version    the library's version
     */
    @RegisterForReflection
    public record Via(String repository, String artifact, String version) {
    }
}
