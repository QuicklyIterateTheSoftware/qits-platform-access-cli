package eu.wohlben.qits.cli.access.git;

import eu.wohlben.qits.cli.access.idp.IdpException;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.session.ExclusiveLock;
import eu.wohlben.qits.cli.access.session.Times;

import java.io.IOException;
import java.io.PrintStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * What {@code qits git-credential} does with {@code git.json}: hand Git an access token, refreshing
 * it when needed, and forget one Git says failed.
 */
public final class GitAccess {

    /** A push can take a while; a token with less than this left may expire on the way. */
    static final Duration MARGIN = Duration.ofSeconds(60);
    static final String ENDED = "Git sign-in ended — run `qits git-login`.";

    private final GitCredentialFile store;
    private final Clock clock;

    public GitAccess(GitCredentialFile store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * An access token for {@code gitOrigin}, or empty when there is none to give. Empty for an
     * origin with no sign-in says nothing, so Git asks its other helpers; an ended sign-in or a
     * refresh that failed says why on {@code err}.
     */
    public Optional<String> accessToken(String gitOrigin, PrintStream err) throws IOException, InterruptedException {
        Optional<GitCredential> saved = store.find(gitOrigin);
        if (saved.isEmpty()) {
            return Optional.empty();
        }
        if (saved.get().accessUsable(clock.instant(), MARGIN)) {
            return Optional.of(saved.get().accessToken());
        }
        try (ExclusiveLock ignored = store.lock()) {
            // Read again: another Git process may have refreshed while this one waited.
            Optional<GitCredential> again = store.find(gitOrigin);
            if (again.isEmpty()) {
                return Optional.empty();
            }
            GitCredential credential = again.get();
            Instant now = clock.instant();
            if (credential.accessUsable(now, MARGIN)) {
                return Optional.of(credential.accessToken());
            }
            if (!now.isBefore(credential.refreshExpiresAt())) {
                err.println(ENDED + " (it ended " + Times.local(credential.refreshExpiresAt()) + ")");
                return Optional.empty();
            }
            String clientId = credential.clientId() == null ? GitLoginFlow.CLIENT_ID : credential.clientId();
            TokenClient.TokenResponse token;
            try {
                token = new TokenClient(credential.idpUrl(), clientId).refresh(credential.refreshToken(), credential.audience());
            } catch (IdpException.Unreachable e) {
                err.println("Cannot refresh the Git sign-in: " + e.getMessage());
                return Optional.empty();
            } catch (IdpException invalidGrantOrRefused) {
                err.println(ENDED + " (" + invalidGrantOrRefused.getMessage() + ")");
                return Optional.empty();
            }
            // Write first: the idp has spent the old refresh token.
            GitCredential next = GitCredential.from(credential.idpUrl(), credential.audience(), gitOrigin, token,
                    clock.instant());
            store.put(next);
            return Optional.of(next.accessToken());
        }
    }

    /**
     * Git erases after any refused request. Only the cached access token goes: a passing 401 must
     * not cost a new browser sign-in. A token other than the one stored (another process refreshed
     * meanwhile) is not the one Git means, and the stored one stays.
     */
    public void erase(String gitOrigin, String rejectedPassword) throws IOException, InterruptedException {
        try (ExclusiveLock ignored = store.lock()) {
            Optional<GitCredential> saved = store.find(gitOrigin);
            if (saved.isEmpty() || saved.get().accessToken() == null) {
                return;
            }
            if (rejectedPassword != null && !rejectedPassword.equals(saved.get().accessToken())) {
                return;
            }
            store.put(saved.get().withoutAccessToken());
        }
    }
}
