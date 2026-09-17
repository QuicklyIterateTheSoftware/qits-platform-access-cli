package eu.wohlben.qits.cli.access.git;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * One Git sign-in in {@code git.json}, for one git origin. The access token may be absent: Git's
 * {@code erase} drops it and keeps the refresh token.
 * <p>
 * {@link #toString()} leaves both tokens out.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitCredential(
        String idpUrl,
        String clientId,
        String audience,
        String gitOrigin,
        String accessToken,
        Instant accessExpiresAt,
        String refreshToken,
        Instant refreshExpiresAt) {

    /** The expiry times count from when the answer arrived, rounded down to the second. */
    static GitCredential from(String idpUrl, String audience, String gitOrigin, TokenClient.TokenResponse token,
                              Instant receivedAt) {
        Instant now = receivedAt.truncatedTo(ChronoUnit.SECONDS);
        return new GitCredential(idpUrl, GitLoginFlow.CLIENT_ID, audience, gitOrigin, token.accessToken(),
                now.plusSeconds(token.expiresIn()), token.refreshToken(), now.plusSeconds(token.refreshExpiresIn()));
    }

    /** True while the access token has more than {@code margin} left. */
    boolean accessUsable(Instant now, Duration margin) {
        return accessToken != null && !accessToken.isBlank() && accessExpiresAt != null
                && now.isBefore(accessExpiresAt.minus(margin));
    }

    GitCredential withoutAccessToken() {
        return new GitCredential(idpUrl, clientId, audience, gitOrigin, null, null, refreshToken, refreshExpiresAt);
    }

    @Override
    public String toString() {
        return "GitCredential[gitOrigin=" + gitOrigin + ", idpUrl=" + idpUrl + ", audience=" + audience
                + ", accessExpiresAt=" + accessExpiresAt + ", refreshExpiresAt=" + refreshExpiresAt + "]";
    }
}
