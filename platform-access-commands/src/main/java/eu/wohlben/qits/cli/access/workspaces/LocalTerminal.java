package eu.wohlben.qits.cli.access.workspaces;

import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;

import java.io.InputStream;

/**
 * The caller's own terminal, as {@code qits workspaces attach} uses it: raw mode in and out, its
 * size, a word when the window changes, and the keystrokes. The commands module has no JLine, so the
 * real one is {@code stty} on {@code /dev/tty} ({@link SttyTerminal}); a test hands a fake.
 * <p>
 * Whether there is a terminal at all is decided here, once, by {@link #of}: stdin a person's terminal
 * and {@code stty} able to read it. Without one the attachment only streams ({@link #READ_ONLY}).
 */
interface LocalTerminal {

    /** A window size, in characters. */
    record Size(int cols, int rows) {
    }

    /** How an attaching command gets its terminal; a test hands its own. */
    @FunctionalInterface
    interface Factory {
        LocalTerminal open(CliContext context);
    }

    /** No terminal: nothing is read, nothing is switched, and the remote output is only streamed. */
    LocalTerminal READ_ONLY = new LocalTerminal() {
        @Override
        public boolean interactive() {
            return false;
        }

        @Override
        public void enterRaw() {
        }

        @Override
        public void restore() {
        }

        @Override
        public Size size() {
            return null;
        }

        @Override
        public boolean onResize(Runnable resized) {
            return false;
        }

        @Override
        public InputStream keys() {
            return InputStream.nullInputStream();
        }
    };

    /**
     * Whether a person types here: the keystrokes are relayed and the terminal goes raw. False means
     * read-only streaming.
     */
    boolean interactive();

    /** Raw mode, no echo: every key, Ctrl-C included, goes to the remote command as it is typed. */
    void enterRaw() throws CliFailure;

    /** The terminal as it was before {@link #enterRaw}. Safe to call twice, and from any thread. */
    void restore();

    /** The window's size, or null when it cannot be read. */
    Size size();

    /** Runs {@code resized} when the window changes (SIGWINCH). False when no signal can be had. */
    boolean onResize(Runnable resized);

    /** What the person types. */
    InputStream keys();

    /** The process's own terminal when stdin is one and stty works on it, else {@link #READ_ONLY}. */
    static LocalTerminal of(CliContext context) {
        if (!context.stdinIsTerminal()) {
            return READ_ONLY;
        }
        LocalTerminal terminal = SttyTerminal.open(context.in());
        return terminal == null ? READ_ONLY : terminal;
    }
}
