package eu.wohlben.qits.fx;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class AnnotatedTest {

    @Test
    void plain() {
        assertTrue(true);
    }

    /**
     * A Javadoc that is not part of the range, { even with a brace.
     */
    @DisplayName("shows a } name")
    @Tag("slow")
    @Test
    void withOthersAbove() {
        assertTrue(true);
    }

    // a comment with a brace }
    /* and a block comment { */
    @Test
    public void withComments() throws Exception {
        assertTrue(true);
    }

    @org.junit.jupiter.api.Test
    void qualified() {
    }

    void helper() {
    }
}
