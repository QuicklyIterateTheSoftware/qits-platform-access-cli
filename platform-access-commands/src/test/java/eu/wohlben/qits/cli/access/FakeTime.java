package eu.wohlben.qits.cli.access;

import eu.wohlben.qits.cli.access.daemon.Sleeper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A wall clock that only moves when someone sleeps on it, or when a test makes it jump. */
public final class FakeTime extends Clock implements Sleeper {

    private volatile Instant now;
    public final List<Duration> sleeps = Collections.synchronizedList(new ArrayList<>());
    /** Runs after every sleep, with the clock already moved. */
    public volatile Runnable afterSleep = () -> { };

    public FakeTime(Instant start) {
        this.now = start;
    }

    public void jump(Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public void sleep(Duration duration) {
        sleeps.add(duration);
        now = now.plus(duration);
        if (sleeps.size() > 100_000) {
            throw new AssertionError("the daemon slept 100000 times: a loop that never ends");
        }
        afterSleep.run();
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
