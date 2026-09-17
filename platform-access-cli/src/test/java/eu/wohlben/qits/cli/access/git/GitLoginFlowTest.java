package eu.wohlben.qits.cli.access.git;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cli.access.FakeIdp;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.session.ExclusiveLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class GitLoginFlowTest {

    private static final Instant T0 = Instant.parse("2026-09-12T10:00:00Z");
    static final String ORIGIN = "https://githost.dev.wohlben.eu";
    private static final String AUDIENCE = "dev-qits-githost";

    @TempDir
    Path home;

    private FakeIdp idp;
    private FakeTime time;
    private GitCredentialFile store;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private String opened;

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdp();
        time = new FakeTime(T0);
        store = new GitCredentialFile(home.resolve("qits"));
    }

    @AfterEach
    void stop() {
        idp.close();
        assertThat(out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8)).doesNotContain(FakeIdp.SECRET);
    }

    private int login(Consumer<String> browser, Duration timeout) throws Exception {
        return new GitLoginFlow(new TokenClient(idp.url(), GitLoginFlow.CLIENT_ID), store, ORIGIN, AUDIENCE,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                time, url -> {
                    opened = url;
                    browser.accept(url);
                }, timeout).run();
    }

    /** What the person and the idp do: sign in, and the idp sends the browser back with a code. */
    static Consumer<String> signIn(FakeIdp idp, String code, String stateOverride) {
        return url -> {
            Map<String, String> q = query(url);
            idp.approve(code, q.get("code_challenge"), q.get("redirect_uri"));
            String state = stateOverride != null ? stateOverride : q.get("state");
            visit(q.get("redirect_uri") + "?code=" + enc(code) + "&state=" + enc(state));
        };
    }

    static void visit(String url) {
        try (HttpClient http = HttpClient.newHttpClient()) {
            http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    static Map<String, String> query(String url) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String part : URI.create(url).getRawQuery().split("&")) {
            String[] pair = part.split("=", 2);
            result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return result;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Test
    void theAddressNamesTheGitClientTheAudienceAndALoopbackCallback() throws Exception {
        assertThat(login(signIn(idp, "code-1", null), Duration.ofSeconds(10))).isZero();

        assertThat(opened).startsWith(idp.url() + "/authorize?");
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("  " + opened);
        Map<String, String> q = query(opened);
        assertThat(q).containsEntry("response_type", "code")
                .containsEntry("client_id", "qits-git-workstation")
                .containsEntry("code_challenge_method", "S256")
                .containsEntry("audience", AUDIENCE)
                .containsKey("code_challenge")
                .doesNotContainKey("code_verifier");
        assertThat(q.get("redirect_uri")).matches("http://127\\.0\\.0\\.1:\\d+/callback");
        assertThat(q.get("state")).hasSizeGreaterThanOrEqualTo(40);
    }

    @Test
    void theAnswerIsExchangedAndStoredUnderTheOrigin() throws Exception {
        assertThat(login(signIn(idp, "code-1", null), Duration.ofSeconds(10))).isZero();

        Map<String, String> exchange = idp.requests.getFirst();
        assertThat(exchange).containsEntry("grant_type", "authorization_code")
                .containsEntry("client_id", "qits-git-workstation")
                .containsEntry("code", "code-1")
                .containsEntry("redirect_uri", query(opened).get("redirect_uri"))
                .containsEntry("authorization-header", "null")
                .containsKey("code_verifier");

        GitCredential saved = store.find(ORIGIN).orElseThrow();
        assertThat(saved.idpUrl()).isEqualTo(idp.url());
        assertThat(saved.clientId()).isEqualTo("qits-git-workstation");
        assertThat(saved.audience()).isEqualTo(AUDIENCE);
        assertThat(saved.gitOrigin()).isEqualTo(ORIGIN);
        assertThat(saved.accessToken()).startsWith(FakeIdp.SECRET + "access-");
        assertThat(saved.accessExpiresAt()).isEqualTo(T0.plusSeconds(900));
        assertThat(saved.refreshToken()).startsWith(FakeIdp.SECRET + "refresh-");
        assertThat(saved.refreshExpiresAt()).isEqualTo(T0.plus(Duration.ofHours(720)));
        assertThat(saved.toString()).doesNotContain(FakeIdp.SECRET);

        JsonNode file = new ObjectMapper().readTree(Files.readString(store.path()));
        assertThat(file.path("credentials").path(ORIGIN).path("accessExpiresAt").asText()).isEqualTo("2026-09-12T10:15:00Z");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(store.path()))).isEqualTo("rw-------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(home.resolve("qits")))).isEqualTo("rwx------");
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Signed in for Git pushes to " + ORIGIN)
                .contains("refs/heads/external/").contains("The sign-in lasts until ");
    }

    @Test
    void anotherOriginsSignInIsKept() throws Exception {
        GitCredential other = new GitCredential(idp.url(), GitLoginFlow.CLIENT_ID, "prod-qits-githost",
                "http://githost.prod.localhost:8080", null, null, FakeIdp.SECRET + "other", T0.plus(Duration.ofDays(1)));
        try (ExclusiveLock ignored = store.lock()) {
            store.put(other);
        }

        assertThat(login(signIn(idp, "code-1", null), Duration.ofSeconds(10))).isZero();

        assertThat(store.read()).containsOnlyKeys(ORIGIN, "http://githost.prod.localhost:8080");
        assertThat(store.find("http://githost.prod.localhost:8080")).contains(other);
    }

    @Test
    void anAnswerWithAnotherStateIsRefused() throws Exception {
        assertThat(login(signIn(idp, "code-1", "forged"), Duration.ofSeconds(10))).isEqualTo(1);

        assertThat(err.toString(StandardCharsets.UTF_8)).contains("not for this sign-in").contains("Nothing was saved");
        assertThat(idp.requests).isEmpty();
        assertThat(store.path()).doesNotExist();
    }

    @Test
    void anErrorFromTheIdpIsShown() throws Exception {
        Consumer<String> denied = url -> visit(query(url).get("redirect_uri") + "?error=access_denied"
                + "&error_description=no+session&state=" + enc(query(url).get("state")));

        assertThat(login(denied, Duration.ofSeconds(10))).isEqualTo(1);

        assertThat(err.toString(StandardCharsets.UTF_8)).contains("did not sign you in (access_denied: no session)");
        assertThat(store.path()).doesNotExist();
    }

    @Test
    void noAnswerInTimeSavesNothing() throws Exception {
        assertThat(login(url -> { }, Duration.ofMillis(300))).isEqualTo(1);

        assertThat(err.toString(StandardCharsets.UTF_8)).contains("did not come back").contains("Run `qits git-login` again");
        assertThat(store.path()).doesNotExist();
    }

    @Test
    void aCodeTheIdpRefusesSavesNothing() throws Exception {
        Consumer<String> wrongCode = url -> visit(query(url).get("redirect_uri") + "?code=never-approved&state="
                + enc(query(url).get("state")));

        assertThat(login(wrongCode, Duration.ofSeconds(10))).isEqualTo(1);

        assertThat(err.toString(StandardCharsets.UTF_8)).contains("invalid_grant").contains("Nothing was saved");
        assertThat(store.path()).doesNotExist();
    }
}
