package eu.wohlben.qits.cli.access.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * The content of {@code t.json}. The times are absolute, so the daemon needs no other input.
 * <p>
 * {@link #toString()} leaves the two tokens out: a record prints every field by default, and a
 * session in a log line or a test failure must not show a token.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record Session(
        String idpUrl,
        String clientId,
        String accessToken,
        Instant accessExpiresAt,
        String refreshToken,
        Instant refreshExpiresAt) {

    /**
     * The session a token answer makes. The expiry times count from when the answer arrived,
     * rounded down to the second, so they never claim more time than the idp gave.
     */
    public static Session from(String idpUrl, TokenClient.TokenResponse token, Instant receivedAt) {
        Instant now = receivedAt.truncatedTo(ChronoUnit.SECONDS);
        return new Session(idpUrl, TokenClient.CLIENT_ID, token.accessToken(), now.plusSeconds(token.expiresIn()),
                token.refreshToken(), now.plusSeconds(token.refreshExpiresIn()));
    }

    @Override
    public String toString() {
        return "Session[idpUrl=" + idpUrl + ", clientId=" + clientId
                + ", accessExpiresAt=" + accessExpiresAt + ", refreshExpiresAt=" + refreshExpiresAt + "]";
    }
}
