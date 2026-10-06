package eu.wohlben.qits.cli.access.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

/** {@link IstanbulJsonParser} on real vitest 4.1 v8 output ({@link Fixtures#coverageTree}). */
class IstanbulJsonParserTest {

    @TempDir
    Path work;

    private final IstanbulJsonParser parser = new IstanbulJsonParser();

    @Test
    void theFileIsFoundWhereAngularsBuilderLeavesItAndUnderQitsReports() throws Exception {
        Path root = Fixtures.coverageTree(work);
        Path qits = Files.createDirectories(root.resolve(".qits-reports/coverage/app"));
        Files.copy(root.resolve("coverage/qits-fx-app/coverage-final.json"), qits.resolve("coverage-final.json"));
        Path decoy = Files.createDirectories(root.resolve("coverage/node_modules/pkg"));
        Files.writeString(decoy.resolve("coverage-final.json"), "{}");
        Files.writeString(root.resolve("coverage/qits-fx-app/coverage-summary.json"), "{}");

        assertThat(Fixtures.paths(Fixtures.step(root), parser.discover(root))).containsExactly(
                ".qits-reports/coverage/app/coverage-final.json", "coverage/qits-fx-app/coverage-final.json");
        assertThat(parser.language()).isEqualTo("typescript");
        assertThat(parser.tool()).isEqualTo("vitest-coverage");
    }

    @Test
    void nothingIsFoundInATreeWithoutIt() {
        assertThat(parser.discover(work)).isEmpty();
    }

    @Test
    void aLineIsCoverableWhereAStatementStartsAndCoveredWhenOneRan() throws Exception {
        Path root = Fixtures.coverageTree(work);

        LineCoverage coverage = parser.parse(root.resolve("coverage/qits-fx-app/coverage-final.json"),
                Fixtures.step(root));

        assertThat(coverage.files()).containsOnlyKeys("src/app/ledger.ts", "src/app/clock.ts");
        // Line 17 holds two statements (the return and the arrow function's body), both ran.
        assertThat(coverage.files().get("src/app/ledger.ts")).containsExactly(
                entry(7, true), entry(10, true), entry(11, false), entry(13, true), entry(17, true),
                entry(22, false), entry(23, false), entry(24, false), entry(27, false), entry(32, false));
        assertThat(coverage.files().get("src/app/clock.ts")).containsExactly(
                entry(2, false), entry(3, false), entry(5, false));
    }

    @Test
    void aPathOutsideTheRootOrInNodeModulesIsLeftOut() throws Exception {
        Path root = Files.createDirectories(work.resolve("app"));
        Path file = Files.createDirectories(root.resolve("coverage/app")).resolve("coverage-final.json");
        Files.writeString(file, """
                {"/elsewhere/src/a.ts":{"path":"/elsewhere/src/a.ts","statementMap":{"0":{"start":{"line":1,"column":0},"end":{"line":1,"column":null}}},"s":{"0":1}},
                 "%1$s/node_modules/lib/b.js":{"path":"%1$s/node_modules/lib/b.js","statementMap":{"0":{"start":{"line":1,"column":0},"end":{"line":1,"column":null}}},"s":{"0":1}},
                 "src/c.ts":{"statementMap":{"0":{"start":{"line":4,"column":0},"end":{"line":4,"column":null}}},"s":{"0":0}}}
                """.formatted(root));

        LineCoverage coverage = parser.parse(file, Fixtures.step(root));

        assertThat(coverage.files()).containsOnlyKeys("src/c.ts");
        assertThat(coverage.files().get("src/c.ts")).containsExactly(entry(4, false));
    }

    @Test
    void aFileThatIsNotACoverageMapThrowsSoTheKindSkipsIt() throws IOException {
        Path file = Files.createDirectories(work.resolve("coverage/x")).resolve("coverage-final.json");
        Files.writeString(file, "[1, 2]");

        assertThatThrownBy(() -> parser.parse(file, Fixtures.step(work))).isInstanceOf(IOException.class);
    }
}
