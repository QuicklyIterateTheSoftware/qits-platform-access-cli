package eu.wohlben.qits.fx;

import org.junit.jupiter.api.Test;

class OverloadTest {

    private void check(String value) {
        assert value.isEmpty();
    }

    @Test
    void check() {
        check("x");
    }

    void twice(int a) {
    }

    void twice(String a) {
    }
}
