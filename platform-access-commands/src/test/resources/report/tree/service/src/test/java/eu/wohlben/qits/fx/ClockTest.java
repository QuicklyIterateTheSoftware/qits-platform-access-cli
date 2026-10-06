package eu.wohlben.qits.fx;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ClockTest {

    @Test
    void ticks() {
    }

    @Test
    @Timeout(value = 200, unit = java.util.concurrent.TimeUnit.MILLISECONDS)
    void waitsTooLong() throws InterruptedException {
        Thread.sleep(2000);
    }
}
