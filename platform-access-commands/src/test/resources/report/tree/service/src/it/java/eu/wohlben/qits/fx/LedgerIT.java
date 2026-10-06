package eu.wohlben.qits.fx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LedgerIT {

    @Test
    void startsTheApplication() {
    }

    @Test
    void servesTheLedger() {
        assertEquals(200, 503, "GET /ledger");
    }
}
