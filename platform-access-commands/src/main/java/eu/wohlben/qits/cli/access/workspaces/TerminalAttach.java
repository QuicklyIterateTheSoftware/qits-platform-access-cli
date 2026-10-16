package eu.wohlben.qits.cli.access.workspaces;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.daemon.Sleeper;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One attachment to a command's terminal on a slot's daemon: {@code WS /terminal/commands/{id}},
 * through qits-workspaces' container proxy, the socket the landing app's Terminal tab opens
 * ({@code TerminalSocket} and {@code ReconnectingSocket} there, whose rules this keeps).
 * <p>
 * <b>The wire.</b> Keystrokes go out as {@code {"type":"data","data":…}}, the window size as
 * {@code {"type":"resize","cols":…,"rows":…}}, sent on every open and again when the window changes.
 * The daemon answers with raw terminal text, and replays up to 256 KB of scrollback on each attach;
 * a reconnect therefore clears the screen first, as the page does.
 * <p>
 * <b>Closing only detaches.</b> The command outlives this socket. A clean close from the server
 * (1000) is final: the command ended, or was gone before the attach (the daemon then says so in the
 * terminal and closes). Any other end reconnects after 0.5, 1, 2, 4 and 4 seconds, counted from the
 * last open, and then gives up. A refused upgrade other than a 5xx ends at once.
 * <p>
 * <b>Read-only without a terminal.</b> When stdin is no person's terminal the output is only
 * streamed: no raw mode, no keystroke and no resize goes out (a size from a pipe would shrink the
 * person's window), and a clean close still ends it with 0.
 * <p>
 * <b>Ctrl-] detaches</b> (0x1d, telnet's and docker's escape): every key before it in the same read
 * still goes out. The terminal is restored on every way out of {@link #run()}; SIGINT and SIGTERM
 * ({@link #stop()}) leave the same way, and {@link SttyTerminal}'s shutdown hook covers the rest.
 */
final class TerminalAttach {

    /** The landing app's {@code RECONNECT_BACKOFF_MS}. */
    static final List<Duration> BACKOFF = List.of(Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2),
            Duration.ofSeconds(4), Duration.ofSeconds(4));
    /** Ctrl-]. */
    static final char DETACH = 0x1d;
    /** How often the size is read besides SIGWINCH. */
    static final Duration SIZE_POLL = Duration.ofSeconds(2);
    /** The landing app's default size, for a terminal that cannot tell its own. */
    static final LocalTerminal.Size DEFAULT_SIZE = new LocalTerminal.Size(100, 30);
    /** Home, erase the screen, erase the scrollback: what xterm's reset does to the page's screen. */
    static final String CLEAR = "\u001b[H\u001b[2J\u001b[3J";
    static final int CLEAN_CLOSE = 1000;
    static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);
    /** Far above any replay (256 KB of bytes); a larger message is a broken or hostile stream. */
    static final int MAX_MESSAGE_CHARS = 8 * 1024 * 1024;

    /** What the socket, the keyboard and the window hand to the loop. */
    private sealed interface Inbound {
    }

    private record Output(String text) implements Inbound {
    }

    private record Keys(String text) implements Inbound {
    }

    private record Resized() implements Inbound {
    }

    private record Closed(int code, String reason) implements Inbound {
    }

    private record Failed(Throwable error) implements Inbound {
    }

    private record Stop() implements Inbound {
    }

    private static final Inbound RESIZED = new Resized();
    private static final Inbound STOP = new Stop();

    /** How one connection ended. */
    private enum Ending { ENDED, DROPPED, LEFT }

    private final PlatformClient client;
    private final URI uri;
    private final String commandId;
    private final LocalTerminal terminal;
    private final PrintStream out;
    private final PrintStream log;
    private final Sleeper sleeper;
    private final List<Duration> backoff;
    private final Duration sizePoll;

    private volatile boolean detached;
    private volatile boolean stopped;
    private volatile boolean raw;
    private volatile BlockingQueue<Inbound> inbox;
    private volatile PlatformClient.Socket current;
    private volatile WebSocket live;
    private boolean outputClosed;
    private String trouble;
    private LocalTerminal.Size sent;

    TerminalAttach(PlatformClient client, URI uri, String commandId, LocalTerminal terminal, PrintStream out,
                   PrintStream log, Sleeper sleeper, List<Duration> backoff, Duration sizePoll) {
        this.client = client;
        this.uri = uri;
        this.commandId = commandId;
        this.terminal = terminal;
        this.out = out;
        this.log = log;
        this.sleeper = sleeper;
        this.backoff = List.copyOf(backoff);
        this.sizePoll = sizePoll;
    }

    /** Ends the attachment from any thread; the command keeps running. {@link #run()} then returns 0. */
    void stop() {
        stopped = true;
        leave();
    }

    /** Ctrl-]: the same as {@link #stop()}, said differently. */
    private void detach() {
        detached = true;
        leave();
    }

    private void leave() {
        sleeper.wake();
        BlockingQueue<Inbound> queue = inbox;
        if (queue != null) {
            queue.add(STOP);
        }
        // An open socket is closed by the loop, politely; a handshake that hangs is cut here.
        PlatformClient.Socket socket = current;
        if (socket != null && live == null) {
            socket.abort();
        }
    }

    /**
     * 0 when the command ended, on a detach, a stop, or when stdout closed; 1 when the reconnects ran
     * out or the platform refused; 2 for a refusal the person has to act on (the session ended).
     */
    int run() throws InterruptedException {
        boolean interactive = terminal.interactive();
        try {
            if (interactive) {
                terminal.enterRaw();
                raw = true;
                terminal.onResize(this::resized);
                Thread keyboard = Thread.ofPlatform().daemon().name("qits-attach-keys").unstarted(this::readKeys);
                keyboard.start();
                log("attached to command " + SafeText.line(commandId) + "; Ctrl-] detaches");
            }
            return loop(interactive);
        } catch (CliFailure failure) {
            restore();
            log(failure.getMessage());
            return failure.exitCode();
        } finally {
            restore();
        }
    }

    private int loop(boolean interactive) throws InterruptedException {
        int attempt = 0;
        boolean everOpen = false;
        while (true) {
            Ending ending;
            BlockingQueue<Inbound> queue = new LinkedBlockingQueue<>();
            inbox = queue;
            PlatformClient.Socket socket = null;
            try {
                if (leaving()) {
                    return left();
                }
                socket = client.openSocket(uri, new Listener(queue));
                current = socket;
                WebSocket webSocket = socket.await();
                live = webSocket;
                if (everOpen) {
                    if (interactive) {
                        write(CLEAR);
                    } else {
                        log("attached again; the daemon replays what the terminal holds");
                    }
                }
                everOpen = true;
                attempt = 0;
                ending = attached(webSocket, queue, interactive);
            } catch (CliFailure failure) {
                if (leaving()) {
                    return left();
                }
                if (!failure.retryable()) {
                    restore();
                    log(failure.getMessage());
                    return failure.exitCode();
                }
                trouble = failure.getMessage();
                ending = Ending.DROPPED;
            } finally {
                inbox = null;
                live = null;
                current = null;
                if (socket != null) {
                    socket.abort();
                }
            }
            switch (ending) {
                case ENDED -> {
                    restore();
                    log("the command's terminal closed: the command ended");
                    return 0;
                }
                case LEFT -> {
                    return left();
                }
                case DROPPED -> {
                    if (attempt >= backoff.size()) {
                        restore();
                        log(trouble + "; gave up after " + backoff.size() + " attempts. The command may still run: "
                                + "attach again");
                        return CliFailure.FAILED;
                    }
                    Duration wait = backoff.get(attempt++);
                    log(trouble + "; attaching again in " + seconds(wait));
                    sleeper.sleep(wait);
                }
            }
        }
    }

    /** Relays one open connection until it ends. */
    private Ending attached(WebSocket webSocket, BlockingQueue<Inbound> queue, boolean interactive)
            throws InterruptedException {
        sent = null;
        if (interactive && !send(webSocket, resizeFrame(size()))) {
            return Ending.DROPPED;
        }
        webSocket.request(1);
        long nextPoll = System.nanoTime() + sizePoll.toNanos();
        while (true) {
            long wait = interactive ? Math.max(0, nextPoll - System.nanoTime()) : Long.MAX_VALUE;
            Inbound inbound = queue.poll(wait, TimeUnit.NANOSECONDS);
            if (inbound == null) {
                nextPoll = System.nanoTime() + sizePoll.toNanos();
                inbound = RESIZED;
            }
            switch (inbound) {
                case Output output -> {
                    write(output.text());
                    if (outputClosed) {
                        close(webSocket);
                        return Ending.LEFT;
                    }
                    webSocket.request(1);
                }
                case Keys keys -> {
                    if (!send(webSocket, dataFrame(keys.text()))) {
                        return Ending.DROPPED;
                    }
                }
                case Resized ignored -> {
                    LocalTerminal.Size now = size();
                    if (interactive && !now.equals(sent) && !send(webSocket, resizeFrame(now))) {
                        return Ending.DROPPED;
                    }
                }
                case Closed closed -> {
                    if (closed.code() == CLEAN_CLOSE) {
                        return Ending.ENDED;
                    }
                    String reason = SafeText.line(closed.reason()).strip();
                    trouble = "the socket closed (code " + closed.code() + (reason.isEmpty() ? "" : ": " + reason) + ")";
                    return Ending.DROPPED;
                }
                case Failed failed -> {
                    if (leaving()) {
                        return Ending.LEFT;
                    }
                    trouble = "the socket dropped (" + describe(failed.error()) + ")";
                    return Ending.DROPPED;
                }
                case Stop ignored -> {
                    close(webSocket);
                    return Ending.LEFT;
                }
            }
        }
    }

    private boolean leaving() {
        return detached || stopped || outputClosed;
    }

    private int left() {
        restore();
        if (detached) {
            log("detached; the command keeps running");
        } else if (stopped) {
            log("stopped; the command keeps running");
        } else {
            log("stdout is closed; detached, the command keeps running");
        }
        return 0;
    }

    /** The window's size, else the landing app's default. Sent once per change. */
    private LocalTerminal.Size size() {
        LocalTerminal.Size size = terminal.size();
        return size == null ? DEFAULT_SIZE : size;
    }

    private boolean send(WebSocket webSocket, String frame) throws InterruptedException {
        try {
            webSocket.sendText(frame, true).get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (ExecutionException | TimeoutException | IllegalStateException e) {
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            trouble = "cannot write to the socket (" + describe(cause) + ")";
            return false;
        }
    }

    private boolean send(WebSocket webSocket, ResizeFrame frame) throws InterruptedException {
        boolean done = send(webSocket, frame.json());
        if (done) {
            sent = frame.size();
        }
        return done;
    }

    /** A detach is a close, never a terminate: the daemon only drops this sink. */
    private static void close(WebSocket webSocket) {
        try {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "detached").get(1, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException | IllegalStateException e) {
            // Aborted below either way.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    record ResizeFrame(LocalTerminal.Size size, String json) {
    }

    /** {@code {"type":"resize","cols":…,"rows":…}}. */
    static ResizeFrame resizeFrame(LocalTerminal.Size size) {
        ObjectNode frame = JsonNodeFactory.instance.objectNode();
        frame.put("type", "resize");
        frame.put("cols", size.cols());
        frame.put("rows", size.rows());
        return new ResizeFrame(size, frame.toString());
    }

    /** {@code {"type":"data","data":…}}: the keystrokes as typed. */
    static String dataFrame(String keys) {
        ObjectNode frame = JsonNodeFactory.instance.objectNode();
        frame.put("type", "data");
        frame.put("data", keys);
        return frame.toString();
    }

    private void write(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.write(bytes, 0, bytes.length);
        out.flush();
        if (out.checkError()) {
            outputClosed = true;
        }
    }

    private void resized() {
        BlockingQueue<Inbound> queue = inbox;
        if (queue != null) {
            queue.add(RESIZED);
        }
    }

    /**
     * The keyboard, on its own thread: what each read gives goes out as one data frame, up to a
     * Ctrl-], which detaches. Keys typed between two connections are dropped, as the page drops
     * them: a terminal has no send queue.
     */
    private void readKeys() {
        char[] buffer = new char[4096];
        try (Reader keys = new InputStreamReader(terminal.keys(), StandardCharsets.UTF_8)) {
            int read;
            while (!leaving() && (read = keys.read(buffer)) >= 0) {
                String typed = new String(buffer, 0, read);
                int escape = typed.indexOf(DETACH);
                String before = escape < 0 ? typed : typed.substring(0, escape);
                BlockingQueue<Inbound> queue = inbox;
                if (!before.isEmpty() && queue != null) {
                    queue.add(new Keys(before));
                }
                if (escape >= 0) {
                    detach();
                    return;
                }
            }
        } catch (IOException gone) {
            // The terminal went away: the socket still streams until it ends or is stopped.
        }
    }

    private void restore() {
        if (raw) {
            raw = false;
            terminal.restore();
        }
    }

    private void log(String message) {
        // In raw mode a bare line feed only moves down; the carriage return goes back to the margin.
        log.print("qits: " + message + (raw ? "\r\n" : System.lineSeparator()));
        log.flush();
    }

    private static String describe(Throwable e) {
        if (e == null) {
            return "no detail";
        }
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + SafeText.line(message));
    }

    private static String seconds(Duration duration) {
        return duration.toMillis() % 1000 == 0 ? duration.toSeconds() + " s" : duration.toMillis() / 1000.0 + " s";
    }

    /**
     * Hands what the socket delivers to the loop's queue, a whole message at a time: a message that
     * arrives in parts ({@code last=false}) is put back together first.
     */
    private static final class Listener implements WebSocket.Listener {
        private final BlockingQueue<Inbound> queue;
        private final StringBuilder message = new StringBuilder();

        Listener(BlockingQueue<Inbound> queue) {
            this.queue = queue;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            // Nothing is asked for yet: the loop asks once the size is out.
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
                queue.add(new Output(message.toString()));
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
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
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
