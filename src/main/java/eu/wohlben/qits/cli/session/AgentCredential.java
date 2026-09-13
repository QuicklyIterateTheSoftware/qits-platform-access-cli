package eu.wohlben.qits.cli.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.session.TokenClaims;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The credential a workspace container has: the commissioned client pair it was injected with,
 * exchanged at the internal idp for a bearer.
 * <p>
 * <b>Minted once per process.</b> The idp answers a {@code client_credentials} grant with a token
 * whose {@code aud} claim carries every audience the client holds, not the one that was asked for
 * (verified on dev, 2026-09-13) — so one bearer serves every service and there is no token per
 * target to keep. The grant has no refresh token, so refreshing is minting again, which happens
 * when under {@link #MARGIN} of its hour is left.
 * <p>
 * The token is held in memory and nowhere else. It is never written to the workstation session file
 * and never under the agent's config folder: that folder is becoming a git repository, and a machine
 * credential committed to a branch is a leak with a history.
 * <p>
 * <b>No message here holds the token or the client secret.</b> A refusal names the status and the
 * idp's own {@code error} and {@code error_description}, which hold neither.
 */
public final class AgentCredential implements Credential {

    /** A token this close to its end may expire on the way, so it is minted again first. */
    public static final Duration MARGIN = Duration.ofSeconds(60);

    /** What a container is told to ask for when nothing else says. Granted to every workspace. */
    static final String DEFAULT_AUDIENCE = "qits-platform";

    static final String AUDIENCE = "QITS_GIT_AUTH_AUDIENCE";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;
    private final String audience;
    private final Clock clock;
    private final HttpClient http;

    private String token;
    private Instant expiresAt = Instant.EPOCH;
    private List<String> granted = List.of();
    private int mints;

    /**
     * The one credential of this process.
     * <p>
     * Minted once and held once: a screen that opens five dropdowns and runs a command must not go
     * to the idp six times, and the token it would get each time is the same token. Held statically
     * because it is a property of the process's environment, which is the same thing for every
     * object in it; a test builds its own instance instead.
     */
    private static volatile AgentCredential shared;

    public static AgentCredential of(Map<String, String> env, Clock clock) {
        AgentCredential held = shared;
        if (held != null) {
            return held;
        }
        synchronized (AgentCredential.class) {
            if (shared == null) {
                shared = new AgentCredential(
                        new PlatformEndpoints(Mode.IN_PLATFORM, env, null).wire("idp"), env, clock);
            }
            return shared;
        }
    }

    public AgentCredential(String idpBase, Map<String, String> env, Clock clock) {
        this(idpBase, env.get(Mode.CLIENT_ID), env.get(Mode.CLIENT_SECRET),
                env.getOrDefault(AUDIENCE, DEFAULT_AUDIENCE), clock);
    }

    public AgentCredential(String idpBase, String clientId, String clientSecret, String audience, Clock clock) {
        this.tokenUrl = trim(idpBase) + "/idp/token";
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.audience = audience;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public synchronized String bearer() throws CliFailure {
        if (token == null || !clock.instant().isBefore(expiresAt.minus(MARGIN))) {
            mint();
        }
        return token;
    }

    /**
     * Refused only for a service dialled by its own name on the wire, which is what its audience is.
     * <p>
     * Inside the platform a service is a single label — {@code dev-qits-projects} — and a name with
     * a dot in it is something a person pointed at by hand, with {@code QITS_URL_<APP>} or a
     * command's own flag. Whatever that is, it is not this credential's grant to judge: the call
     * goes out and whatever answers, answers.
     */
    @Override
    public synchronized void checkAudience(String wanted) throws CliFailure {
        bearer();
        if (wanted == null || wanted.isBlank() || wanted.contains(".") || granted.isEmpty()
                || granted.contains(wanted)) {
            return;
        }
        throw new CliFailure("the workspace credential is not granted " + wanted, CliFailure.USAGE);
    }

    /**
     * The one refusal an agent will meet often. The credential reads across the estate and writes
     * nowhere, which is the correct answer and not a fault to work around.
     * <p>
     * <b>Not</b> worked around with {@code X-Qits-User} / {@code X-Qits-Roles}. Those headers are
     * what the gateway asserts <i>about</i> a caller; a client that writes them asserts a role it
     * does not hold, which is a privilege escalation with a friendly name. An agent that needs a
     * door this credential cannot open is granted the audience, not given a header.
     */
    @Override
    public String explain(int status) {
        return status == 403 ? "403 - this credential is qits:agent, which reads but does not write" : null;
    }

    @Override
    public String who() throws CliFailure {
        String role = TokenClaims.of(bearer()).role().orElse("no role");
        return "agent (" + role + ")";
    }

    /** The audiences the idp says this credential holds, once it has been minted. */
    public synchronized List<String> granted() throws CliFailure {
        bearer();
        return granted;
    }

    /** How many times this process has been to the idp. The screen never shows it; the tests do. */
    public synchronized int mints() {
        return mints;
    }

    private void mint() throws CliFailure {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new CliFailure("There is no workspace credential in this environment ("
                    + Mode.CLIENT_ID + " and " + Mode.CLIENT_SECRET + ").", CliFailure.USAGE);
        }
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "client_credentials");
        body.put("client_id", clientId);
        body.put("client_secret", clientSecret);
        body.put("audience", audience);
        HttpResponse<String> answer;
        try {
            mints++;
            answer = http.send(HttpRequest.newBuilder(URI.create(tokenUrl))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(form(body), StandardCharsets.UTF_8))
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException unreachable) {
            throw CliFailure.retryable("Cannot reach the idp at " + tokenUrl + ": " + unreachable.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw CliFailure.retryable("Interrupted while minting the workspace credential.");
        }
        if (answer.statusCode() != 200) {
            throw refusal(answer);
        }
        JsonNode issued = read(answer.body());
        token = issued.path("access_token").asText(null);
        if (token == null || token.isBlank()) {
            throw new CliFailure("The idp answered the workspace credential without an access token.",
                    CliFailure.FAILED);
        }
        long seconds = issued.path("expires_in").asLong(3600);
        expiresAt = clock.instant().plusSeconds(seconds);
        granted = TokenClaims.of(token).audiences();
    }

    /**
     * {@code invalid_target} is the idp saying this client was never granted that service. It is
     * not a crash and not a bug to work around: the answer is to grant the audience.
     */
    private CliFailure refusal(HttpResponse<String> answer) {
        JsonNode body = read(answer.body());
        String error = body.path("error").asText("");
        if ("invalid_target".equals(error)) {
            return new CliFailure("the workspace credential is not granted " + audience, CliFailure.USAGE);
        }
        String detail = body.path("error_description").asText("");
        return new CliFailure("The idp refused the workspace credential (HTTP " + answer.statusCode()
                + (error.isEmpty() ? "" : ", " + error) + (detail.isEmpty() ? "" : ": " + detail) + ").",
                CliFailure.FAILED);
    }

    /** A body that will not parse is replaced: its message could quote the body, and the body is a token. */
    private static JsonNode read(String body) {
        try {
            return JSON.readTree(body == null ? "{}" : body);
        } catch (IOException notJson) {
            return JSON.createObjectNode();
        }
    }

    private static String form(Map<String, String> fields) {
        return fields.entrySet().stream()
                .map(field -> URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private static String trim(String url) {
        String stripped = url == null ? "" : url.strip();
        while (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }

    @Override
    public String toString() {
        return "AgentCredential[" + clientId + ", " + tokenUrl + "]";
    }
}
