package eu.wohlben.qits.cli.access.git;

import eu.wohlben.qits.cli.access.idp.IdpException;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.login.Pkce;
import eu.wohlben.qits.cli.access.session.ExclusiveLock;
import eu.wohlben.qits.cli.access.session.Times;

import java.io.IOException;
import java.io.PrintStream;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The Git sign-in: PKCE and a {@code state}, the browser, the loopback answer, the exchange, and
 * {@code git.json}.
 * <p>
 * The client is {@code qits-git-workstation}, not {@code qits-cli}. Its token carries {@code
 * groups=[qits:git:external]} and a ref pattern, so the git host lets it push {@code
 * refs/heads/external/*} and nothing else. The {@code audience} names the git host of one
 * environment.
 * <p>
 * <b>Nothing here prints a token, the code or the verifier.</b>
 */
public final class GitLoginFlow {

    public static final String CLIENT_ID = "qits-git-workstation";

    private final TokenClient idp;
    private final GitCredentialFile store;
    private final String gitOrigin;
    private final String audience;
    private final PrintStream out;
    private final PrintStream err;
    private final Clock clock;
    private final Consumer<String> browser;
    private final Duration timeout;

    /** @param browser opens the sign-in address, or null to only print it */
    public GitLoginFlow(TokenClient idp, GitCredentialFile store, String gitOrigin, String audience, PrintStream out,
                        PrintStream err, Clock clock, Consumer<String> browser, Duration timeout) {
        this.idp = idp;
        this.store = store;
        this.gitOrigin = gitOrigin;
        this.audience = audience;
        this.out = out;
        this.err = err;
        this.clock = clock;
        this.browser = browser;
        this.timeout = timeout;
    }

    /** 0 when the sign-in is saved, 1 when it is not. */
    public int run() throws IOException, InterruptedException {
        Pkce pkce = Pkce.create();
        String state = Pkce.state();
        try (LoopbackCallback callback = LoopbackCallback.open()) {
            Map<String, String> extra = new LinkedHashMap<>();
            extra.put("audience", audience);
            extra.put("state", state);
            String address = idp.authorizeUrl(pkce.challenge(), callback.redirectUri(), extra);
            out.println("Open this address in your browser and sign in:");
            out.println();
            out.println("  " + address);
            out.println();
            out.flush();
            if (browser != null) {
                browser.accept(address);
            }
            out.println("Waiting up to " + timeout.toSeconds() + " s for the browser to come back to "
                    + callback.redirectUri() + ".");
            out.flush();

            Optional<LoopbackCallback.Callback> answer = callback.await(timeout);
            if (answer.isEmpty()) {
                err.println("The browser did not come back within " + timeout.toSeconds()
                        + " s. Nothing was saved. Run `qits git-login` again.");
                return 1;
            }
            LoopbackCallback.Callback result = answer.get();
            if (result.error() != null && !result.error().isBlank()) {
                err.println("The idp did not sign you in (" + result.error()
                        + (blank(result.errorDescription()) ? "" : ": " + result.errorDescription())
                        + "). Nothing was saved.");
                return 1;
            }
            if (!state.equals(result.state())) {
                err.println("The answer that came back is not for this sign-in: its state differs. Nothing was saved.");
                return 1;
            }
            if (blank(result.code())) {
                err.println("The answer that came back holds no code. Nothing was saved.");
                return 1;
            }
            TokenClient.TokenResponse token;
            try {
                token = idp.exchange(result.code(), pkce.verifier(), callback.redirectUri());
            } catch (IdpException e) {
                err.println(e.getMessage() + ". Nothing was saved. Run `qits git-login` again.");
                return 1;
            }
            GitCredential credential = GitCredential.from(idp.idpUrl(), audience, gitOrigin, token, clock.instant());
            try (ExclusiveLock ignored = store.lock()) {
                store.put(credential);
            }
            out.println("Signed in for Git pushes to " + gitOrigin + ": branches under refs/heads/external/ only.");
            out.println("Saved to " + store.path() + ".");
            out.println("The sign-in lasts until " + Times.local(credential.refreshExpiresAt())
                    + ". `qits git-credential` refreshes the access token when Git needs one.");
            return 0;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
