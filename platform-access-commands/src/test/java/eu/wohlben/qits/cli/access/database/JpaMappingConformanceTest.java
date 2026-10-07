package eu.wohlben.qits.cli.access.database;

import eu.wohlben.qits.cli.access.database.fixtures.model.Account;
import eu.wohlben.qits.cli.access.database.fixtures.model.Badge;
import eu.wohlben.qits.cli.access.database.fixtures.model.Ledger;
import eu.wohlben.qits.cli.access.database.fixtures.model.Membership;
import eu.wohlben.qits.cli.access.database.fixtures.model.Team;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.internal.ClassLoaderAccessImpl;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.boot.spi.ClassLoaderAccess;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.ForeignKey;
import org.hibernate.mapping.Table;
import org.hibernate.mapping.UniqueKey;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds the generator's reading of the fixture entities' class files against Hibernate's own
 * {@code Metadata} for the same classes, built the way Quarkus builds it: the PostgreSQL dialect,
 * the default implicit naming, no connection, and Bean
 * Validation's constraints applied to the schema. Tables, columns, nullability, primary keys (in key
 * order), foreign keys and unique columns must be equal; a difference names the table and what differs.
 * Every test runs twice: under Hibernate's standard physical naming, which is what Quarkus leaves in
 * place when a unit names none (the estate's case), and under {@code CamelCaseToUnderscoresNamingStrategy},
 * for a unit that names it.
 * <p>
 * Hibernate is a test-scope dependency: this is the only place it runs, and the binary never carries it.
 */
class JpaMappingConformanceTest {

    private static final List<Class<?>> ENTITIES = List.of(Account.class, Membership.class, Team.class, Badge.class,
            Ledger.class);

    /** Quarkus' default (it sets no strategy, so Hibernate's standard one applies), and the snake_case one. */
    static final String STANDARD = "org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl";
    static final String SNAKE_CASE = "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy";

    @TempDir
    static Path dir;

    private static final Map<String, Both> BOTH = new HashMap<>();

    /** Hibernate's mapping of the fixture, and the generator's, under one naming strategy. */
    record Both(Metadata metadata, UnitModel generated) {
    }

    @AfterAll
    static void close() {
        BOTH.clear();
    }

    static Both both(String strategy) throws Exception {
        Both known = BOTH.get(strategy);
        if (known != null) {
            return known;
        }
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", PostgreSQLDialect.class.getName())
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
                .applySetting("hibernate.physical_naming_strategy", strategy)
                .build();
        try {
            MetadataSources sources = new MetadataSources(registry);
            ENTITIES.forEach(sources::addAnnotatedClass);
            Metadata metadata = sources.buildMetadata();
            try (ValidatorFactory validation = Validation.byDefaultProvider().configure()
                    .messageInterpolator(new ParameterMessageInterpolator()).buildValidatorFactory()) {
                // What Hibernate's BeanValidationIntegrator does when Quarkus starts with
                // hibernate-validator present. The class is package-private; its method is the one the
                // integrator calls.
                Method apply = Class.forName("org.hibernate.boot.beanvalidation.TypeSafeActivator").getMethod(
                        "applyRelationalConstraints", ValidatorFactory.class, Collection.class, Map.class,
                        Dialect.class, ClassLoaderAccess.class);
                apply.setAccessible(true);
                apply.invoke(null, validation, metadata.getEntityBindings(), Map.of(),
                        metadata.getDatabase().getDialect(),
                        new ClassLoaderAccessImpl(JpaMappingConformanceTest.class.getClassLoader(), registry));
            }
            List<String> extra = strategy.equals(STANDARD) ? List.of()
                    : List.of("quarkus.hibernate-orm.model.physical-naming-strategy=" + strategy);
            Path root = FixtureProject.create(dir.resolve(strategy), extra);
            UnitModel generated = new JpaEntityModelSource().read(root).stream()
                    .filter(unit -> unit.name().equals("model")).findFirst().orElseThrow();
            Both both = new Both(metadata, generated);
            BOTH.put(strategy, both);
            return both;
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {STANDARD, SNAKE_CASE})
    void theTablesAreHibernates(String strategy) throws Exception {
        Both both = both(strategy);
        assertThat(both.generated().tables().stream().map(TableModel::name).toList())
                .containsExactlyInAnyOrderElementsOf(hibernateTables(both).keySet());
    }

    @ParameterizedTest
    @ValueSource(strings = {STANDARD, SNAKE_CASE})
    void everyTableHasHibernatesColumnsAndNullability(String strategy) throws Exception {
        Both both = both(strategy);
        for (Table table : hibernateTables(both).values()) {
            assertThat(columns(table(both, table.getName())))
                    .as("columns of %s: name -> nullable", table.getName())
                    .isEqualTo(table.getColumns().stream().collect(Collectors.toMap(Column::getName, Column::isNullable,
                            (a, b) -> a, TreeMap::new)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {STANDARD, SNAKE_CASE})
    void everyPrimaryKeyIsHibernatesInKeyOrder(String strategy) throws Exception {
        Both both = both(strategy);
        for (Table table : hibernateTables(both).values()) {
            List<String> expected = table.getPrimaryKey() == null ? List.of()
                    : table.getPrimaryKey().getColumns().stream().map(Column::getName).toList();
            List<String> actual = table(both, table.getName()).columns().stream().filter(ColumnModel::primaryKey)
                    .map(ColumnModel::name).toList();
            assertThat(actual).as("primary key of %s", table.getName()).isEqualTo(expected);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {STANDARD, SNAKE_CASE})
    void everyForeignKeyIsHibernates(String strategy) throws Exception {
        Both both = both(strategy);
        for (Table table : hibernateTables(both).values()) {
            Set<String> expected = new TreeSet<>();
            Set<String> expectedColumns = new TreeSet<>();
            for (ForeignKey key : table.getForeignKeyCollection()) {
                List<String> names = key.getColumns().stream().map(Column::getName).toList();
                expected.add(String.join(", ", names) + " -> " + key.getReferencedTable().getName());
                expectedColumns.addAll(names);
            }
            Set<String> actual = both.generated().relations().stream()
                    .filter(relation -> relation.from().equals(table.getName()))
                    .map(relation -> relation.column() + " -> " + relation.to())
                    .collect(Collectors.toCollection(TreeSet::new));
            assertThat(actual).as("foreign keys of %s", table.getName()).isEqualTo(expected);
            Set<String> actualColumns = table(both, table.getName()).columns().stream().filter(ColumnModel::foreignKey)
                    .map(ColumnModel::name).collect(Collectors.toCollection(TreeSet::new));
            assertThat(actualColumns).as("FK columns of %s", table.getName()).isEqualTo(expectedColumns);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {STANDARD, SNAKE_CASE})
    void everyRelationIsOptionalExactlyWhenItsColumnsMayBeNull(String strategy) throws Exception {
        Both both = both(strategy);
        for (RelationModel relation : both.generated().relations()) {
            Table table = hibernateTables(both).get(relation.from());
            boolean nullable = table.getColumns().stream()
                    .filter(column -> List.of(relation.column().split(", ")).contains(column.getName()))
                    .anyMatch(Column::isNullable);
            assertThat(relation.optional()).as("%s -> %s optional", relation.from(), relation.to()).isEqualTo(nullable);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {STANDARD, SNAKE_CASE})
    void theUniqueColumnsAreHibernates(String strategy) throws Exception {
        Both both = both(strategy);
        for (Table table : hibernateTables(both).values()) {
            Set<String> expected = new TreeSet<>();
            table.getColumns().stream().filter(Column::isUnique).map(Column::getName).forEach(expected::add);
            for (UniqueKey key : table.getUniqueKeys().values()) {
                key.getColumns().stream().map(Column::getName).forEach(expected::add);
            }
            Set<String> actual = table(both, table.getName()).columns().stream().filter(ColumnModel::unique)
                    .map(ColumnModel::name).collect(Collectors.toCollection(TreeSet::new));
            assertThat(actual).as("unique columns of %s", table.getName()).isEqualTo(expected);
        }
    }

    private static Map<String, Table> hibernateTables(Both both) {
        return both.metadata().collectTableMappings().stream()
                .collect(Collectors.toMap(Table::getName, table -> table, (a, b) -> a, TreeMap::new));
    }

    private static TableModel table(Both both, String name) {
        return both.generated().tables().stream().filter(table -> table.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("the generator has no table " + name));
    }

    private static Map<String, Boolean> columns(TableModel table) {
        return table.columns().stream().collect(Collectors.toMap(ColumnModel::name, ColumnModel::nullable,
                (a, b) -> a, TreeMap::new));
    }
}
