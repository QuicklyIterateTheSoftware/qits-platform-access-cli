package eu.wohlben.qits.cli.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.smallrye.jwt.util.KeyUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * <b>An idp that publishes its issuer</b>, for {@link BearerIssuerTest}: what the tenant reads of
 * qits-idp and nothing else. {@code /idp/.well-known/openid-configuration} answers {@link #ISSUER}
 * and a {@code jwks_uri} on this server, and {@code /idp/jwks} answers the suite's verification key
 * ({@code bearer-verification-key.pem}) under the {@code kid} {@link BearerProfile} signs with.
 * <p>
 * One server per JVM. The test profile and the test class are loaded by two class loaders, so a
 * static field would be two servers: the address is kept in a system property instead, and the
 * requests the server saw are read back over HTTP ({@link #recorded}), not from a field.
 */
final class IssuerIdp {

    /** The issuer this idp publishes, as qits-idp publishes {@code https://idp.qits.<domain>}. */
    static final String ISSUER = "https://idp.qits.mcp-suite.invalid";

    private static final String ADDRESS = "qits.mcp-suite.issuer-idp";
    private static final String RECORDED = "/recorded";

    private IssuerIdp() {
    }

    /** The idp's base address, {@code http://127.0.0.1:<port>}, started on first use. */
    static synchronized String ensureStarted() {
        String address = System.getProperty(ADDRESS);
        if (address != null) {
            return address;
        }
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            List<String> seen = new CopyOnWriteArrayList<>();
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                switch (path) {
                    case RECORDED -> answer(exchange, 200, "text/plain", String.join("\n", seen));
                    case "/idp/.well-known/openid-configuration" -> {
                        seen.add(path);
                        answer(exchange, 200, "application/json", """
                                {"issuer":"%s","jwks_uri":"%s/idp/jwks","token_endpoint":"%s/idp/token"}"""
                                .formatted(ISSUER, base, base));
                    }
                    case "/idp/jwks" -> {
                        seen.add(path);
                        answer(exchange, 200, "application/json", jwks());
                    }
                    default -> {
                        seen.add(path);
                        answer(exchange, 404, "text/plain", "");
                    }
                }
            });
            server.start();
            System.setProperty(ADDRESS, base);
            return base;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The paths the idp was asked for, in order, whichever class loader started it. */
    static List<String> recorded() {
        try (HttpClient client = HttpClient.newHttpClient()) {
            String body = client.send(HttpRequest.newBuilder(URI.create(ensureStarted() + RECORDED)).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            return body.isEmpty() ? List.of() : List.of(body.split("\n"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String jwks() {
        try {
            RSAPublicKey key = (RSAPublicKey) KeyUtils.decodePublicKey(BearerProfile.pem("/bearer-verification-key.pem"));
            Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
            return """
                    {"keys":[{"kty":"RSA","use":"sig","alg":"RS256","kid":"mcp-suite-key","n":"%s","e":"%s"}]}"""
                    .formatted(b64.encodeToString(unsigned(key.getModulus().toByteArray())),
                            b64.encodeToString(unsigned(key.getPublicExponent().toByteArray())));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the suite's verification key", e);
        }
    }

    /** A big integer's bytes without the sign byte a JWK does not carry. */
    private static byte[] unsigned(byte[] bytes) {
        return bytes.length > 1 && bytes[0] == 0 ? java.util.Arrays.copyOfRange(bytes, 1, bytes.length) : bytes;
    }

    private static void answer(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
