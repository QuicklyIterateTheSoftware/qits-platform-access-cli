package eu.wohlben.qits.cli.access.login;

import eu.wohlben.qits.cli.access.idp.IdpException;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.session.ExclusiveLock;
import eu.wohlben.qits.cli.access.session.Session;
import eu.wohlben.qits.cli.access.session.SessionFile;
import eu.wohlben.qits.cli.access.session.Times;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * The sign-in: PKCE, the authorize address, the pasted code, the exchange, the session file.
 * <p>
 * The code is pasted, not caught by a listener: {@code qits-cli}'s redirect target is the idp's
 * own page, which shows the code. PKCE is what makes a pasted code worthless to anyone else.
 * <p>
 * <b>Nothing here prints a token, the code or the verifier.</b>
 */
public final class LoginFlow {

    /**
     * How long a code stays spendable: the idp's {@code qits.idp.cli.authorization-code-ttl}. The
     * code the person pastes first was made before that, so no code of this sign-in lives longer
     * than this after the first paste.
     */
    static final Duration CODE_TTL = Duration.ofMinutes(5);

    private final TokenClient idp;
    private final SessionFile store;
    private final BufferedReader in;
    private final PrintStream out;
    private final PrintStream err;
    private final Clock clock;
    private final Consumer<String> browser;

    /** @param browser opens the sign-in address, or null to only print it */
    public LoginFlow(TokenClient idp, SessionFile store, BufferedReader in, PrintStream out, PrintStream err,
                     Clock clock, Consumer<String> browser) {
        this.idp = idp;
        this.store = store;
        this.in = in;
        this.out = out;
        this.err = err;
        this.clock = clock;
        this.browser = browser;
    }

    /** 0 when the session is saved, 1 when it is not. */
    public int run() throws IOException, InterruptedException {
        Pkce pkce = Pkce.create();
        String address = idp.authorizeUrl(pkce.challenge());
        out.println("Open this address in your browser and sign in:");
        out.println();
        out.println("  " + address);
        out.println();
        if (browser != null) {
            browser.accept(address);
        }
        out.println("The page then shows a code.");

        Instant deadline = null;
        while (true) {
            String code = readCode();
            if (code == null) {
                err.println("No code given. Nothing was saved.");
                return 1;
            }
            if (deadline == null) {
                deadline = clock.instant().plus(CODE_TTL);
            }
            TokenClient.TokenResponse token;
            try {
                token = idp.exchange(code, pkce.verifier());
            } catch (IdpException.InvalidGrant e) {
                if (clock.instant().isBefore(deadline)) {
                    err.println("The idp did not accept that code: it is expired, used or mistyped. Paste it again.");
                    continue;
                }
                err.println("The idp did not accept that code, and the codes of this sign-in have expired."
                        + " Run `qits login` again.");
                return 1;
            } catch (IdpException.Unreachable e) {
                err.println(e.getMessage());
                if (clock.instant().isBefore(deadline)) {
                    err.println("Paste the code again to try again.");
                    continue;
                }
                err.println("Run `qits login` again.");
                return 1;
            } catch (IdpException refused) {
                err.println(refused.getMessage());
                return 1;
            }

            Session session = Session.from(idp.idpUrl(), token, clock.instant());
            // Under the daemon's write lock: a daemon refreshing right now must not write its
            // answer over this new session, or spend a token this login just replaced.
            try (ExclusiveLock ignored = store.lockForWrite()) {
                store.write(session);
            }
            out.println("Signed in to " + session.idpUrl() + ".");
            out.println("Session saved to " + store.path() + ".");
            out.println("Access token valid until " + Times.local(session.accessExpiresAt()) + ".");
            out.println("Session ends " + Times.local(session.refreshExpiresAt())
                    + " unless it is refreshed before then (`qits session-daemon` does that).");
            return 0;
        }
    }

    /** One trimmed line; an empty line asks again; null at the end of input. */
    private String readCode() throws IOException {
        while (true) {
            out.print("Paste the code: ");
            out.flush();
            String line = in.readLine();
            if (line == null) {
                out.println();
                return null;
            }
            String code = line.strip();
            if (!code.isEmpty()) {
                return code;
            }
        }
    }
}
