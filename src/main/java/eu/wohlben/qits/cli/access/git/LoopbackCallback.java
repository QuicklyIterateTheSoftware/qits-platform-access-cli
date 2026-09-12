package eu.wohlben.qits.cli.access.git;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The one-use address the browser comes back to after the sign-in: {@code
 * http://127.0.0.1:<port>/callback}, on a port the system picks. The code never passes through a
 * file or a command line. {@code qits-git-workstation}'s redirect must be an HTTP loopback address;
 * the idp's code page is only for {@code qits-cli}. Copied from qits-bootstrap-cli.
 */
public final class LoopbackCallback implements AutoCloseable {

    private final HttpServer server;
    private final CompletableFuture<Callback> callback = new CompletableFuture<>();

    private LoopbackCallback(HttpServer server) {
        this.server = server;
    }

    public static LoopbackCallback open() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        LoopbackCallback result = new LoopbackCallback(server);
        server.createContext("/callback", result::handle);
        server.start();
        return result;
    }

    public String redirectUri() {
        String host = server.getAddress().getAddress().getHostAddress();
        String bracketed = host.contains(":") ? "[" + host + "]" : host;
        return "http://" + bracketed + ":" + server.getAddress().getPort() + "/callback";
    }

    /** The first answer, or empty when none came in time. */
    public Optional<Callback> await(Duration timeout) throws InterruptedException {
        try {
            return Optional.of(callback.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException | ExecutionException none) {
            return Optional.empty();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        Map<String, String> query = query(exchange.getRequestURI());
        callback.complete(new Callback(query.get("code"), query.get("state"), query.get("error"),
                query.get("error_description")));
        byte[] response = "qits git-login has the answer. Go back to the terminal; you can close this page."
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> result = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String part : raw.split("&")) {
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

    /** What the browser brought back. {@code toString} leaves the code out. */
    public record Callback(String code, String state, String error, String errorDescription) {
        @Override
        public String toString() {
            return "Callback[error=" + error + "]";
        }
    }
}
