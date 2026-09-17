package eu.wohlben.qits.cli.access.login;

import eu.wohlben.qits.cli.access.FakeIdp;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.session.Session;
import eu.wohlben.qits.cli.access.session.SessionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class LoginFlowTest {

    private static final Instant T0 = Instant.parse("2026-09-11T20:00:00Z");

    @TempDir
    Path home;

    private FakeIdp idp;
    private FakeTime time;
    private SessionFile store;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private String openedInBrowser;

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdp();
        time = new FakeTime(T0);
        store = new SessionFile(home.resolve("qits"));
    }

    @AfterEach
    void stop() {
        idp.close();
        // Whatever happened, no token reached the screen.
        assertThat(out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8))
                .doesNotContain(FakeIdp.SECRET);
    }

    /** The lines a person types. A supplier runs when its line is read, so it can see the output. */
    private int login(List<Supplier<String>> lines) throws Exception {
        Deque<Supplier<String>> queue = new ArrayDeque<>(lines);
        BufferedReader in = new BufferedReader(new StringReader("")) {
            @Override
            public String readLine() {
                Supplier<String> next = queue.poll();
                return next == null ? null : next.get();
            }
        };
        return new LoginFlow(new TokenClient(idp.url()), store, in,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                time, url -> openedInBrowser = url).run();
    }

    /** What the person does in the browser: sign in, and the idp approves a code for the challenge. */
    private Supplier<String> signInAndPaste(String code) {
        return () -> {
            idp.approve(code, query(printedUrl()).get("code_challenge"));
            return "  " + code + "  ";
        };
    }

    /** The address of the latest sign-in: a test may run two logins on one output. */
    private String printedUrl() {
        return out.toString(StandardCharsets.UTF_8).lines().map(String::strip)
                .filter(l -> l.startsWith("http")).reduce((earlier, later) -> later).orElseThrow();
    }

    @Test
    void theAuthorizeUrlIsPrintedAndOpened() throws Exception {
        login(List.of());
        String url = printedUrl();
        assertThat(url).startsWith(idp.url() + "/authorize?");
        Map<String, String> query = query(url);
        assertThat(query).containsEntry("response_type", "code")
                .containsEntry("client_id", "qits-cli")
                .containsEntry("redirect_uri", idp.url() + "/connect/cli")
                .containsEntry("code_challenge_method", "S256")
                .containsKey("code_challenge")
                .doesNotContainKey("code_verifier");
        assertThat(openedInBrowser).isEqualTo(url);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Paste the code: ");
    }

    @Test
    void aGoodCodeWritesTheSession() throws Exception {
        assertThat(login(List.of(signInAndPaste("good-code")))).isZero();

        Map<String, String> exchange = idp.requests.getFirst();
        assertThat(exchange).containsEntry("grant_type", "authorization_code")
                .containsEntry("client_id", "qits-cli")
                .containsEntry("code", "good-code")
                .containsEntry("redirect_uri", idp.url() + "/connect/cli")
                .containsEntry("authorization-header", "null")
                .doesNotContainKey("client_secret");

        Session session = store.read().orElseThrow();
        assertThat(session.idpUrl()).isEqualTo(idp.url());
        assertThat(session.clientId()).isEqualTo("qits-cli");
        assertThat(session.accessToken()).startsWith(FakeIdp.SECRET + "access-");
        assertThat(session.refreshToken()).startsWith(FakeIdp.SECRET + "refresh-");
        assertThat(session.accessExpiresAt()).isEqualTo(T0.plusSeconds(900));
        assertThat(session.refreshExpiresAt()).isEqualTo(T0.plus(Duration.ofHours(720)));
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(store.path()))).isEqualTo("rw-------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(store.directory()))).isEqualTo("rwx------");
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Signed in to " + idp.url())
                .contains("Session saved to " + store.path())
                .contains("Session ends ");
    }

    @Test
    void aBadCodeAsksAgain() throws Exception {
        assertThat(login(List.of(() -> "bogus", () -> "", signInAndPaste("good-code")))).isZero();
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("did not accept that code").contains("Paste it again");
        assertThat(idp.grants("authorization_code")).isEqualTo(2);
        assertThat(store.read()).isPresent();
    }

    @Test
    void theEndOfInputEndsWithNoFile() throws Exception {
        assertThat(login(List.of(() -> "bogus"))).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("Nothing was saved");
        assertThat(store.path()).doesNotExist();
    }

    @Test
    void afterTheCodeTtlTheFlowSendsThePersonBackToLogin() throws Exception {
        List<Supplier<String>> lines = List.of(() -> "bogus", () -> {
            time.jump(LoginFlow.CODE_TTL.plusSeconds(1));
            return "still-bogus";
        }, () -> "never-read");
        assertThat(login(lines)).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("Run `qits login` again");
        assertThat(idp.grants("authorization_code")).isEqualTo(2);
        assertThat(store.path()).doesNotExist();
    }

    @Test
    void anUnreachableIdpAsksAgainAndThenWorks() throws Exception {
        idp.failNext(503);
        assertThat(login(List.of(signInAndPaste("good-code"), signInAndPaste("second-code")))).isZero();
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("HTTP 503").contains("Paste the code again");
    }

    /** A new login replaces an old session whole, and leaves no temporary file behind. */
    @Test
    void aNewLoginReplacesTheFile() throws Exception {
        assertThat(login(List.of(signInAndPaste("first")))).isZero();
        Session first = store.read().orElseThrow();
        Object firstInode = Files.getAttribute(store.path(), "unix:ino");

        time.jump(Duration.ofMinutes(1));
        assertThat(login(List.of(signInAndPaste("second")))).isZero();
        Session second = store.read().orElseThrow();

        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(second.accessExpiresAt()).isEqualTo(T0.plus(Duration.ofMinutes(1)).plusSeconds(900));
        // A rename, not a rewrite in place: a reader holding the old file never sees a mix.
        assertThat(Files.getAttribute(store.path(), "unix:ino")).isNotEqualTo(firstInode);
        try (var files = Files.list(store.directory())) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder("t.json", "t.json.lock");
        }
    }

    private static Map<String, String> query(String url) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String part : URI.create(url).getRawQuery().split("&")) {
            String[] pair = part.split("=", 2);
            result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return result;
    }
}
