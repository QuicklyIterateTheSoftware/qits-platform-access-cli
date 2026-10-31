package eu.wohlben.qits.cli.access.idp;

import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliRow;
import eu.wohlben.qits.cli.access.users.IdpUsersContract;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>What the commands ask the idp, and why</b> (ticket qits-1149): the token endpoint, for each of
 * the CLI's two sessions, and the user doors of {@code qits users}
 * ({@code users/IdpUsersContract}).
 * <ul>
 *   <li><b>A person's session</b> ({@link TokenClient}, client {@code qits-cli}): {@code qits login}
 *       exchanges the pasted code, and {@code qits session-daemon} and every platform command on a
 *       workstation ({@code AccessTokens}, here {@code qits work list}) refresh it, through the one
 *       {@code SessionRefresh}.</li>
 *   <li><b>Git's session</b> ({@link TokenClient}, client {@code qits-git-workstation}): {@code qits
 *       git-login} exchanges the loopback callback's code, {@code qits git-credential} refreshes it
 *       for {@code qits-platform}.</li>
 * </ul>
 * Every request goes as a form, compared field by field with the recording, so each row's call
 * sends the state's own values. The one exception is {@code qits login}'s exchange: its {@code
 * redirect_uri} is the idp's own page, {@code <idp>/connect/cli}, whose host is wherever the CLI
 * reaches the idp, so that field is held to {@link #CONNECT_PAGE}, and is generated from the state's
 * {@code redirectUri} when the provider verifies it. {@code GET /idp/authorize}, which
 * the provider records too, is the browser's request, never the CLI's: it has no row.
 */
public final class IdpContract {

    /** The code the idp's connect page shows, issued for {@code redirect_uri=<idp>/connect/cli}. */
    static final String CONNECT_PAGE_CODE = "an authorization code issued to the CLI for the connect page";
    /**
     * {@code qits login}'s {@code redirect_uri}, {@link TokenClient#redirectUri}: the connect page of
     * whatever idp the CLI reaches, so its host is the CLI's idp address, never one fixed value. The
     * provider records its own ({@code http://dev-qits-idp:8080/idp/connect/cli}), the mock serves
     * another; the path is what both hold.
     */
    static final String CONNECT_PAGE = "https?://[^/?#]+/idp/connect/cli";
    static final String GIT_CODE = "an authorization code issued to the git client";
    static final String SESSION = "a session to refresh";
    static final String GIT_SESSION = "a git session to refresh";
    static final String SPENT_SESSION = "no session with the given refresh token";

    @FunctionalInterface
    interface Call {
        void run(String idp, java.util.Map<String, String> params) throws Exception;
    }

    /** The idp is served under {@code /idp}, as the CLI addresses it. */
    private static CliRow.Call idp(Call call) {
        return (url, recorded) -> call.run(url + "/idp", recorded.params());
    }

    /** What every grant's answer is read for ({@code TokenClient.TokenResponse}). */
    static final List<String> SESSION_TOKENS = List.of("access_token", "expires_in", "refresh_token",
            "refresh_expires_in");

    private static void session(TokenClient.TokenResponse token) {
        assertThat(token.accessToken()).isNotBlank();
        assertThat(token.refreshToken()).isNotBlank();
        assertThat(token.expiresIn()).isPositive();
        assertThat(token.refreshExpiresIn()).isPositive();
    }

    /** {@code qits login}: the code the person pasted, for the connect page it was shown on. */
    private static final Call LOGIN = (idp, params) -> session(new TokenClient(idp).exchange(params.get("code"),
            params.get("codeVerifier")));

    private static final Call GIT_LOGIN = (idp, params) -> session(new TokenClient(idp, params.get("clientId"))
            .exchange(params.get("code"), params.get("codeVerifier"), params.get("redirectUri")));

    private static final Call REFRESH = (idp, params) -> session(new TokenClient(idp).refresh(params.get("refreshToken")));

    private static final Call GIT_REFRESH = (idp, params) -> session(new TokenClient(idp, params.get("clientId"))
            .refresh(params.get("refreshToken"), params.get("audience")));

    /** A spent refresh token is {@code invalid_grant}: the session is over, and the person signs in again. */
    private static final Call SPENT = (idp, params) ->
            assertThatThrownBy(() -> new TokenClient(idp).refresh(params.get("refreshToken")))
                    .isInstanceOf(IdpException.InvalidGrant.class).hasMessageContaining("invalid_grant");

    static final List<CliRow> ROWS = rows();

    public static final CliPact CONTRACT = CliPact.of("qits-idp-service", "qits-idp")
            .client(TokenClient.class)
            .client(IdpUsersContract.CLIENT)
            .excluded("TokenClient#authorizeUrl", "the address the browser is sent to (GET /idp/authorize); the"
                    + " browser makes that request, never the CLI")
            .excluded("TokenClient#redirectUri", "the connect page's address, sent inside the exchange's form")
            .excluded("TokenClient#idpUrl", "an accessor, no request")
            .rows(ROWS);

    private static List<CliRow> rows() {
        List<CliRow> rows = new ArrayList<>();
        rows.add(CliRow.of("qits login", CONNECT_PAGE_CODE, "issueToken", "TokenClient#exchange", idp(LOGIN))
                .consumes(SESSION_TOKENS).request("POST /idp/token")
                .fieldMatches("redirect_uri", CONNECT_PAGE));
        rows.add(CliRow.of("qits git-login", GIT_CODE, "issueToken", "TokenClient#exchange", idp(GIT_LOGIN))
                .consumes(SESSION_TOKENS));
        rows.add(CliRow.of("qits session-daemon", SESSION, "issueToken", "TokenClient#refresh", idp(REFRESH))
                .consumes(SESSION_TOKENS));
        rows.add(CliRow.of("qits session-daemon", SPENT_SESSION, "issueToken", "TokenClient#refresh", idp(SPENT))
                .consumes("error", "error_description"));
        rows.add(CliRow.of("qits work list", SESSION, "issueToken", "TokenClient#refresh", idp(REFRESH))
                .consumes(SESSION_TOKENS));
        rows.add(CliRow.of("qits git-credential", GIT_SESSION, "issueToken", "TokenClient#refresh", idp(GIT_REFRESH))
                .consumes(SESSION_TOKENS));
        rows.addAll(IdpUsersContract.ROWS);
        return List.copyOf(rows);
    }

    private IdpContract() {
    }
}
