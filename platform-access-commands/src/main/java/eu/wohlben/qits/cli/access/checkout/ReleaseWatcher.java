package eu.wohlben.qits.cli.access.checkout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cli.access.daemon.Sleeper;
import eu.wohlben.qits.cli.access.events.SseParser;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Follows one repository's {@code SCMRelease} events and hands each one to the checkout.
 * <p>
 * <b>A sibling of {@code EventStream}, not a reuse of it.</b> The reconnect mechanics are the same
 * ones — 1, 2, 4 … up to 30 seconds, reset on connect; a stream that is silent for
 * {@link #IDLE_LIMIT} counts as dropped; a 4xx ends the command; stopping aborts the connection and
 * never the thread — but what happens to a frame is not: {@code qits events} prints it, and this
 * runs Git. Bending {@code EventStream} into both shapes would have cost it the one thing it is,
 * so the mechanics are kept side by side and the SSE parser is shared.
 * <p>
 * <b>The stream has no replay, so it is not enough on its own.</b> A release cut while this was
 * stopped, or while it was reconnecting, never arrives as a frame. So the newest release is read
 * from qits-events' query API at startup and again after every connect — with the stream already
 * open, so nothing can fall between the two — and the version last acted on is remembered, so the
 * frame that follows a reconcile does not run Git a second time.
 * <p>
 * Notes go to stderr, one line each with a time. Never a token.
 */
public final class ReleaseWatcher {

    /** What qits-events calls a release of a repository. */
    public static final String EVENT = "SCMRelease";

    /** Three keepalives missed. */
    public static final Duration IDLE_LIMIT = Duration.ofSeconds(60);
    static final Duration FIRST_BACKOFF = Duration.ofSeconds(1);
    static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** What brings the checkout to a release. Answers whether it moved anything. */
    @FunctionalInterface
    public interface Hold {
        boolean hold(Release release) throws CliFailure, InterruptedException;
    }

    private final PlatformClient client;
    private final URI stream;
    private final URI latest;
    private final String repoName;
    private final String projectId;
    private final Hold hold;
    private final PrintStream out;
    private final Notes log;
    private final Sleeper sleeper;
    private final Duration idleLimit;

    private volatile boolean stopRequested;
    private volatile PlatformClient.Connection current;
    private boolean outputClosed;
    private String lastVersion;

    ReleaseWatcher(PlatformClient client, URI stream, URI latest, String repoName, String projectId, Hold hold,
                   PrintStream out, Notes log, Sleeper sleeper, Duration idleLimit) {
        this.client = client;
        this.stream = stream;
        this.latest = latest;
        this.repoName = repoName;
        this.projectId = projectId;
        this.hold = hold;
        this.out = out;
        this.log = log;
        this.sleeper = sleeper;
        this.idleLimit = idleLimit;
    }

    /** Ends the watch from any thread. {@link #run()} then returns 0. */
    public void stop() {
        stopRequested = true;
        sleeper.wake();
        PlatformClient.Connection connection = current;
        if (connection != null) {
            connection.abort();
        }
    }

    /**
     * {@code --once}: the newest release, then done. A refusal is this command's exit code — a
     * checkout with local changes ends the run rather than being logged and waited out.
     */
    public int once() throws CliFailure, InterruptedException {
        Release release = newest();
        if (release == null) {
            return 0;
        }
        apply(release);
        return 0;
    }

    /** 0 after a stop or when stdout closes; the failure's exit code when the platform refuses. */
    public int run() throws InterruptedException {
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(
                r -> Thread.ofPlatform().daemon().name("qits-checkout-watchdog").unstarted(r));
        try {
            return loop(watchdog);
        } finally {
            watchdog.shutdownNow();
        }
    }

    private int loop(ScheduledExecutorService watchdog) throws InterruptedException {
        // Whatever was released while this was not running, before the first connect: a stream that
        // cannot be opened at all must not leave the checkout behind as well.
        try {
            reconcile();
        } catch (CliFailure failure) {
            if (!failure.retryable()) {
                log(failure.getMessage());
                return failure.exitCode();
            }
            log(failure.getMessage());
        }
        Duration backoff = FIRST_BACKOFF;
        boolean wasConnected = false;
        while (!stopRequested) {
            String trouble;
            try {
                PlatformClient.Connection connection = client.openStream(stream);
                current = connection;
                if (stopRequested) {
                    connection.close();
                    break;
                }
                log((wasConnected ? "connected again to " : "connected to ") + stream);
                wasConnected = true;
                backoff = FIRST_BACKOFF;
                reconcile();
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
                    + (wasConnected ? " — a release in the gap is read back when it connects again" : ""));
            sleeper.sleep(backoff);
            backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff.multipliedBy(2);
        }
        log(outputClosed ? "stdout is closed; stopped" : "stopped");
        return 0;
    }

    /** Reads until the stream ends. Answers why it ended, or null for a stop or a closed stdout. */
    private String read(PlatformClient.Connection connection, ScheduledExecutorService watchdog)
            throws InterruptedException {
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
                Optional<SseParser.Event> event = parser.accept(line);
                if (event.isPresent()) {
                    frame(event.get().data());
                }
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
                return "the stream sent nothing, not even a keepalive, for " + idleLimit.toSeconds() + " s";
            }
            return "the stream dropped (" + describe(dropped.getCause()) + ")";
        } finally {
            watch.cancel(false);
            connection.close();
        }
    }

    /** One event from the stream. Anything but this repository's release is not this command's. */
    private void frame(String data) throws InterruptedException {
        JsonNode envelope = parse(data);
        if (envelope == null || !envelope.isObject()) {
            return;
        }
        if (envelope.path("name").isTextual() && !envelope.get("name").asText().equals(EVENT)) {
            return;
        }
        Release release = match(payload(envelope));
        if (release == null) {
            return;
        }
        try {
            apply(release);
        } catch (CliFailure refused) {
            // A refusal is about this release, not about the watch: it says so and keeps following.
            log(refused.getMessage());
        }
    }

    /** The newest release of this repository, read back from the query API. Null when there is none. */
    private Release newest() throws CliFailure, InterruptedException {
        JsonNode answer = client.get(latest);
        JsonNode events = answer.path("events");
        if (!events.isArray() || events.isEmpty()) {
            log("nothing is released yet for " + SafeText.line(repoName) + "; the checkout was not touched");
            return null;
        }
        Release release = match(payload(events.get(0)));
        if (release == null) {
            log("the newest " + EVENT + " does not name " + SafeText.line(repoName)
                    + "; the checkout was not touched");
        }
        return release;
    }

    /** The newest release, held. A refusal is a note: the next release, or the next connect, tries again. */
    private void reconcile() throws CliFailure, InterruptedException {
        Release release = newest();
        if (release == null) {
            return;
        }
        try {
            apply(release);
        } catch (CliFailure refused) {
            log(refused.getMessage());
        }
    }

    /**
     * Holds the checkout at this release, unless it is the one last held: a reconcile after a
     * reconnect and the frame that follows it name the same release, and Git is not run twice for
     * it. A release that was refused is not remembered, so the next frame tries it again.
     */
    private void apply(Release release) throws CliFailure, InterruptedException {
        if (release.version().equals(lastVersion)) {
            return;
        }
        if (hold.hold(release)) {
            out.println(SafeText.line(release.version()));
            out.flush();
        }
        lastVersion = release.version();
    }

    /**
     * The release an event names, or null when it names another repository. The payload says
     * {@code repositoryName}; some producers say {@code repository} instead, and {@code commitSha}
     * may be missing altogether, which the tag makes up for.
     */
    Release match(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            return null;
        }
        JsonNode named = payload.get("repositoryName");
        String name = named != null && named.isTextual() ? named.asText()
                : named == null || named.isNull() ? text(payload, "repository") : null;
        if (!repoName.equals(name)) {
            return null;
        }
        if (projectId != null && !projectId.equals(text(payload, "projectId"))) {
            return null;
        }
        String version = text(payload, "version");
        if (version == null || version.isBlank()) {
            return null;
        }
        return new Release(version.strip(), text(payload, "commitSha"));
    }

    /** An event's payload, which travels as a JSON string and has to be read a second time. */
    private static JsonNode payload(JsonNode envelope) {
        JsonNode payload = envelope.path("payload");
        return payload.isTextual() ? parse(payload.asText()) : payload;
    }

    private static JsonNode parse(String text) {
        try {
            JsonNode node = JSON.readTree(text);
            return node == null || node.isMissingNode() ? null : node;
        } catch (IOException notJson) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static String describe(Throwable e) {
        if (e == null) {
            return "no detail";
        }
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private void log(String message) {
        log.accept(message);
    }
}
