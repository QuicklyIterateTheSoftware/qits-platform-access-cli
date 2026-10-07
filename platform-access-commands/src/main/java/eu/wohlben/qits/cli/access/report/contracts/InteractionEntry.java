package eu.wohlben.qits.cli.access.report.contracts;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * One interaction, without its bodies.
 *
 * @param providerStates the names of its provider states, as the pact lists them
 * @param type           {@code Synchronous/HTTP}, {@code Asynchronous/Messages} or {@code Synchronous/Messages}
 * @param method         the request's method; null for a message
 * @param path           the request's path; null for a message
 * @param status         the response's status; null for a message
 * @param contentHash    {@code sha256:} and the hex SHA-256 of what the interaction exchanges
 *                       ({@link ContentHash}); null when the source does not carry it
 * @param verified       {@link #OK} or {@link #FAILED} for a pact the verifier ran; null for a consumer's
 */
@RegisterForReflection
public record InteractionEntry(String description, List<String> providerStates, String type, String method,
                               String path, Integer status, String contentHash, String verified) {

    public static final String HTTP = "Synchronous/HTTP";
    public static final String ASYNC_MESSAGE = "Asynchronous/Messages";
    public static final String SYNC_MESSAGE = "Synchronous/Messages";

    public static final String OK = "OK";
    public static final String FAILED = "FAILED";

    public InteractionEntry {
        providerStates = providerStates == null ? List.of() : List.copyOf(providerStates);
    }
}
