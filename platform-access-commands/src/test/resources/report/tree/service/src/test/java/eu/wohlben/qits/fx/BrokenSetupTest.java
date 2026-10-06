package eu.wohlben.qits.fx;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BrokenSetupTest {

    @BeforeAll
    static void startTheDatabase() {
        throw new IllegalStateException("the database did not start");
    }

    @Test
    void neverRuns() {
    }
}
