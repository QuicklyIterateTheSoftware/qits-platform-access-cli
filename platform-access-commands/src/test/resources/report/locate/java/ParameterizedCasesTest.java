package eu.wohlben.qits.fx;

import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ParameterizedCasesTest {

    @ParameterizedTest
    @ValueSource(strings = {"a", "b", "}"})
    void fromValues(String value) {
        assert value != null;
    }

    @ParameterizedTest(name = "{0} is {1}")
    @MethodSource("cases")
    void fromMethod(String input, int expected) {
        assert input.length() == expected;
    }

    static Stream<Arguments> cases() {
        return Stream.of(Arguments.of("a", 1), Arguments.of("bb", 3));
    }

    @RepeatedTest(3)
    void repeated(RepetitionInfo info) {
        assert info.getCurrentRepetition() < 3;
    }

    @TestFactory
    Stream<DynamicTest> factory() {
        return Stream.empty();
    }
}
