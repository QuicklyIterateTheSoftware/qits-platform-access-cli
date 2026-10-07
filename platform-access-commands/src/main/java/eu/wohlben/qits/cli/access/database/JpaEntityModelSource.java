package eu.wohlben.qits.cli.access.database;

import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * JPA/Hibernate, id {@code jpa}: the entity mapping of a maven reactor, read from its compiled
 * classes with Jandex (epic qits-760, Design §1 and §2).
 * <p>
 * The inputs are every reactor module's {@code target/classes} (found by walking {@code <modules>}
 * from the root {@code pom.xml}) and the jars of each module's {@code target/qits-classpath.txt},
 * which {@code dependency:build-classpath -Dmdep.outputFile=target/qits-classpath.txt
 * -DincludeScope=runtime} writes. Nothing is loaded and nothing of the repository runs.
 * <p>
 * One unit per persistence unit the reactor declares ({@link JpaUnits}), holding every {@code @Entity}
 * in its packages and their sub-packages, the reactor's or a jar's. An entity of the reactor that no
 * unit claims is drawn with the others of its Java package, in a unit named after the package.
 */
public final class JpaEntityModelSource implements EntityModelSource {

    public static final String ID = "jpa";

    private static final String STANDARD_NAMING = "org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl";
    private static final Set<String> SNAKE_CASE_NAMING = Set.of(
            "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
            "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl");

    private final Consumer<String> warnings;

    /** Warnings (an ignored profiled key, an unreadable class file) go to {@code warnings}, one line each. */
    public JpaEntityModelSource(Consumer<String> warnings) {
        this.warnings = warnings;
    }

    public JpaEntityModelSource() {
        this(line -> { });
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean detects(Path root) {
        return !JpaInputs.compiledModules(root).isEmpty();
    }

    @Override
    public List<UnitModel> read(Path root) throws IOException {
        try (JpaInputs inputs = JpaInputs.open(root, warnings)) {
            if (inputs.modules().isEmpty()) {
                return List.of();
            }
            List<UnitModel> units = new ArrayList<>();
            Set<DotName> claimed = new HashSet<>();
            JpaUnits.Declared declared = JpaUnits.read(inputs.root(), inputs.modules(), warnings);
            for (JpaUnits.Unit unit : declared.units()) {
                List<ClassInfo> entities = entities(inputs, inputs.classesIn(unit.packages()));
                entities.forEach(entity -> claimed.add(entity.name()));
                JpaMapping.Mapped mapped = new JpaMapping(inputs, snakeCase(unit.namingStrategy(), unit.name()))
                        .map(entities);
                List<String> notes = new ArrayList<>();
                notes.add("Datasource `" + (unit.datasource() == null ? JpaUnits.DEFAULT_UNIT : unit.datasource()) + "`");
                notes.add((unit.packages().size() == 1 ? "package " : "packages ")
                        + String.join(", ", unit.packages().stream().map(pkg -> "`" + pkg + "`").toList()));
                notes.add(count(mapped.entities()));
                units.add(new UnitModel(unit.name(), MermaidEntityDiagramFormat.fileNameOf(unit.name()),
                        "Persistence unit `" + unit.name() + "`", notes, mapped.tables(), mapped.relations(),
                        mapped.notDrawn()));
            }
            // The reactor's own entities that no unit lists, one diagram per package.
            Map<String, List<ClassInfo>> unclaimed = new TreeMap<>();
            for (ClassInfo entity : entities(inputs, inputs.reactorClasses())) {
                if (!claimed.contains(entity.name())) {
                    unclaimed.computeIfAbsent(JpaInputs.packageOf(entity.name()), pkg -> new ArrayList<>()).add(entity);
                }
            }
            boolean defaultSnakeCase = snakeCase(declared.defaultNamingStrategy(), JpaUnits.DEFAULT_UNIT);
            for (Map.Entry<String, List<ClassInfo>> pkg : unclaimed.entrySet()) {
                JpaMapping.Mapped mapped = new JpaMapping(inputs, defaultSnakeCase).map(pkg.getValue());
                units.add(new UnitModel(pkg.getKey(), MermaidEntityDiagramFormat.fileNameOf(pkg.getKey()),
                        "Package `" + pkg.getKey() + "`",
                        List.of("No persistence unit of this repository lists it", count(mapped.entities())),
                        mapped.tables(), mapped.relations(), mapped.notDrawn()));
            }
            units.sort((a, b) -> a.fileName().compareTo(b.fileName()));
            return units;
        }
    }

    /**
     * Whether a unit's physical naming strategy is Hibernate's camel-case-to-underscores one. No key is
     * Hibernate's standard strategy, which Quarkus leaves in place; a class this does not know is read
     * as the standard one, with a warning, since its names cannot be computed without running it.
     */
    private boolean snakeCase(String strategy, String unit) {
        if (strategy == null || strategy.equals(STANDARD_NAMING)) {
            return false;
        }
        if (SNAKE_CASE_NAMING.contains(strategy)) {
            return true;
        }
        warnings.accept("warning: unit " + unit + " names the physical naming strategy " + strategy
                + ", which this does not know; its names are drawn as the mapping spells them");
        return false;
    }

    private static String count(int entities) {
        return entities + (entities == 1 ? " entity" : " entities");
    }

    /** The {@code @Entity} classes among {@code names}, in name order. */
    private static List<ClassInfo> entities(JpaInputs inputs, List<DotName> names) {
        List<ClassInfo> entities = new ArrayList<>();
        for (DotName name : names) {
            Optional<ClassInfo> info = inputs.classInfo(name);
            if (info.isPresent() && info.get().declaredAnnotation(JpaMapping.ENTITY) != null) {
                entities.add(info.get());
            }
        }
        return entities;
    }
}
