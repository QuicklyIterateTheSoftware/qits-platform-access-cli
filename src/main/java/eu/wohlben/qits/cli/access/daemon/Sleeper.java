package eu.wohlben.qits.cli.access.daemon;

import java.time.Duration;

/** The daemon's only way to wait. Tests give it one that moves a fake clock instead. */
public interface Sleeper {

    void sleep(Duration duration) throws InterruptedException;

    /** Ends the current sleep and every later one at once. Used to stop. */
    default void wake() {
    }

    /**
     * Waits on a monitor rather than being interrupted. An interrupt that landed during the
     * session write would close the file channel, and lose a refresh token the idp has already
     * rotated.
     */
    static Sleeper real() {
        return new Sleeper() {
            private final Object monitor = new Object();
            private boolean woken;

            @Override
            public void sleep(Duration duration) throws InterruptedException {
                synchronized (monitor) {
                    if (!woken) {
                        monitor.wait(Math.max(1, duration.toMillis()));
                    }
                }
            }

            @Override
            public void wake() {
                synchronized (monitor) {
                    woken = true;
                    monitor.notifyAll();
                }
            }
        };
    }
}
