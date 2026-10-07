package eu.wohlben.qits.fx;

import org.junit.jupiter.api.Test;

class ShapesTest {

    record Point(int x, int y) {
        Point {
            if (x < 0) {
                throw new IllegalArgumentException();
            }
        }

        @Test
        void inRecord() {
        }
    }

    enum Colour {
        RED {
            @Override
            String code() {
                return "r";
            }
        },
        GREEN;

        String code() {
            return "g";
        }

        @Test
        void inEnum() {
        }
    }

    @Test
    void usesClassLiterals() {
        Class<?> type = Point.class;
        assert type != null;
    }
}

record TopLevelRecord(String name) {

    @Test
    void inTopLevelRecord() {
    }
}
