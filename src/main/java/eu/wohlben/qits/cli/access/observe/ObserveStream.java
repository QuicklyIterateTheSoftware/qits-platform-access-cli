package eu.wohlben.qits.cli.access.observe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cli.access.daemon.Sleeper;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.session.Times;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * qits-observability's live stream ({@code /observability/stream}, a WebSocket): the filters go out
 * as one subscribe frame, and each matching record comes back as one frame, printed as a line (or
 * as its JSON with {@code -o json}) and flushed.
 * <p>
 * <b>Live only.</b> The stream has no replay. A dropped socket or a 5xx is followed by a reconnect,
 * waiting 1, 2, 4 … up to 30 seconds, with the filters sent again, and stderr says that records in
 * the gap are missed. A 4xx ends the command. The token is read before each connection, so one
 * that is about to expire is refreshed first (through {@code SessionRefresh}).
 * <p>
 * <b>A silent socket counts as dropped.</b> This side pings every {@link #PING_EVERY}; the server's
 * pongs, its own pings and its frames all count as heard. Nothing heard for {@link #IDLE_LIMIT}
 * ends the connection: after a suspend a socket can stay open on this side and be gone on the other.
 * <p>
 * <b>Back-pressure.</b> The next frame is asked for only once the last one is printed, so a slow
 * terminal makes the server's queue fill, and the server sends {@code {"dropped": N}}.
 * <p>
 * <b>Stopping</b> ({@link #stop()}, from SIGINT or SIGTERM) aborts the socket and wakes the loop,
 * never interrupting the thread: an inline session refresh in progress still writes its answer.
 * <p>
 * Notes go to stderr, one line each with a time. Never a token.
 */
public final class ObserveStream {

    /** Three of this side's pings gone unanswered. */
    public static final Duration IDLE_LIMIT = Duration.ofSeconds(60);
    public static final Duration PING_EVERY = Duration.ofSeconds(20);
    static final Duration FIRST_BACKOFF = Duration.ofSeconds(1);
    static final Duration MAX_BACKOFF = Duration.ofSeconds(30);
    static final Duration SEND_TIMEOUT = Duration.ofSeconds(30);
    /** Far above any record; a larger message is a broken or hostile stream. */
    static final int MAX_MESSAGE_CHARS = 8 * 1024 * 1024;

    private static final byte[] PING = "qits".getBytes(StandardCharsets.US_ASCII);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** What the socket's listener hands to the loop. */
    private sealed interface Inbound {
    }

    private record Text(String text) implements Inbound {
    }

    /** A ping or a pong: the other side is there. */
    private record Heard() implements Inbound {
    }

    private record Closed(int code, String reason) implements Inbound {
    }

    private record Failed(Throwable error) implements Inbound {
    }

    private record Stop() implements Inbound {
    }

    private static final Inbound HEARD = new Heard();
    private static final Inbound STOP = new Stop();

    /** Why a connection ended (null for a stop), and whether anything came over it. */
    private record Ending(String trouble, boolean heard) {
    }

    private final PlatformClient client;
    private final URI uri;
    private final String subscribeFrame;
    private final boolean json;
    private final ZoneId zone;
    private final PrintStream out;
    private final PrintStream log;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Duration idleLimit;
    private final Duration pingEvery;

    private volatile boolean stopRequested;
    private volatile PlatformClient.Socket current;
    private volatile BlockingQueue<Inbound> inbox;
    private boolean outputClosed;
    private CliFailure refused;
    /** Records the server dropped since this command started, over every connection. */
    private long dropped;

    public ObserveStream(PlatformClient client, URI uri, String subscribeFrame, boolean json, ZoneId zone,
                         PrintStream out, PrintStream log, Clock clock, Sleeper sleeper, Duration idleLimit,
                         Duration pingEvery) {
        this.client = client;
        this.uri = uri;
        this.subscribeFrame = subscribeFrame;
        this.json = json;
        this.zone = zone;
        this.out = out;
        this.log = log;
        this.clock = clock;
        this.sleeper = sleeper;
        this.idleLimit = idleLimit;
        this.pingEvery = pingEvery;
    }

    /** Ends the stream from any thread. {@link #run()} then returns 0. */
    public void stop() {
        stopRequested = true;
        sleeper.wake();
        BlockingQueue<Inbound> queue = inbox;
        if (queue != null) {
            queue.add(STOP);
        }
        PlatformClient.Socket socket = current;
        if (socket != null) {
            socket.abort();
        }
    }

    /**
     * 0 after a stop or when stdout closes; 1 when the platform refuses the connection; 2 when the
     * service refuses the filters or the session has ended.
     */
    public int run() throws InterruptedException {
        Duration backoff = FIRST_BACKOFF;
        boolean wasConnected = false;
        while (!stopRequested) {
            String trouble;
            BlockingQueue<Inbound> queue = new LinkedBlockingQueue<>();
            inbox = queue;
            PlatformClient.Socket socket = null;
            try {
                socket = client.openSocket(uri, new Listener(queue));
                current = socket;
                if (stopRequested) {
                    break;
                }
                WebSocket webSocket = socket.await();
                subscribe(webSocket);
                log((wasConnected ? "connected again to " : "connected to ") + uri);
                wasConnected = true;
                Ending ending = read(webSocket, queue);
                if (ending.heard()) {
                    backoff = FIRST_BACKOFF;
                }
                trouble = ending.trouble();
            } catch (CliFailure failure) {
                if (stopRequested) {
                    break;
                }
                if (!failure.retryable()) {
                    log(failure.getMessage());
                    return failure.exitCode();
                }
                trouble = failure.getMessage();
            } finally {
                current = null;
                if (socket != null) {
                    socket.abort();
                }
            }
            if (refused != null) {
                log(refused.getMessage());
                return refused.exitCode();
            }
            if (stopRequested || outputClosed) {
                break;
            }
            log(trouble + "; reconnecting in " + backoff.toSeconds() + " s"
                    + (wasConnected ? " — records in the gap are missed (the stream has no replay)" : ""));
            sleeper.sleep(backoff);
            backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff.multipliedBy(2);
        }
        log(outputClosed ? "stdout is closed; stopped" : "stopped");
        return 0;
    }

    private void subscribe(WebSocket webSocket) throws CliFailure, InterruptedException {
        try {
            webSocket.sendText(subscribeFrame, true).get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException e) {
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            throw CliFailure.retryable("cannot send the filters to " + uri + " (" + describe(cause) + ")");
        }
    }

    /** Reads until the connection ends. */
    private Ending read(WebSocket webSocket, BlockingQueue<Inbound> queue) throws InterruptedException {
        boolean heard = false;
        boolean sawRecord = false;
        long lastHeard = System.nanoTime();
        long nextPing = lastHeard + pingEvery.toNanos();
        CompletableFuture<WebSocket> ping = CompletableFuture.completedFuture(webSocket);
        webSocket.request(1);
        while (!stopRequested) {
            long now = System.nanoTime();
            long idleAt = lastHeard + idleLimit.toNanos();
            if (now - idleAt >= 0) {
                return new Ending("the socket sent nothing, not even a keepalive, for " + seconds(idleLimit), heard);
            }
            if (now - nextPing >= 0) {
                // A pong proves a quiet socket alive, and the traffic keeps the edge from closing it.
                if (ping.isDone()) {
                    ping = sendPing(webSocket);
                }
                nextPing = now + pingEvery.toNanos();
                continue;
            }
            Inbound inbound = queue.poll(Math.min(idleAt, nextPing) - now, TimeUnit.NANOSECONDS);
            switch (inbound) {
                case null -> {
                }
                case Heard ignored -> {
                    heard = true;
                    lastHeard = System.nanoTime();
                }
                case Text text -> {
                    heard = true;
                    sawRecord |= handle(text.text(), sawRecord);
                    if (refused != null) {
                        return new Ending(null, true);
                    }
                    if (out.checkError()) {
                        outputClosed = true;
                        return new Ending(null, true);
                    }
                    // The wait counts from here: printing may have blocked on a slow terminal.
                    lastHeard = System.nanoTime();
                    webSocket.request(1);
                }
                case Closed closed -> {
                    return new Ending("the server closed the socket (" + describe(closed) + ")", heard);
                }
                case Failed failed -> {
                    return new Ending(stopRequested ? null : "the socket dropped (" + describe(failed.error()) + ")", heard);
                }
                case Stop ignored -> {
                    return new Ending(null, heard);
                }
            }
        }
        return new Ending(null, heard);
    }

    /** Prints a record, or says what a notice says. Answers whether the frame was a record. */
    private boolean handle(String text, boolean sawRecord) {
        JsonNode frame;
        try {
            frame = JSON.readTree(text);
        } catch (IOException notJson) {
            frame = null;
        }
        if (frame == null || !frame.isObject()) {
            log("the server sent a frame that is not a JSON object; it is skipped");
            return false;
        }
        if (frame.has("dropped")) {
            // One flood can bring several notices, one each time the server's queue runs empty.
            JsonNode count = frame.get("dropped");
            if (count.canConvertToLong()) {
                dropped += count.asLong();
                log("the server dropped " + count.asLong() + " records, because this side did not read them fast enough ("
                        + dropped + " in all)");
            } else {
                log("the server dropped records (" + SafeText.line(count.asText()) + ")");
            }
            return false;
        }
        if (frame.has("error")) {
            String reason = SafeText.line(frame.path("error").asText("")).strip();
            if (sawRecord) {
                log("the server says: " + reason);
            } else {
                // The subscription is the only frame this side sends, and records flow only once
                // the server has taken it. An error before the first record answers it.
                refused = new CliFailure("The observability service refused the filters: " + reason, CliFailure.USAGE);
            }
            return false;
        }
        if (!frame.has("record")) {
            // A notice this version does not know. Skipped, so the server can add notices.
            return false;
        }
        print(frame);
        return true;
    }

    private void print(JsonNode frame) {
        if (json) {
            try {
                out.println(SafeText.JSON.writeValueAsString(frame));
            } catch (IOException impossible) {
                out.println("{}");
            }
        } else {
            out.println(RecordLine.render(frame, zone));
        }
        out.flush();
    }

    private static CompletableFuture<WebSocket> sendPing(WebSocket webSocket) {
        try {
            return webSocket.sendPing(ByteBuffer.wrap(PING));
        } catch (IllegalStateException stillSending) {
            // A ping or pong is still going out. The next turn tries again.
            return CompletableFuture.completedFuture(webSocket);
        }
    }

    private static String describe(Closed closed) {
        String reason = SafeText.line(closed.reason()).strip();
        return "code " + closed.code() + (reason.isEmpty() ? "" : ": " + reason);
    }

    private static String describe(Throwable e) {
        if (e == null) {
            return "no detail";
        }
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + SafeText.line(message));
    }

    private static String seconds(Duration duration) {
        return duration.toSeconds() > 0 ? duration.toSeconds() + " s" : duration.toMillis() + " ms";
    }

    private void log(String message) {
        log.println(Times.stamp(clock.instant()) + " qits observe: " + message);
        log.flush();
    }

    /**
     * Hands what the socket delivers to the loop's queue. It asks for more itself only for the
     * rest of a message, and after a ping or a pong; the loop asks for the next message once it
     * has printed the last one.
     */
    private static final class Listener implements WebSocket.Listener {
        private final BlockingQueue<Inbound> queue;
        private final StringBuilder message = new StringBuilder();

        Listener(BlockingQueue<Inbound> queue) {
            this.queue = queue;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            // Nothing is asked for yet: the loop asks once the filters are out.
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (message.length() + data.length() > MAX_MESSAGE_CHARS) {
                message.setLength(0);
                queue.add(new Failed(new IOException("a message longer than " + MAX_MESSAGE_CHARS + " characters")));
                webSocket.abort();
                return null;
            }
            message.append(data);
            if (last) {
                queue.add(new Text(message.toString()));
                message.setLength(0);
            } else {
                webSocket.request(1);
            }
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            // The WebSocket answers with a pong by itself.
            queue.add(HEARD);
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            queue.add(HEARD);
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            queue.add(new Closed(statusCode, reason));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            queue.add(new Failed(error));
        }
    }
}
