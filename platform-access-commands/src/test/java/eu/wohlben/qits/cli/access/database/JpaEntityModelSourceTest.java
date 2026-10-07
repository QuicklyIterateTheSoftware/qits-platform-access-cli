package eu.wohlben.qits.cli.access.database;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static eu.wohlben.qits.cli.access.database.FixtureProject.FIXTURES;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which units the jpa source finds and what each holds (epic qits-760, Design §2): the units the
 * reactor declares, by name, quoted or {@code <default>}; a package no unit lists; library entities
 * with their artifactId as origin; and never a unit a jar declares, a profiled key or an expression.
 */
class JpaEntityModelSourceTest {

    @TempDir
    static Path dir;

    private static Path root;
    private static final List<String> warnings = new ArrayList<>();
    private static Map<String, UnitModel> units;

    @BeforeAll
    static void read() throws IOException {
        root = FixtureProject.create(dir.resolve("fixture"));
        units = new JpaEntityModelSource(warnings::add).read(root).stream()
                .collect(Collectors.toMap(UnitModel::fileName, Function.identity()));
    }

    @Test
    void oneUnitPerDeclaredUnitAndOneForTheUnlistedPackage() {
        assertThat(units.keySet()).containsExactlyInAnyOrder("model.md", "default.md", "library.md", "unsupported.md",
                FIXTURES + ".stray.md");
        assertThat(units.get("library.md").name()).isEqualTo("library");
        assertThat(units.get("default.md").name()).isEqualTo("<default>");
        assertThat(units.get(FIXTURES + ".stray.md").heading()).isEqualTo("Package `" + FIXTURES + ".stray`");
    }

    @Test
    void theDefaultUnitTakesItsSubPackagesAndItsModuleIsTheOrigin() {
        assertThat(units.get("default.md").tables()).extracting(TableModel::name, TableModel::origin)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Note", "libs/core"),
                        org.assertj.core.groups.Tuple.tuple("Remark", "libs/core"));
    }

    @Test
    void aLibrarysEntityCarriesItsArtifactIdOrItsJarNameWithoutTheVersion() {
        assertThat(units.get("library.md").tables()).extracting(TableModel::name, TableModel::origin)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Crate", "fixture-plain"),
                        org.assertj.core.groups.Tuple.tuple("Shelf", "qits-fixture-library"));
    }

    @Test
    void theSummaryNamesTheDatasourceThePackagesAndTheCount() {
        assertThat(units.get("model.md").notes()).containsExactly("Datasource `main`",
                "package `" + FIXTURES + ".model`", "5 entities");
        assertThat(units.get("library.md").notes()).containsExactly("Datasource `<default>`",
                "packages `" + FIXTURES + ".library`, `" + FIXTURES + ".plain`", "2 entities");
    }

    @Test
    void aUnitAJarDeclaresAProfiledKeyAndAnExpressionAreNotUnits() {
        assertThat(units.keySet()).doesNotContain("jarunit.md", "devonly.md", "expr.md");
        assertThat(warnings).hasSize(2);
        assertThat(warnings.get(0)).startsWith("warning: ignored %dev.quarkus.hibernate-orm.devonly.packages");
        assertThat(warnings.get(1)).startsWith("warning: ignored quarkus.hibernate-orm.expr.packages");
    }

    @Test
    void whatIsNotDrawnIsListedAndTheRestIsStillDrawn() {
        UnitModel unsupported = units.get("unsupported.md");

        assertThat(unsupported.notDrawn()).containsExactly("`Passport.holder` (`@OneToOne`)",
                "`Passport.previousHolders` (`@ManyToMany`)", "`Passport.shouted` (`@Formula`)");
        assertThat(unsupported.tables()).extracting(TableModel::name).containsExactly("Holder", "Passport");
        assertThat(unsupported.relations()).isEmpty();
    }

    @Test
    void panacheEntitysIdComesFromThePanacheJar() {
        TableModel account = units.get("model.md").tables().stream().filter(t -> t.name().equals("account"))
                .findFirst().orElseThrow();

        assertThat(account.columns().get(0)).isEqualTo(new ColumnModel("id", "long", true, false, false, false, null,
                List.of("generated")));
    }

    @Test
    void itDetectsCompiledClassesOnlyAndReadsNothingWithout() throws IOException {
        Path bare = dir.resolve("bare");
        Files.createDirectories(bare);
        FixtureProject.write(bare.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion>"
                + "<artifactId>bare</artifactId><packaging>pom</packaging><modules><module>m</module></modules></project>");

        assertThat(new JpaEntityModelSource().detects(root)).isTrue();
        assertThat(new JpaEntityModelSource().detects(bare)).isFalse();
        assertThat(new JpaEntityModelSource().read(bare)).isEmpty();
    }

    @Test
    void theReactorIsWalkedByItsModules() {
        assertThat(JpaInputs.modules(root)).extracting(JpaInputs.Module::name)
                .containsExactly("fixture-root", "app", "libs", "libs/core");
    }

    @Test
    void theArtifactIdOfAJarWithoutPomPropertiesIsItsNameWithoutTheVersion() {
        assertThat(JpaInputs.artifactId(Path.of("qits-registries-blobstore-2026.1006.1.jar"), null))
                .isEqualTo("qits-registries-blobstore");
        assertThat(JpaInputs.artifactId(Path.of("foo-1.2.3-SNAPSHOT.jar"), null)).isEqualTo("foo");
        assertThat(JpaInputs.artifactId(Path.of("unversioned.jar"), null)).isEqualTo("unversioned");
    }

    @Test
    void snakeCaseNamesAreHibernatesCamelCaseToUnderscores() {
        assertThat(JpaMapping.snakeCase("displayName")).isEqualTo("display_name");
        assertThat(JpaMapping.snakeCase("LedgerBook")).isEqualTo("ledger_book");
        assertThat(JpaMapping.snakeCase("ci_run")).isEqualTo("ci_run");
        assertThat(JpaMapping.snakeCase("HTTPHeader")).isEqualTo("httpheader");
        assertThat(JpaMapping.snakeCase("sha256Digest")).isEqualTo("sha256_digest");
        assertThat(JpaMapping.snakeCase("a.b")).isEqualTo("a_b");
        assertThat(JpaMapping.unquoted("`Quoted`")).isEqualTo("Quoted");
    }

    @Test
    void withoutANamingKeyNamesAreAsTheMappingSpellsThem() {
        List<String> tables = units.get("model.md").tables().stream().map(TableModel::name).toList();

        assertThat(tables).contains("LedgerBook", "Account_aliases", "Team", "Badge", "account", "account_tag");
    }

    @Test
    void aUnitThatNamesCamelCaseToUnderscoresGetsSnakeCaseNames() throws IOException {
        Path snake = FixtureProject.create(dir.resolve("snake"), List.of(
                "quarkus.hibernate-orm.model.physical-naming-strategy="
                        + "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
                "quarkus.hibernate-orm.unsupported.physical-naming-strategy=com.example.OwnNaming"));
        List<String> seen = new ArrayList<>();

        Map<String, UnitModel> read = new JpaEntityModelSource(seen::add).read(snake).stream()
                .collect(Collectors.toMap(UnitModel::fileName, Function.identity()));

        assertThat(read.get("model.md").tables().stream().map(TableModel::name).toList())
                .contains("ledger_book", "account_aliases", "team", "badge");
        assertThat(seen).contains("warning: unit unsupported names the physical naming strategy com.example.OwnNaming, "
                + "which this does not know; its names are drawn as the mapping spells them");
        assertThat(read.get("unsupported.md").tables().stream().map(TableModel::name).toList())
                .containsExactly("Holder", "Passport");
    }
}
