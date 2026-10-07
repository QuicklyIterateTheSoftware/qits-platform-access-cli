package eu.wohlben.qits.fx;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class OuterTest {

    @Test
    void outer() {
    }

    @Nested
    class Inner {

        @Test
        void inner() {
        }

        @Nested
        class Deeper {

            @Test
            void deep() {
                assert false;
            }
        }
    }

    @Nested
    class Other {
        @Test void inner() { }
    }
}
