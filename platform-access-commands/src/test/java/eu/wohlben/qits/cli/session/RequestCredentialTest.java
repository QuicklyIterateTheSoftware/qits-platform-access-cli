package eu.wohlben.qits.cli.session;

import eu.wohlben.qits.cli.access.FakePlatform;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The caller's bearer, handed in whole: sent as it is, named from its claims, never minted. */
class RequestCredentialTest {

    private static final String AGENT = token("""
            {"sub":"workspace-7f3a","aud":["dev-qits-projects","qits-platform"],"groups":["qits:agent"]}""");
    private static final String PERSON = token("""
            {"sub":"0b5e","preferred_username":"jan","aud":"qits-platform","groups":["qits:admin","staff"]}""");

    /** An unsigned JWT: the signature is not read here, so none is made. */
    private static String token(String claims) {
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        return base64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + base64.encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".signature";
    }

    @Test
    void theBearerIsTheTokenItWasBuiltWithEveryTime() {
        RequestCredential credential = new RequestCredential(AGENT);
        assertThat(credential.bearer()).isEqualTo(AGENT);
        assertThat(credential.bearer()).isEqualTo(AGENT);
    }

    /** There is nothing behind it to mint or refresh with: whatever it was given is what it sends. */
    @Test
    void aTokenItCannotReadIsStillSentAsItIs() {
        RequestCredential credential = new RequestCredential("not even a jwt");
        assertThat(credential.bearer()).isEqualTo("not even a jwt");
        assertThat(credential.who()).isEqualTo("signed in");
        assertThat(credential.explain(403)).isNull();
    }

    @Test
    void itNamesTheHolderTheWayTheirOwnCredentialWould() {
        assertThat(new RequestCredential(AGENT).who()).isEqualTo("agent (qits:agent)");
        assertThat(new RequestCredential(PERSON).who()).isEqualTo("signed in as jan");
        assertThat(new RequestCredential(token("{\"sub\":\"0b5e\"}")).who()).isEqualTo("signed in as 0b5e");
    }

    @Test
    void anAgentsTokenIsRefusedInTheWorkspaceCredentialsOwnWords() {
        AgentCredential workspace = new AgentCredential("http://idp", "id", "secret", "qits-platform",
                new FakeTime(Instant.parse("2026-10-01T10:00:00Z")));
        RequestCredential forwarded = new RequestCredential(AGENT);

        assertThat(forwarded.explain(403)).isEqualTo(workspace.explain(403))
                .isEqualTo("403 - this credential is qits:agent, which reads but does not write");
        assertThat(forwarded.explain(401)).isEqualTo(workspace.explain(401));
        assertThat(forwarded.explain(404)).isNull();
    }

    @Test
    void aPersonsTokenAddsNothingAndTheServiceSpeaks() {
        RequestCredential person = new RequestCredential(PERSON);
        assertThat(person.explain(403)).isNull();
        assertThat(person.explain(401)).isNull();
    }

    @Test
    void aRefusedCallSaysWhatTheCallerHeld() throws Exception {
        try (FakePlatform platform = new FakePlatform()) {
            platform.answer("POST", "/projects/api/write", 403, "{\"error\":\"forbidden\"}");
            PlatformClient client = new PlatformClient(new RequestCredential(AGENT));
            assertThatThrownBy(() -> client.post(URI.create(platform.url() + "/projects/api/write")))
                    .isInstanceOf(CliFailure.class)
                    .hasMessageStartingWith("403 - this credential is qits:agent, which reads but does not write");
            assertThat(platform.requests("POST", "/projects/api/write")).singleElement()
                    .satisfies(request -> assertThat(request.authorization()).isEqualTo("Bearer " + AGENT));
        }
    }

    @Test
    void itRefusesToBeBuiltWithNothing() {
        assertThatThrownBy(() -> new RequestCredential(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequestCredential(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void itNeverPrintsTheToken() {
        assertThat(new RequestCredential(PERSON).toString()).doesNotContain(PERSON).contains("jan");
    }
}
