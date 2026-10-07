package eu.wohlben.qits.cli.access.report.locate;

import eu.wohlben.qits.cli.access.report.LineRange;
import eu.wohlben.qits.cli.access.report.RepositoryRef;
import eu.wohlben.qits.cli.access.report.TestCoordinates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** JUnit test methods located in the Java fixtures under {@code src/test/resources/report/locate/java}. */
class JUnitTestCaseLocatorTest {

    static final Path ROOT = fixtures();

    private final JUnitTestCaseLocator locator = new JUnitTestCaseLocator();

    @TempDir
    Path work;

    static Path fixtures() {
        try {
            return Path.of(JUnitTestCaseLocatorTest.class.getResource("/report/locate").toURI());
        } catch (URISyntaxException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Test
    void supportsJavaUnderSurefireAndFailsafe() {
        assertThat(locator.supports("java", "surefire")).isTrue();
        assertThat(locator.supports("java", "failsafe")).isTrue();
        assertThat(locator.supports("java", "vitest")).isFalse();
        assertThat(locator.supports("typescript", "surefire")).isFalse();
        assertThat(locator.supports(null, null)).isFalse();
    }

    @Test
    void aPlainTestFromItsAnnotationToItsClosingBrace() {
        assertThat(locate("AnnotatedTest", "AnnotatedTest", "plain")).contains(new LineRange(11, 14));
    }

    @Test
    void theOtherAnnotationsAboveAreIncludedAndTheJavadocIsNot() {
        assertThat(locate("AnnotatedTest", "AnnotatedTest", "withOthersAbove")).contains(new LineRange(19, 24));
    }

    @Test
    void commentsAboveAreNotIncludedAndModifiersAndThrowsAreStepped() {
        assertThat(locate("AnnotatedTest", "AnnotatedTest", "withComments")).contains(new LineRange(28, 31));
        assertThat(locate("AnnotatedTest", "AnnotatedTest", "qualified")).contains(new LineRange(33, 35));
    }

    @Test
    void aMethodWithoutAnnotationsStartsAtItsReturnType() {
        assertThat(locate("AnnotatedTest", "AnnotatedTest", "helper")).contains(new LineRange(37, 38));
    }

    @Test
    void parameterizedCasesAreLocatedFromSurefiresSpelling() {
        assertThat(locate("ParameterizedCasesTest", "ParameterizedCasesTest", "fromValues(String)[2]"))
                .contains(new LineRange(15, 19));
        assertThat(locate("ParameterizedCasesTest", "ParameterizedCasesTest", "fromMethod(String, int)[2]"))
                .contains(new LineRange(21, 25));
        assertThat(locate("ParameterizedCasesTest", "ParameterizedCasesTest", "repeated(RepetitionInfo)[3]"))
                .contains(new LineRange(31, 34));
        assertThat(locate("ParameterizedCasesTest", "ParameterizedCasesTest", "factory()[1]"))
                .contains(new LineRange(36, 39));
    }

    @Test
    void ofAnOverloadPairTheTestWins() {
        assertThat(locate("OverloadTest", "OverloadTest", "check")).contains(new LineRange(11, 14));
    }

    @Test
    void twoEqualOverloadsLocateNothing() {
        assertThat(locate("OverloadTest", "OverloadTest", "twice")).isEmpty();
    }

    @Test
    void nestedClassesAreFollowedByTheirBinaryName() {
        assertThat(locate("OuterTest", "OuterTest", "outer")).contains(new LineRange(8, 10));
        assertThat(locate("OuterTest", "OuterTest$Inner", "inner")).contains(new LineRange(15, 17));
        assertThat(locate("OuterTest", "OuterTest$Inner$Deeper", "deep")).contains(new LineRange(22, 25));
        assertThat(locate("OuterTest", "OuterTest$Other", "inner")).contains(new LineRange(31, 31));
        assertThat(locate("OuterTest", "OuterTest", "inner")).isEmpty();
        assertThat(locate("OuterTest", "OuterTest$Missing", "inner")).isEmpty();
    }

    @Test
    void bracesInLiteralsCommentsAndTextBlocksAreNotCounted() {
        assertThat(locate("TrickyTest", "TrickyTest", "bracesEverywhere")).contains(new LineRange(9, 23));
        assertThat(locate("TrickyTest", "TrickyTest", "afterAnonymous")).contains(new LineRange(40, 48));
    }

    @Test
    void aGenericMethod() {
        assertThat(locate("TrickyTest", "TrickyTest", "generic")).contains(new LineRange(25, 29));
    }

    @Test
    void aMultiLineAnnotationWithArraysAndNestedAnnotations() {
        assertThat(locate("TrickyTest", "TrickyTest", "multiLineAnnotation")).contains(new LineRange(31, 38));
    }

    @Test
    void recordsAndEnumsInTheSameFile() {
        assertThat(locate("ShapesTest", "ShapesTest$Point", "inRecord")).contains(new LineRange(14, 16));
        assertThat(locate("ShapesTest", "ShapesTest$Colour", "inEnum")).contains(new LineRange(32, 34));
        assertThat(locate("ShapesTest", "ShapesTest$Colour", "code")).contains(new LineRange(28, 30));
        assertThat(locate("ShapesTest", "ShapesTest", "usesClassLiterals")).contains(new LineRange(37, 41));
        assertThat(locate("ShapesTest", "TopLevelRecord", "inTopLevelRecord")).contains(new LineRange(46, 48));
    }

    @Test
    void aClassLevelFailureLocatesNothing() {
        assertThat(locate("AnnotatedTest", "AnnotatedTest", "initializationError")).isEmpty();
        assertThat(locate("AnnotatedTest", "AnnotatedTest", "(setup)")).isEmpty();
        assertThat(locate("AnnotatedTest", "AnnotatedTest", "AnnotatedTest")).isEmpty();
        assertThat(locate("AnnotatedTest", "AnnotatedTest", "eu.wohlben.qits.fx.AnnotatedTest")).isEmpty();
        assertThat(locate("AnnotatedTest", "AnnotatedTest", "")).isEmpty();
        assertThat(locate("AnnotatedTest", "AnnotatedTest", null)).isEmpty();
    }

    @Test
    void aMissingMethodLocatesNothing() {
        assertThat(locate("AnnotatedTest", "AnnotatedTest", "notThere")).isEmpty();
    }

    @Test
    void aNullOrMissingFileLocatesNothing() {
        assertThat(locator.locate(ROOT, coordinates(null, "eu.wohlben.qits.fx.AnnotatedTest", "plain"))).isEmpty();
        assertThat(locator.locate(ROOT, coordinates("java/Nowhere.java", "eu.wohlben.qits.fx.Nowhere", "plain")))
                .isEmpty();
        assertThat(locator.locate(ROOT, coordinates("../locate/java/AnnotatedTest.java",
                "eu.wohlben.qits.fx.AnnotatedTest", "plain"))).contains(new LineRange(11, 14));
        assertThat(locator.locate(ROOT.resolve("vitest"), coordinates("../java/AnnotatedTest.java",
                "eu.wohlben.qits.fx.AnnotatedTest", "plain"))).isEmpty();
    }

    @Test
    void aFileOverOneMebibyteLocatesNothing() throws Exception {
        String source = Files.readString(ROOT.resolve("java/AnnotatedTest.java"));
        Path big = work.resolve("Big.java");
        Files.writeString(big, source + "//" + "x".repeat((int) SourceFiles.MAX_BYTES) + "\n");
        Path fits = work.resolve("Fits.java");
        Files.writeString(fits, source);

        assertThat(locator.locate(work, coordinates("Big.java", "eu.wohlben.qits.fx.AnnotatedTest", "plain"))).isEmpty();
        assertThat(locator.locate(work, coordinates("Fits.java", "eu.wohlben.qits.fx.AnnotatedTest", "plain")))
                .contains(new LineRange(11, 14));
    }

    @Test
    void unbalancedSourceLocatesNothingAndNeverThrows() throws Exception {
        Files.writeString(work.resolve("Broken.java"), "class Broken {\n @Test\n void a() {\n");
        Files.write(work.resolve("Binary.java"), new byte[]{(byte) 0xff, 0, (byte) 0xfe, '{', '"'});

        assertThat(locator.locate(work, coordinates("Broken.java", "Broken", "a"))).isEmpty();
        assertThat(locator.locate(work, coordinates("Binary.java", "Binary", "a"))).isEmpty();
        assertThat(locator.locate(work.resolve("absent"), coordinates("Broken.java", "Broken", "a"))).isEmpty();
    }

    private Optional<LineRange> locate(String file, String binaryName, String testName) {
        return locator.locate(ROOT, coordinates("java/" + file + ".java", "eu.wohlben.qits.fx." + binaryName, testName));
    }

    private static TestCoordinates coordinates(String file, String className, String testName) {
        return new TestCoordinates("java", "surefire", new RepositoryRef("p", "r"), "sha", file, className, testName,
                null, null);
    }
}
