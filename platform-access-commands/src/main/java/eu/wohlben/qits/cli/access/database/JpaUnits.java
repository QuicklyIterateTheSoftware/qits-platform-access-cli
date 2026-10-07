package eu.wohlben.qits.cli.access.database;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * The persistence units the repository itself declares: the unprofiled
 * {@code quarkus.hibernate-orm.<unit>.packages}, {@code .datasource} and {@code .physical-naming-strategy}
 * keys of each reactor module's
 * own {@code target/classes/application.properties} and
 * {@code target/classes/META-INF/microprofile-config.properties}. Never a jar's: a unit a library
 * declares is drawn in that library's repository (epic qits-760, Design §2).
 * <p>
 * {@code quarkus.hibernate-orm.packages} is the {@code <default>} unit. A profiled key
 * ({@code %dev.quarkus...}) and a value holding a {@code ${...}} expression are not the mapping every
 * profile gets, so they are ignored, each with one warning.
 */
final class JpaUnits {

    static final String DEFAULT_UNIT = "<default>";

    private static final String PREFIX = "quarkus.hibernate-orm.";
    private static final List<String> FILES = List.of("application.properties", "META-INF/microprofile-config.properties");

    static final String NAMING = "physical-naming-strategy";

    /**
     * One declared unit: its datasource and its physical naming strategy (each null when no key names
     * one), and its packages, in order.
     */
    record Unit(String name, String datasource, String namingStrategy, List<String> packages) {
    }

    /** The declared units, and the {@code <default>} unit's naming strategy, which unlisted packages get. */
    record Declared(List<Unit> units, String defaultNamingStrategy) {
    }

    private JpaUnits() {
    }

    /** The declared units, by name; a unit with no packages key is not one. */
    static Declared read(Path root, List<JpaInputs.Module> modules, Consumer<String> warnings) throws IOException {
        Map<String, String> datasources = new TreeMap<>();
        Map<String, String> naming = new TreeMap<>();
        Map<String, Set<String>> packages = new TreeMap<>();
        for (JpaInputs.Module module : modules) {
            for (String file : FILES) {
                Path path = module.classes().resolve(file);
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                Properties properties = new Properties();
                try (InputStream in = Files.newInputStream(path)) {
                    properties.load(in);
                } catch (IllegalArgumentException malformed) {
                    warnings.accept("warning: skipped " + display(root, path) + ": " + malformed.getMessage());
                    continue;
                }
                for (String key : new TreeSet<>(properties.stringPropertyNames())) {
                    read(key, properties.getProperty(key), display(root, path), datasources, naming, packages,
                            warnings);
                }
            }
        }
        List<Unit> units = new ArrayList<>();
        for (Map.Entry<String, Set<String>> unit : packages.entrySet()) {
            units.add(new Unit(unit.getKey(), datasources.get(unit.getKey()), naming.get(unit.getKey()),
                    List.copyOf(unit.getValue())));
        }
        return new Declared(units, naming.get(DEFAULT_UNIT));
    }

    private static void read(String key, String value, String file, Map<String, String> datasources,
                             Map<String, String> naming, Map<String, Set<String>> packages, Consumer<String> warnings) {
        String bare = key;
        boolean profiled = key.startsWith("%");
        if (profiled) {
            int dot = key.indexOf('.');
            bare = dot < 0 ? "" : key.substring(dot + 1);
        }
        if (!bare.startsWith(PREFIX)) {
            return;
        }
        String[] unitAndProperty = unitAndProperty(bare.substring(PREFIX.length()));
        if (unitAndProperty == null) {
            return;
        }
        if (profiled) {
            warnings.accept("warning: ignored " + key + " in " + file
                    + ": a profiled key is not the mapping every profile gets");
            return;
        }
        if (value.contains("${")) {
            warnings.accept("warning: ignored " + key + " in " + file + ": its value is an expression ("
                    + value.strip() + ")");
            return;
        }
        String unit = unitAndProperty[0];
        if (unitAndProperty[1].equals("datasource")) {
            datasources.putIfAbsent(unit, value.strip());
        } else if (unitAndProperty[1].equals(NAMING)) {
            naming.putIfAbsent(unit, value.strip());
        } else {
            Set<String> into = packages.computeIfAbsent(unit, name -> new LinkedHashSet<>());
            for (String pkg : value.split(",")) {
                if (!pkg.isBlank()) {
                    into.add(pkg.strip());
                }
            }
        }
    }

    /**
     * {@code packages} gives the default unit, {@code ci.packages} or {@code "my.unit".packages} the
     * named one; null for every other key under the prefix.
     */
    private static String[] unitAndProperty(String rest) {
        if (isUnitProperty(rest)) {
            return new String[] {DEFAULT_UNIT, rest};
        }
        String unit;
        String property;
        if (rest.startsWith("\"")) {
            int close = rest.indexOf('"', 1);
            if (close < 0 || close + 1 >= rest.length() || rest.charAt(close + 1) != '.') {
                return null;
            }
            unit = rest.substring(1, close);
            property = rest.substring(close + 2);
        } else {
            int dot = rest.indexOf('.');
            if (dot < 0) {
                return null;
            }
            unit = rest.substring(0, dot);
            property = rest.substring(dot + 1);
        }
        if (unit.isEmpty() || !isUnitProperty(property)) {
            return null;
        }
        return new String[] {unit, property};
    }

    private static boolean isUnitProperty(String property) {
        return property.equals("packages") || property.equals("datasource") || property.equals(NAMING);
    }

    private static String display(Path root, Path path) {
        return path.startsWith(root) ? root.relativize(path).toString().replace('\\', '/') : path.toString();
    }
}
