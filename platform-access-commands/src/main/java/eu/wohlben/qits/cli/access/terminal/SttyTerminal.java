package eu.wohlben.qits.cli.access.terminal;

import eu.wohlben.qits.cli.access.platform.CliFailure;
import sun.misc.Signal;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The real {@link LocalTerminal}: {@code stty} with {@code /dev/tty} as its stdin, the way a shell
 * script does it. No JLine (the commands module has none) and no shell: each call is {@code stty}
 * with its arguments, and the saved state is checked to be stty's own {@code -g} form before it is
 * handed back.
 * <p>
 * <b>Restoring is certain.</b> {@link #enterRaw} registers a shutdown hook that restores, so an exit
 * the attachment's own {@code finally} never reaches (a {@code System.exit} elsewhere, SIGHUP) still
 * leaves the terminal usable; {@link #restore} removes it again.
 * <p>
 * <b>SIGWINCH</b> is {@code sun.misc.Signal}, which StopSignals already relies on in the native
 * binary for TERM and INT: a native executable installs signal handling by default. The attachment
 * also reads the size every few seconds, so a runtime that keeps the signal costs a little lag, not a
 * wrong size for good.
 */
final class SttyTerminal implements LocalTerminal {

    /** What {@code stty -g} prints: hex fields and colons (GNU), or {@code gfmt1:} key=value (BSD). */
    private static final Pattern SAVED = Pattern.compile("[0-9A-Za-z:=]+");
    private static final File TTY = new File("/dev/tty");
    private static final long STTY_TIMEOUT_SECONDS = 5;

    private final InputStream keys;
    private final String original;
    private boolean raw;
    private Thread hook;

    private SttyTerminal(InputStream keys, String original) {
        this.keys = keys;
        this.original = original;
    }

    /** The terminal, or null when stty cannot read it (no {@code /dev/tty}, no stty). */
    static SttyTerminal open(InputStream keys) {
        String saved = stty("-g");
        return saved == null || !SAVED.matcher(saved).matches() ? null : new SttyTerminal(keys, saved);
    }

    @Override
    public boolean interactive() {
        return true;
    }

    @Override
    public synchronized void enterRaw() throws CliFailure {
        if (raw) {
            return;
        }
        hook = Thread.ofPlatform().unstarted(this::restore);
        Runtime.getRuntime().addShutdownHook(hook);
        raw = true;
        if (stty("raw", "-echo") == null) {
            restore();
            throw new CliFailure("Cannot put this terminal in raw mode (stty raw -echo failed).", CliFailure.FAILED);
        }
    }

    @Override
    public synchronized void restore() {
        if (!raw) {
            return;
        }
        raw = false;
        stty(original);
        if (hook != null && Thread.currentThread() != hook) {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException shuttingDown) {
                // The hook runs or has run; the terminal is restored either way.
            }
        }
        hook = null;
    }

    @Override
    public Size size() {
        String answer = stty("size");
        if (answer == null) {
            return null;
        }
        String[] parts = answer.strip().split("\\s+");
        try {
            int rows = Integer.parseInt(parts[0]);
            int cols = Integer.parseInt(parts[1]);
            // A terminal that reports 0 0 (some serial lines, some CI runners) has no size to give.
            return rows > 0 && cols > 0 ? new Size(cols, rows) : null;
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException notASize) {
            return null;
        }
    }

    @Override
    public boolean onResize(Runnable resized) {
        try {
            Signal.handle(new Signal("WINCH"), signal -> resized.run());
            return true;
        } catch (IllegalArgumentException | UnsupportedOperationException | LinkageError notHere) {
            return false;
        }
    }

    @Override
    public InputStream keys() {
        return keys;
    }

    /** {@code stty <args> < /dev/tty}: its stdout, or null when it failed. */
    private static String stty(String... args) {
        List<String> command = new ArrayList<>();
        command.add("stty");
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).redirectInput(TTY)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!process.waitFor(STTY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return process.exitValue() == 0 ? out : null;
        } catch (IOException noStty) {
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
