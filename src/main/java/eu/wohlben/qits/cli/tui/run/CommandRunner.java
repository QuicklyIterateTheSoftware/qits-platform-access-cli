package eu.wohlben.qits.cli.tui.run;

import eu.wohlben.qits.cli.access.observe.SafeText;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs what the screen shows, by starting this same binary again.
 * <p>
 * <b>This is the decision that keeps the TUI generic.</b> Nothing here knows a command: the argv is
 * whatever the picker built, and the child is the ordinary {@code qits} a person would have typed.
 * A command that writes to {@code System.out}, holds global state or calls {@code System.exit} needs
 * no thought, because it is a separate process; stopping a stream is killing a child, not unwinding
 * a thread. The estate already shells {@code docker}, {@code git} and {@code /bin/stty} from its
 * binaries, so a child process is nothing new.
 * <p>
 * The child inherits the environment, so it signs in with the same session the TUI was started
 * with, and gets {@code QITS_TUI=0} so nothing it does tries to take the screen too.
 * <p>
 * A child's output is untrusted text — it holds CI step logs and telemetry that anyone may have
 * written — so every line goes through {@link SafeText#line}, exactly as {@code qits observe} and
 * {@code qits ci} do with theirs.
 */
public final class CommandRunner {

    /** What the output box keeps. A screen holds tens of lines; this holds a scroll of them. */
    static final int TAIL_LINES = 500;

    /** How long a stopped child is given to end before it is ended for it. */
    static final long GRACE_MILLIS = 500;

    /** The name of the variable that tells a child it must not paint. */
    public static final String TUI_ENV = "QITS_TUI";

    private final String executable;
    private final TailBuffer tail = new TailBuffer(TAIL_LINES);

    private volatile Process process;
    private volatile String title = "";
    private volatile boolean stopped;

    public CommandRunner() {
        this(self());
    }

    public CommandRunner(String executable) {
        this.executable = executable;
    }

    /**
     * This binary's own path. {@code /proc/self/exe} is the one that is right whatever the program
     * was invoked as, and it is Linux-only, which is the slice this CLI targets. Where {@code /proc}
     * is not mounted, {@link ProcessHandle}'s own answer is the fallback.
     */
    public static String self() {
        Path exe = Path.of("/proc/self/exe");
        if (Files.exists(exe)) {
            return exe.toString();
        }
        return ProcessHandle.current().info().command().orElse("qits");
    }

    /** Whether a child is running right now. */
    public boolean running() {
        Process current = process;
        return current != null && current.isAlive();
    }

    /** {@code running}, {@code streaming}, {@code exit 0}, or empty before the first run. */
    public String title() {
        return title;
    }

    public List<String> lines() {
        return tail.all();
    }

    /**
     * Start {@code argv} as a child of this process. A child already running is stopped first — one
     * output box, one child.
     *
     * @param stream whether the command runs until it is stopped, so the box says so and never
     *               waits for an exit code
     */
    public void start(List<String> argv, boolean stream) {
        start(argv, stream, List.of());
    }

    /**
     * @param secrets the values of the command's {@code interactive()} options, in the order it will
     *                ask for them. They go on the child's standard input, never in its argv.
     */
    public void start(List<String> argv, boolean stream, List<String> secrets) {
        stop();
        tail.clear();
        this.stopped = false;
        this.title = stream ? "streaming" : "running";
        ProcessBuilder builder = new ProcessBuilder(command(argv)).redirectErrorStream(true);
        builder.environment().put(TUI_ENV, "0");
        Process started;
        try {
            started = builder.start();
        } catch (IOException cannotStart) {
            this.process = null;
            this.title = "cannot start";
            tail.add(SafeText.line(String.valueOf(cannotStart.getMessage())));
            return;
        }
        this.process = started;
        answer(started, secrets);
        Thread reader = new Thread(() -> read(started), "qits-tui-run");
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * The answers to the prompts the child is about to make, then end of input. Closing matters as
     * much as writing: a command that reads nothing would otherwise wait for a stdin that never
     * ends.
     */
    private void answer(Process child, List<String> secrets) {
        try (var toChild = new java.io.OutputStreamWriter(child.getOutputStream(), StandardCharsets.UTF_8)) {
            for (String secret : secrets) {
                toChild.write(secret);
                toChild.write('\n');
            }
        } catch (IOException gone) {
            // The child ended before it read them. Its own output says why.
        }
    }

    private List<String> command(List<String> argv) {
        List<String> command = new ArrayList<>(argv.size() + 1);
        command.add(executable);
        command.addAll(argv);
        return command;
    }

    /**
     * Reads until the child closes its output, then says how it ended. Both streams are one, so the
     * box shows a command's errors where the command printed them.
     */
    private void read(Process child) {
        try (BufferedReader lines = new BufferedReader(
                new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) {
                tail.add(SafeText.line(line));
            }
        } catch (IOException closed) {
            // The child was stopped; whatever it had printed is already in the buffer.
        }
        try {
            int exit = child.waitFor();
            // A child this TUI killed did not fail; saying `exit 143` would read as one.
            title = stopped ? "stopped" : "exit " + exit;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            title = "stopped";
        }
    }

    /**
     * {@code ⌃C}: end the child and leave the TUI on the screen. A command given the chance to end
     * itself usually does; one that does not is ended after {@link #GRACE_MILLIS}.
     */
    public void stop() {
        Process current = process;
        if (current == null || !current.isAlive()) {
            return;
        }
        stopped = true;
        current.destroy();
        try {
            if (!current.waitFor(GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                current.destroyForcibly();
            }
        } catch (InterruptedException interrupted) {
            current.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
