package eu.wohlben.qits.cli.session;

import eu.wohlben.qits.cli.access.FakeIdp;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The workspace credential, against a stubbed idp: one mint, one bearer, and a refused audience. */
class AgentCredentialTest {

    private static final Instant T0 = Instant.parse("2026-09-13T10:00:00Z");

    private FakeIdp idp;
    private FakeTime time;

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdp();
        time = new FakeTime(T0);
    }

    @AfterEach
    void stop() {
        idp.close();
    }

    /** {@link FakeIdp#url()} already ends in {@code /idp}, which the credential appends itself. */
    private AgentCredential credential(String audience) {
        String base = idp.url().substring(0, idp.url().length() - "/idp".length());
        return new AgentCredential(base, idp.commissionedId, idp.commissionedSecret, audience, time);
    }

    @Test
    void itMintsWithTheCommissionedPairAndNoBrowser() throws CliFailure {
        AgentCredential agent = credential("qits-platform");
        assertThat(agent.bearer()).startsWith(FakeIdp.SECRET);
        assertThat(idp.grants("client_credentials")).isEqualTo(1);
        assertThat(idp.requests).singleElement().satisfies(request -> {
            assertThat(request).containsEntry("client_id", idp.commissionedId);
            assertThat(request).containsEntry("audience", "qits-platform");
        });
    }

    @Test
    void oneTokenServesEveryServiceTheClientHolds() throws CliFailure {
        AgentCredential agent = credential("qits-platform");
        String first = agent.bearer();
        agent.checkAudience("dev-qits-projects");
        agent.checkAudience("dev-qits-ci");
        assertThat(agent.bearer()).isEqualTo(first);
        assertThat(agent.mints()).as("the aud claim carries the whole grant, so once is enough").isEqualTo(1);
        assertThat(agent.granted()).contains("dev-qits-projects", "dev-qits-ci", "qits-platform");
    }

    @Test
    void aTokenNearItsEndIsMintedAgain() throws CliFailure {
        idp.clientCredentialsExpiresIn = 3600;
        AgentCredential agent = credential("qits-platform");
        agent.bearer();
        time.jump(Duration.ofSeconds(3600).minus(AgentCredential.MARGIN).minusSeconds(1));
        agent.bearer();
        assertThat(agent.mints()).isEqualTo(1);
        time.jump(Duration.ofSeconds(2));
        agent.bearer();
        assertThat(agent.mints()).as("under a minute left is a new token").isEqualTo(2);
    }

    @Test
    void aServiceOutsideTheGrantIsOnePlainSentence() throws CliFailure {
        AgentCredential agent = credential("qits-platform");
        assertThatThrownBy(() -> agent.checkAudience("dev-qits-observability"))
                .isInstanceOf(CliFailure.class)
                .hasMessage("the workspace credential is not granted dev-qits-observability")
                .satisfies(refusal -> assertThat(((CliFailure) refusal).exitCode()).isEqualTo(CliFailure.USAGE));
    }

    @Test
    void anAddressSomebodyPointedAtByHandIsNotJudged() throws CliFailure {
        AgentCredential agent = credential("qits-platform");
        agent.checkAudience("127.0.0.1");
        agent.checkAudience("projects.dev.wohlben.eu");
    }

    @Test
    void anAudienceTheMintItselfRefusesReadsTheSameWay() {
        idp.grantedAudiences = List.of("qits-platform");
        AgentCredential agent = credential("dev-qits-observability");
        assertThatThrownBy(agent::bearer)
                .isInstanceOf(CliFailure.class)
                .hasMessage("the workspace credential is not granted dev-qits-observability");
    }

    @Test
    void theRoleItHoldsIsWhatTheScreenSays() throws CliFailure {
        assertThat(credential("qits-platform").who()).isEqualTo("agent (qits:agent)");
    }

    @Test
    void nothingItHoldsIsEverPrinted() throws CliFailure {
        AgentCredential agent = credential("qits-platform");
        String bearer = agent.bearer();
        assertThat(agent.toString()).doesNotContain(bearer).doesNotContain(idp.commissionedSecret);
    }
}
