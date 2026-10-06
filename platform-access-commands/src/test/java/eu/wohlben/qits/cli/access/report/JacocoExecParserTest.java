package eu.wohlben.qits.cli.access.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

class JacocoExecParserTest {

    @TempDir
    Path work;

    private final JacocoExecParser parser = new JacocoExecParser();

    @Test
    void theExecFileIsFoundWhereTheArchetypePutsIt() throws Exception {
        assertThat(parser.discover(work)).isEmpty();

        JacocoFixture.build(work);

        assertThat(parser.discover(work)).containsExactly(work.resolve(".qits-reports/jacoco.exec"));
        assertThat(parser.language()).isEqualTo("java");
        assertThat(parser.tool()).isEqualTo("jacoco");
        assertThat(parser.kind()).isEqualTo("coverage");
    }

    @Test
    void everyModulesClassesAreReadAgainstTheOneExecFile() throws Exception {
        JacocoFixture.build(work);

        LineCoverage coverage = parser.parse(work.resolve(".qits-reports/jacoco.exec"), Fixtures.step(work));

        assertThat(coverage.language()).isEqualTo("java");
        assertThat(coverage.tool()).isEqualTo("jacoco");
        assertThat(coverage.files()).containsOnlyKeys(JacocoFixture.LEDGER, JacocoFixture.CLOCK);
        assertThat(coverage.files().get(JacocoFixture.LEDGER)).containsExactly(
                entry(3, true), entry(7, true), entry(8, false), entry(10, true), entry(11, true), entry(14, true),
                entry(18, false));
        assertThat(coverage.files().get(JacocoFixture.CLOCK)).containsExactly(entry(3, false), entry(5, false));
    }

    @Test
    void aClassDirectoryInsideSourcesOrNodeModulesIsNoModule() throws Exception {
        JacocoFixture.build(work);
        Files.createDirectories(work.resolve("web/node_modules/pkg/target/classes"));
        Files.createDirectories(work.resolve("service/src/main/resources/target/classes"));

        assertThat(JacocoExecParser.classDirectories(work)).containsExactly(
                work.resolve("domain/target/classes"), work.resolve("service/target/classes"));
    }

    @Test
    void anExecFileThatIsNotOneThrowsSoTheKindSkipsIt() throws IOException {
        Path exec = Files.createDirectories(work.resolve(".qits-reports")).resolve("jacoco.exec");
        Files.writeString(exec, "not an exec file");

        assertThatThrownBy(() -> parser.parse(exec, Fixtures.step(work))).isInstanceOf(IOException.class);
    }

    @Test
    void anEmptyTreeHasNoLines() throws Exception {
        JacocoFixture.build(work);
        Path elsewhere = Files.createDirectories(work.resolve("elsewhere"));
        Files.copy(work.resolve(".qits-reports/jacoco.exec"), elsewhere.resolve("jacoco.exec"));

        LineCoverage coverage = parser.parse(elsewhere.resolve("jacoco.exec"), Fixtures.step(elsewhere));

        assertThat(coverage.files()).isEqualTo(Map.of());
    }
}
