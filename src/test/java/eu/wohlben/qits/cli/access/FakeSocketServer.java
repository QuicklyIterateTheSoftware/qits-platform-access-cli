package eu.wohlben.qits.cli.access;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A WebSocket server in the test's own process, on a plain {@link ServerSocket}: the RFC 6455
 * opening handshake, and text, ping, pong and close frames. Each connection follows a script, one
 * script per connection in order. It records the path and the Authorization header of every upgrade
 * and every text frame a client sends, so a test can check the bearer and the subscription without
 * any output showing them.
 * <p>
 * It uses nothing but the JDK, so the native proof can run it from {@code target/test-classes}.
 */
public final class FakeSocketServer implements AutoCloseable {

    private static final String ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int TEXT = 0x1;
    private static final int CLOSE = 0x8;
    private static final int PING = 0x9;
    private static final int PONG = 0xA;

    public record Upgrade(String path, String authorization) {
    }

    /** What one connection does. */
    @FunctionalInterface
    public interface Script {
        void run(Connection connection) throws Exception;
    }

    private record Frame(int opcode, byte[] payload) {
    }

    private final ServerSocket server;
    private final ExecutorService threads = Executors.newCachedThreadPool(r -> Thread.ofPlatform().daemon().unstarted(r));
    private final CountDownLatch closing = new CountDownLatch(1);
    public final List<Upgrade> upgrades = Collections.synchronizedList(new ArrayList<>());
    public final List<Connection> connections = Collections.synchronizedList(new ArrayList<>());
    public final BlockingDeque<Script> scripts = new LinkedBlockingDeque<>();

    public FakeSocketServer() throws IOException {
        this(0);
    }

    public FakeSocketServer(int port) throws IOException {
        server = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
        threads.submit(this::acceptAll);
    }

    public int port() {
        return server.getLocalPort();
    }

    /** The base URL, as QITS_OBSERVABILITY_URL takes it. */
    public String url() {
        return "http://127.0.0.1:" + port();
    }

    public URI socketUri() {
        return URI.create("ws://127.0.0.1:" + port() + "/observability/stream");
    }

    private void acceptAll() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                threads.submit(() -> serve(socket));
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            String requestLine = readLine(in);
            Map<String, String> headers = new HashMap<>();
            for (String line = readLine(in); line != null && !line.isEmpty(); line = readLine(in)) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).strip().toLowerCase(Locale.ROOT), line.substring(colon + 1).strip());
                }
            }
            String[] parts = requestLine == null ? new String[0] : requestLine.split(" ");
            upgrades.add(new Upgrade(parts.length > 1 ? parts[1] : "", headers.get("authorization")));
            Connection connection = new Connection(socket, in, out, headers.getOrDefault("sec-websocket-key", ""));
            connections.add(connection);
            Script script = scripts.poll();
            if (script == null) {
                connection.refuse(500, "{\"message\":\"the fake has no script left\"}");
            } else {
                script.run(connection);
            }
        } catch (Exception clientGone) {
            // The client went away, or the fake closed; nothing to answer.
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0 && b != '\n') {
            if (b != '\r') {
                line.write(b);
            }
        }
        return b < 0 && line.size() == 0 ? null : line.toString(StandardCharsets.ISO_8859_1);
    }

    /** One client connection, driven by its script. */
    public final class Connection {
        private final Socket socket;
        private final DataInputStream in;
        private final OutputStream out;
        private final String key;
        public final List<String> texts = Collections.synchronizedList(new ArrayList<>());
        public final AtomicInteger pings = new AtomicInteger();

        Connection(Socket socket, DataInputStream in, OutputStream out, String key) {
            this.socket = socket;
            this.in = in;
            this.out = out;
            this.key = key;
        }

        /** Answers the upgrade with this status instead of 101. */
        public void refuse(int status, String body, String... headerPairs) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            StringBuilder head = new StringBuilder("HTTP/1.1 " + status + " Refused\r\n");
            for (int i = 0; i + 1 < headerPairs.length; i += 2) {
                head.append(headerPairs[i]).append(": ").append(headerPairs[i + 1]).append("\r\n");
            }
            head.append("Content-Type: application/json\r\nContent-Length: ").append(bytes.length)
                    .append("\r\nConnection: close\r\n\r\n");
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(bytes);
            out.flush();
        }

        public void accept() throws Exception {
            String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + ACCEPT_GUID).getBytes(StandardCharsets.ISO_8859_1)));
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }

        /** Reads frames until a text frame comes, answering pings on the way. */
        public String awaitText() throws IOException {
            while (true) {
                Frame frame = readFrame();
                if (frame.opcode() == CLOSE) {
                    throw new EOFException("the client closed the socket before it sent text");
                }
                if (answer(frame)) {
                    return texts.getLast();
                }
            }
        }

        public void send(String text) throws IOException {
            write(TEXT, text.getBytes(StandardCharsets.UTF_8));
        }

        public void ping() throws IOException {
            write(PING, "fake".getBytes(StandardCharsets.US_ASCII));
        }

        /** Sends a close frame and waits (up to 5 s) for the client's. */
        public void close(int code, String reason) throws IOException {
            byte[] words = reason.getBytes(StandardCharsets.UTF_8);
            write(CLOSE, ByteBuffer.allocate(2 + words.length).putShort((short) code).put(words).array());
            socket.setSoTimeout(5000);
            try {
                while (readFrame().opcode() != CLOSE) {
                    // Frames after a close are not answered.
                }
            } catch (IOException gone) {
                // The client closed the TCP connection instead; that ends it too.
            }
        }

        /** Ends the TCP connection without a close frame. */
        public void drop() throws IOException {
            socket.close();
        }

        /** Reads and answers pings until the client goes or the fake closes. */
        public void hold() {
            try {
                while (true) {
                    Frame frame = readFrame();
                    if (frame.opcode() == CLOSE) {
                        write(CLOSE, frame.payload());
                        return;
                    }
                    answer(frame);
                }
            } catch (IOException gone) {
                // The client went away, or the fake closed.
            }
        }

        /** Keeps the connection open and reads nothing, so no ping is answered. */
        public void holdSilent() throws InterruptedException {
            closing.await();
        }

        private boolean answer(Frame frame) throws IOException {
            switch (frame.opcode()) {
                case TEXT -> {
                    texts.add(new String(frame.payload(), StandardCharsets.UTF_8));
                    return true;
                }
                case PING -> {
                    pings.incrementAndGet();
                    write(PONG, frame.payload());
                }
                default -> {
                    // Pongs and continuation frames: nothing to do.
                }
            }
            return false;
        }

        private Frame readFrame() throws IOException {
            int first = in.readUnsignedByte();
            int second = in.readUnsignedByte();
            long length = second & 0x7F;
            if (length == 126) {
                length = in.readUnsignedShort();
            } else if (length == 127) {
                length = in.readLong();
            }
            boolean masked = (second & 0x80) != 0;
            byte[] mask = new byte[4];
            if (masked) {
                in.readFully(mask);
            }
            byte[] payload = new byte[(int) length];
            in.readFully(payload);
            if (masked) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i % 4];
                }
            }
            return new Frame(first & 0x0F, payload);
        }

        private synchronized void write(int opcode, byte[] payload) throws IOException {
            out.write(0x80 | opcode);
            if (payload.length < 126) {
                out.write(payload.length);
            } else if (payload.length < 65536) {
                out.write(126);
                out.write(payload.length >>> 8);
                out.write(payload.length & 0xFF);
            } else {
                out.write(127);
                out.write(ByteBuffer.allocate(8).putLong(payload.length).array());
            }
            out.write(payload);
            out.flush();
        }
    }

    @Override
    public void close() {
        closing.countDown();
        try {
            server.close();
        } catch (IOException ignored) {
            // Closing anyway.
        }
        synchronized (connections) {
            for (Connection connection : connections) {
                try {
                    connection.socket.close();
                } catch (IOException ignored) {
                    // Closing anyway.
                }
            }
        }
        threads.shutdownNow();
    }
}
