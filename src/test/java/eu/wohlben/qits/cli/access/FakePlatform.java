package eu.wohlben.qits.cli.access;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * The platform's edge in the test's own process: canned JSON answers by method and path, and a
 * Server-Sent Events route at {@code /events/api/stream} whose connections follow scripts, one
 * script per connection. It records every request, the Authorization header included, so a test
 * can check the bearer without any output showing it.
 */
public final class FakePlatform implements AutoCloseable {

    public record Request(String method, String path, String query, String authorization, String accept, String body) {
    }

    public record Answer(int status, String body, Map<String, String> headers) {
    }

    /** What one stream connection does. */
    @FunctionalInterface
    public interface StreamScript {
        void run(HttpExchange exchange) throws Exception;
    }

    private final HttpServer server;
    private final ExecutorService threads = Executors.newCachedThreadPool(r -> Thread.ofPlatform().daemon().unstarted(r));
    private final Map<String, Answer> answers = new ConcurrentHashMap<>();
    private final CountDownLatch closing = new CountDownLatch(1);
    public final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    public final BlockingDeque<StreamScript> streams = new LinkedBlockingDeque<>();

    public FakePlatform() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        // Handlers of held streams block; each exchange needs its own thread.
        server.setExecutor(threads);
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void answer(String method, String path, int status, String body, String... headerPairs) {
        Map<String, String> headers = new ConcurrentHashMap<>();
        for (int i = 0; i + 1 < headerPairs.length; i += 2) {
            headers.put(headerPairs[i], headerPairs[i + 1]);
        }
        answers.put(method + " " + path, new Answer(status, body, headers));
    }

    public void answer(String method, String path, String json) {
        answer(method, path, 200, json);
    }

    public List<Request> requests(String method, String path) {
        synchronized (requests) {
            return requests.stream().filter(r -> r.method().equals(method) && r.path().equals(path)).toList();
        }
    }

    /** A 200 stream that sends these lines, then ends, or stays open (silent) until the fake closes. */
    public StreamScript stream(boolean holdOpen, String... lines) {
        return exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream body = exchange.getResponseBody();
            for (String line : lines) {
                body.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                body.flush();
            }
            if (holdOpen) {
                closing.await();
            }
        };
    }

    /** A stream connection refused with this status. */
    public StreamScript refuse(int status, String body, String... headerPairs) {
        return exchange -> {
            for (int i = 0; i + 1 < headerPairs.length; i += 2) {
                exchange.getResponseHeaders().add(headerPairs[i], headerPairs[i + 1]);
            }
            respond(exchange, new Answer(status, body, Map.of()));
        };
    }

    /** One SSE event frame as the events service writes it. */
    public static String[] event(String id, String json) {
        return new String[] {"id:" + id, "data:" + json, ""};
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        URI uri = exchange.getRequestURI();
        requests.add(new Request(exchange.getRequestMethod(), uri.getPath(), uri.getRawQuery(),
                exchange.getRequestHeaders().getFirst("Authorization"), exchange.getRequestHeaders().getFirst("Accept"), body));
        if (uri.getPath().equals("/events/api/stream")) {
            StreamScript script = streams.poll();
            try {
                if (script == null) {
                    respond(exchange, new Answer(500, "{\"message\":\"the fake has no stream script left\"}", Map.of()));
                } else {
                    script.run(exchange);
                }
            } catch (Exception clientGone) {
                // The client aborted; nothing to answer.
            } finally {
                exchange.close();
            }
            return;
        }
        Answer answer = answers.get(exchange.getRequestMethod() + " " + uri.getPath());
        respond(exchange, answer != null ? answer
                : new Answer(404, "{\"message\":\"the fake has no answer for " + uri.getPath() + "\"}", Map.of()));
    }

    private static void respond(HttpExchange exchange, Answer answer) throws IOException {
        byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        answer.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        exchange.sendResponseHeaders(answer.status(), bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @Override
    public void close() {
        closing.countDown();
        server.stop(0);
        threads.shutdownNow();
    }
}
