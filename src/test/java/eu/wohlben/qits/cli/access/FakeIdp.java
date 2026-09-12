package eu.wohlben.qits.cli.access;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An idp in the test's own process, answering {@code /idp/token} the way the real one does:
 * PKCE-checked codes, refresh tokens that rotate, and a spent refresh token that revokes its whole
 * family. It serves both public clients, {@code qits-cli} and {@code qits-git-workstation}.
 * <p>
 * Every token it issues starts with {@link #SECRET}, so a test can prove that none reached the
 * output.
 */
public final class FakeIdp implements AutoCloseable {

    public static final String SECRET = "SECRET-";
    private static final Set<String> CLIENTS = Set.of("qits-cli", "qits-git-workstation");

    private record Approval(String challenge, String redirectUri) {
    }

    private final HttpServer server;
    private final Map<String, Approval> approvals = new ConcurrentHashMap<>();
    private final Set<String> liveRefresh = ConcurrentHashMap.newKeySet();
    private final Set<String> spentRefresh = ConcurrentHashMap.newKeySet();
    private final Deque<Integer> forcedStatus = new ConcurrentLinkedDeque<>();
    private final AtomicInteger serial = new AtomicInteger();
    public final List<Map<String, String>> requests = Collections.synchronizedList(new ArrayList<>());
    public volatile long expiresIn = 900;
    public volatile long refreshExpiresIn = 720 * 3600;
    public volatile Runnable onRequest = () -> { };

    public FakeIdp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/idp/token", this::token);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/idp";
    }

    /** The idp approves this code for this PKCE challenge, as after a `qits login` sign-in. */
    public void approve(String code, String challenge) {
        approve(code, challenge, url() + "/connect/cli");
    }

    /** The same, for a client whose redirect is its own (a loopback address). */
    public void approve(String code, String challenge, String redirectUri) {
        approvals.put(code, new Approval(challenge, redirectUri));
    }

    /** A live refresh token, as a past login left it. */
    public String issueRefreshToken() {
        String token = SECRET + "refresh-" + serial.incrementAndGet();
        liveRefresh.add(token);
        return token;
    }

    /** The next requests answer with these statuses, one each, before normal service resumes. */
    public void failNext(int... statuses) {
        for (int status : statuses) {
            forcedStatus.add(status);
        }
    }

    public long grants(String grantType) {
        return requests.stream().filter(r -> grantType.equals(r.get("grant_type"))).count();
    }

    private void token(HttpExchange exchange) throws IOException {
        Map<String, String> form = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        form.put("authorization-header", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
        requests.add(form);
        onRequest.run();
        Integer forced = forcedStatus.poll();
        if (forced != null) {
            respond(exchange, forced, "{\"error\":\"temporarily_unavailable\"}");
            return;
        }
        if (!CLIENTS.contains(form.get("client_id")) || exchange.getRequestHeaders().containsKey("Authorization")
                || form.containsKey("client_secret")) {
            respond(exchange, 400, "{\"error\":\"invalid_request\",\"error_description\":\"a public client must not use Authorization\"}");
            return;
        }
        switch (String.valueOf(form.get("grant_type"))) {
            case "authorization_code" -> {
                Approval approval = approvals.remove(String.valueOf(form.get("code")));
                if (approval == null || !approval.redirectUri().equals(form.get("redirect_uri"))
                        || !approval.challenge().equals(s256(form.get("code_verifier")))) {
                    respond(exchange, 400, "{\"error\":\"invalid_grant\",\"error_description\":"
                            + "\"authorization code is invalid, expired, or already used\"}");
                    return;
                }
                respond(exchange, 200, issue());
            }
            case "refresh_token" -> {
                String presented = String.valueOf(form.get("refresh_token"));
                if (liveRefresh.remove(presented)) {
                    spentRefresh.add(presented);
                    respond(exchange, 200, issue());
                    return;
                }
                if (spentRefresh.contains(presented)) {
                    // Replay of a spent token: the whole family goes.
                    liveRefresh.clear();
                }
                respond(exchange, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"refresh token is invalid\"}");
            }
            default -> respond(exchange, 400, "{\"error\":\"unsupported_grant_type\"}");
        }
    }

    private String issue() {
        int n = serial.incrementAndGet();
        String refresh = SECRET + "refresh-" + n;
        liveRefresh.add(refresh);
        return "{\"access_token\":\"" + SECRET + "access-" + n + "\",\"token_type\":\"Bearer\",\"expires_in\":" + expiresIn
                + ",\"refresh_token\":\"" + refresh + "\",\"refresh_expires_in\":" + refreshExpiresIn
                + ",\"a_field_added_later\":true}";
    }

    public static String s256(String verifier) {
        if (verifier == null) {
            return "";
        }
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static Map<String, String> form(String body) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String part : body.split("&")) {
            if (part.isEmpty()) {
                continue;
            }
            String[] pair = part.split("=", 2);
            result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
        }
        return result;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
