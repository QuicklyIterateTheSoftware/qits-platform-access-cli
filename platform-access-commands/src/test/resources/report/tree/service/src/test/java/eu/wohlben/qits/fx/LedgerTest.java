package eu.wohlben.qits.fx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LedgerTest {

    @Test
    void addsTwoEntries() {
        assertEquals(4, 2 + 2);
    }

    @Test
    void refusesAnotherRunsToken() {
        assertEquals(403, 204, "the door must refuse another run's token");
    }

    @Test
    void readsTheLedger() {
        Object ledger = null;
        ledger.toString();
    }

    @Disabled("not yet")
    @Test
    void skipsForNow() {
    }

    @Nested
    class WhenEmpty {

        @Test
        void hasNoBalance() {
            assertEquals(0, 0);
        }

        @Test
        void refusesAWithdrawal() {
            assertEquals("refused", "accepted");
        }
    }
}
