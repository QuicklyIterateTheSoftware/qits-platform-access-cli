package eu.wohlben.qits.cli.access.checkout;

import eu.wohlben.qits.cli.access.session.Times;

import java.io.PrintStream;
import java.time.Clock;
import java.util.function.Consumer;

/**
 * Every line this command says to a person: a time, the command's name, and the message, on
 * stderr. One place, so a note from the Git side and a note from the watch read the same. The time
 * comes from the clock the context holds, never from the wall.
 */
final class Notes implements Consumer<String> {

    private final PrintStream err;
    private final Clock clock;

    Notes(PrintStream err, Clock clock) {
        this.err = err;
        this.clock = clock;
    }

    @Override
    public void accept(String message) {
        err.println(Times.stamp(clock.instant()) + " qits checkout-daemon: " + message);
        err.flush();
    }
}
