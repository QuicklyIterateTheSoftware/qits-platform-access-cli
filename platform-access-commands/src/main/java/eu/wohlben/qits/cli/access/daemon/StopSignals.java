package eu.wohlben.qits.cli.access.daemon;

import sun.misc.Signal;

import java.util.List;

/**
 * SIGTERM and SIGINT ask the daemon to stop, and it then leaves on its own: locks released, a last
 * log line, exit code 0. The JVM's default handlers would instead exit at once from a shutdown
 * hook, in the middle of whatever the loop was doing. {@code qits events} uses it too: an inline
 * refresh there must finish its write before the process ends.
 */
public final class StopSignals {

    private StopSignals() {
    }

    public static void install(Runnable stop) {
        for (String name : List.of("TERM", "INT")) {
            try {
                Signal.handle(new Signal(name), signal -> stop.run());
            } catch (IllegalArgumentException | UnsupportedOperationException notAllowed) {
                // The runtime keeps this signal (for example under -Xrs). Its default still ends
                // the process, and the kernel drops the locks with it.
            }
        }
    }
}
