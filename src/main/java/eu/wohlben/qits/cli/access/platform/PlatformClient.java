package eu.wohlben.qits.cli.access.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Calls to platform services through the edge, with the session's access token as a bearer.
 * <p>
 * <b>No message here holds a token.</b> A refusal names the method, the address, the status and
 * the service's own error message; the request's headers never appear.
 */
public final class PlatformClient {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern AUTH_PARAM = Pattern.compile("(error|error_description)=\"([^\"]*)\"");

    private final AccessTokens tokens;

    public PlatformClient(AccessTokens tokens) {
        this.tokens = tokens;
    }

    public JsonNode get(URI uri) throws CliFailure, InterruptedException {
        return send("GET", uri, HttpRequest.newBuilder(uri).GET());
    }

    public JsonNode post(URI uri, JsonNode body) throws CliFailure, InterruptedException {
        String json;
        try {
            json = JSON.writeValueAsString(body);
        } catch (IOException impossible) {
            throw new CliFailure("cannot write the request body", CliFailure.FAILED);
        }
        return send("POST", uri, HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)));
    }

    private JsonNode send(String method, URI uri, HttpRequest.Builder builder) throws CliFailure, InterruptedException {
        HttpRequest request = builder
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + tokens.session().accessToken())
                .build();
        HttpResponse<String> response;
        try (HttpClient http = newClient()) {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw CliFailure.retryable("Cannot reach " + uri + ": " + describe(e));
        }
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw refusal(method, uri, status, response.headers(), response.body());
        }
        try {
            JsonNode node = JSON.readTree(response.body());
            if (node == null || node.isMissingNode()) {
                throw new IOException("empty");
            }
            return node;
        } catch (IOException e) {
            throw new CliFailure(method + " " + uri + " answered with a body that is not JSON.", CliFailure.FAILED);
        }
    }

    /**
     * Opens a Server-Sent Events stream. A status other than 200 is a {@link CliFailure}: retryable
     * for a 5xx, final for a 4xx. The caller reads the lines and closes the connection.
     */
    public Connection openStream(URI uri) throws CliFailure, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + tokens.session().accessToken())
                .GET()
                .build();
        // A client per connection, so that stopping can abort exactly this one. No request
        // timeout: the body never ends, and a silent stream is the caller's watchdog's business.
        HttpClient http = newClient();
        HttpResponse<Stream<String>> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofLines());
        } catch (IOException e) {
            http.shutdownNow();
            throw CliFailure.retryable("Cannot reach " + uri + ": " + describe(e));
        } catch (InterruptedException e) {
            http.shutdownNow();
            throw e;
        }
        int status = response.statusCode();
        if (status != 200) {
            String body;
            try (Stream<String> lines = response.body()) {
                body = lines.limit(200).collect(Collectors.joining("\n"));
            } catch (RuntimeException unreadable) {
                body = "";
            }
            http.shutdownNow();
            CliFailure refusal = refusal("GET", uri, status, response.headers(), body);
            throw status >= 500 ? CliFailure.retryable(refusal.getMessage()) : refusal;
        }
        return new Connection(http, response.body());
    }

    /** An open stream. {@link #abort()} may be called from any thread, and ends a blocked read. */
    public static final class Connection implements AutoCloseable {
        private final HttpClient http;
        private final Stream<String> lines;

        Connection(HttpClient http, Stream<String> lines) {
            this.http = http;
            this.lines = lines;
        }

        public Stream<String> lines() {
            return lines;
        }

        public void abort() {
            http.shutdownNow();
        }

        @Override
        public void close() {
            abort();
            try {
                lines.close();
            } catch (RuntimeException ignored) {
                // The client is already shut down; nothing is left to release.
            }
        }
    }

    static CliFailure refusal(String method, URI uri, int status, HttpHeaders headers, String body) {
        if (status == 401) {
            String detail = headers.allValues("WWW-Authenticate").stream()
                    .map(PlatformClient::authenticateError)
                    .filter(s -> !s.isEmpty())
                    .findFirst()
                    .orElse("");
            return new CliFailure("The platform refused the token (HTTP 401" + (detail.isEmpty() ? "" : ": " + detail)
                    + "). If this goes on, run `qits login`.", CliFailure.FAILED);
        }
        if (status == 403) {
            return new CliFailure("Your roles do not allow this (HTTP 403): " + method + " " + uri, CliFailure.FAILED);
        }
        StringBuilder message = new StringBuilder(method + " " + uri + " answered HTTP " + status);
        String serviceMessage = serviceMessage(body);
        if (!serviceMessage.isEmpty()) {
            message.append(": ").append(serviceMessage);
        }
        if (status >= 300 && status < 400) {
            headers.firstValue("Location").ifPresent(l -> message.append(" (a redirect to ").append(l).append(")"));
        }
        return new CliFailure(message.toString(), CliFailure.FAILED);
    }

    /** {@code error} and {@code error_description} of a bearer challenge; never anything else. */
    private static String authenticateError(String challenge) {
        Matcher m = AUTH_PARAM.matcher(challenge);
        String error = "";
        String description = "";
        while (m.find()) {
            if (m.group(1).equals("error")) {
                error = m.group(2);
            } else {
                description = m.group(2);
            }
        }
        return error.isEmpty() ? description : description.isEmpty() ? error : error + ", " + description;
    }

    /**
     * The service's own words: {@code message} (what qits services answer), {@code errors[].message},
     * or a validation answer's {@code violations[].message}. A body that is not JSON (an HTML page
     * from a proxy) gives nothing; it is not quoted.
     */
    static String serviceMessage(String body) {
        JsonNode node;
        try {
            node = JSON.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (IOException notJson) {
            return "";
        }
        if (node == null || !node.isObject()) {
            return "";
        }
        List<String> messages = new ArrayList<>();
        if (node.path("message").isTextual()) {
            messages.add(node.get("message").asText());
        }
        for (String list : List.of("errors", "violations")) {
            for (JsonNode entry : node.path(list)) {
                String text = entry.path("message").asText("");
                String field = entry.path("field").asText("");
                if (!text.isEmpty()) {
                    messages.add(field.isEmpty() ? text : field + ": " + text);
                }
            }
        }
        if (messages.isEmpty() && node.path("title").isTextual()) {
            messages.add(node.get("title").asText());
        }
        return String.join("; ", messages);
    }

    private static HttpClient newClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    static String describe(Throwable e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : " (" + message + ")");
    }
}
