package eu.wohlben.qits.fx;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrickyTest {

    @Test
    void bracesEverywhere() {
        String open = "{ not a brace";
        char close = '}';
        char quote = '\'';
        String escaped = "\" } \\";
        // }
        /* { */
        String block = """
            {
              "json": "}"
            \""" still inside {
            """;
        assert open.equals(block + close + quote + escaped);
    }

    static <T extends Comparable<T>> List<T> generic(T first) {
        List<T> list = new ArrayList<>();
        list.add(first);
        return list;
    }

    @Test
    @Settings(
            value = @Value(names = {"a", "b"}),
            more = {@Inner(1), @Inner(2)}
    )
    @Tags({@Tag("x"), @Tag("y")})
    void multiLineAnnotation() {
    }

    @Test
    void afterAnonymous() {
        Runnable r = new Runnable() {
            @Override
            public void run() {
            }
        };
        r.run();
    }
}
