package eu.wohlben.qits.cli.access.events;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import eu.wohlben.qits.cli.access.daemon.Sleeper;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.session.Times;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * qits-events' live stream ({@code GET /events/api/stream?names=…}), printed as one JSON object per
 * line on stdout, each flushed, so {@code | jq} sees an event when it arrives.
 * <p>
 * <b>Live only.</b> The stream has no replay: whatever happens while it is not connected is gone.
 * So a dropped connection or a 5xx is followed by a reconnect, waiting 1, 2, 4 … up to 30 seconds,
 * and stderr says that events in the gap are missed. A 4xx ends the command: trying again cannot
 * help.
 * <p>
 * <b>A silent stream counts as dropped.</b> The service sends a comment every 20 seconds. After a
 * suspend a TCP connection can stay open on this side and be gone on the other, so no line for
 * {@link #IDLE_LIMIT} ends the connection and a reconnect follows.
 * <p>
 * <b>Stopping</b> ({@link #stop()}, from SIGINT or SIGTERM) aborts the open connection, never the
 * thread: an inline session refresh in progress still writes its answer.
 * <p>
 * Notes go to stderr, one line each with a time. Never a token.
 */
public final class EventStream {

    /** Three keepalives missed. */
    public static final Duration IDLE_LIMIT = Duration.ofSeconds(60);
    static final Duration FIRST_BACKOFF = Duration.ofSeconds(1);
    static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final PlatformClient client;
    private final URI uri;
    private final PrintStream out;
    private final PrintStream log;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Duration idleLimit;

    private volatile boolean stopRequested;
    private volatile PlatformClient.Connection current;
    private boolean outputClosed;

    public EventStream(PlatformClient client, URI uri, PrintStream out, PrintStream log, Clock clock, Sleeper sleeper,
                       Duration idleLimit) {
        this.client = client;
        this.uri = uri;
        this.out = out;
        this.log = log;
        this.clock = clock;
        this.sleeper = sleeper;
        this.idleLimit = idleLimit;
    }

    /** Ends the stream from any thread. {@link #run()} then returns 0. */
    public void stop() {
        stopRequested = true;
        sleeper.wake();
        PlatformClient.Connection connection = current;
        if (connection != null) {
            connection.abort();
        }
    }

    /** 0 after a stop or when stdout closes; the failure's exit code when the platform refuses. */
    public int run() throws InterruptedException {
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(
                r -> Thread.ofPlatform().daemon().name("qits-events-watchdog").unstarted(r));
        try {
            return loop(watchdog);
        } finally {
            watchdog.shutdownNow();
        }
    }

    private int loop(ScheduledExecutorService watchdog) throws InterruptedException {
        Duration backoff = FIRST_BACKOFF;
        boolean wasConnected = false;
        while (!stopRequested) {
            String trouble;
            try {
                PlatformClient.Connection connection = client.openStream(uri);
                current = connection;
                if (stopRequested) {
                    connection.close();
                    break;
                }
                log((wasConnected ? "connected again to " : "connected to ") + uri);
                wasConnected = true;
                backoff = FIRST_BACKOFF;
                trouble = read(connection, watchdog);
            } catch (CliFailure failure) {
                if (!failure.retryable()) {
                    log(failure.getMessage());
                    return failure.exitCode();
                }
                trouble = failure.getMessage();
            } finally {
                current = null;
            }
            if (stopRequested || outputClosed) {
                break;
            }
            log(trouble + "; reconnecting in " + backoff.toSeconds() + " s"
                    + (wasConnected ? " — events in the gap are missed (the stream has no replay)" : ""));
            sleeper.sleep(backoff);
            backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff.multipliedBy(2);
        }
        log(outputClosed ? "stdout is closed; stopped" : "stopped");
        return 0;
    }

    /** Reads until the stream ends. Answers why it ended, or null for a stop or a closed stdout. */
    private String read(PlatformClient.Connection connection, ScheduledExecutorService watchdog) {
        SseParser parser = new SseParser();
        AtomicLong lastLine = new AtomicLong(System.nanoTime());
        AtomicBoolean idle = new AtomicBoolean();
        long period = Math.max(TimeUnit.MILLISECONDS.toNanos(50), idleLimit.toNanos() / 4);
        ScheduledFuture<?> watch = watchdog.scheduleAtFixedRate(() -> {
            if (System.nanoTime() - lastLine.get() > idleLimit.toNanos() && idle.compareAndSet(false, true)) {
                connection.abort();
            }
        }, period, period, TimeUnit.NANOSECONDS);
        try {
            Iterator<String> lines = connection.lines().iterator();
            while (!stopRequested && lines.hasNext()) {
                String line = lines.next();
                lastLine.set(System.nanoTime());
                parser.accept(line).ifPresent(event -> print(event.data()));
                if (out.checkError()) {
                    outputClosed = true;
                    return null;
                }
            }
            return stopRequested ? null : "the stream ended";
        } catch (UncheckedIOException dropped) {
            if (stopRequested) {
                return null;
            }
            if (idle.get()) {
                return "the stream sent nothing, not even a keepalive, for " + seconds(idleLimit);
            }
            return "the stream dropped (" + describe(dropped.getCause()) + ")";
        } finally {
            watch.cancel(false);
            connection.close();
        }
    }

    /** One line: the event, with its payload string read as JSON when it is JSON. */
    void print(String data) {
        JsonNode line;
        try {
            JsonNode parsed = JSON.readTree(data);
            if (parsed instanceof ObjectNode event) {
                JsonNode payload = event.get("payload");
                if (payload != null && payload.isTextual()) {
                    JsonNode inner = parse(payload.asText());
                    if (inner != null) {
                        event.set("payload", inner);
                    }
                }
                line = event;
            } else {
                line = TextNode.valueOf(data);
            }
        } catch (IOException notJson) {
            // Printed as a JSON string, so every line stays JSON for a reader like jq.
            line = TextNode.valueOf(data);
        }
        try {
            out.println(JSON.writeValueAsString(line));
        } catch (IOException impossible) {
            out.println("\"\"");
        }
        out.flush();
    }

    private static JsonNode parse(String text) {
        try {
            JsonNode node = JSON.readTree(text);
            return node == null || node.isMissingNode() ? null : node;
        } catch (IOException notJson) {
            return null;
        }
    }

    private static String seconds(Duration duration) {
        return duration.toSeconds() > 0 ? duration.toSeconds() + " s" : duration.toMillis() + " ms";
    }

    private static String describe(Throwable e) {
        if (e == null) {
            return "no detail";
        }
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private void log(String message) {
        log.println(Times.stamp(clock.instant()) + " qits events: " + message);
        log.flush();
    }
}
