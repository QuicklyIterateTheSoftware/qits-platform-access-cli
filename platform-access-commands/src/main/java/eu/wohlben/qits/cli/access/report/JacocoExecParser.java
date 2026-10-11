package eu.wohlben.qits.cli.access.report;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.tools.ExecFileLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * JaCoCo, {@code .qits-reports/jacoco.exec} at the root: what the archetype's agent
 * ({@code JAVA_TOOL_OPTIONS}, {@code append=true}) wrote from every JVM the step started, forked test
 * JVMs and failsafe-launched applications alike. Read with {@code org.jacoco.core} against every
 * module's {@code target/classes}, so no {@code report} goal is needed and a class one module's tests
 * cover from another module counts.
 * <p>
 * A class's lines map to {@code <module>/src/main/java/<package>/<source file>}, under the module
 * whose {@code target/classes} held it. A class whose source is not there (generated into
 * {@code target/generated-sources}, or written in another language) is left out: no reviewer can read
 * those lines, and no diff names them. {@code NOT_COVERED} is uncovered; {@code PARTLY_COVERED} and
 * {@code FULLY_COVERED} are covered.
 * <p>
 * CLASSES A JVM CHANGED BEFORE IT RAN THEM (qits-1171). JaCoCo keys its data by a CRC of the class
 * bytes the JVM loaded. Quarkus rewrites many classes as it loads them (CDI beans, Panache entities
 * and repositories), so their data carries an id no class in {@code target/classes} has, and read
 * against {@code target/classes} alone those classes count as never run. The agent's
 * {@code classdumpdir} writes every class as it was loaded to {@code .qits-reports/jacoco-classes},
 * one file per variant. Each variant is read against the data under its own id, and a line it covers
 * counts as covered in the original. {@code target/classes} alone decides which lines are coverable,
 * so the total does not depend on how many variants a run loaded. Without the directory (an older
 * archetype), only {@code target/classes} is read, as before.
 */
public final class JacocoExecParser implements ReportParser<LineCoverage> {

    static final String EXEC = "jacoco.exec";
    static final String SOURCES = "src/main/java";
    /** Beside the exec file: what the agent's {@code classdumpdir} wrote, every class as a JVM loaded it. */
    static final String CLASS_DUMP = "jacoco-classes";

    /** Directories never searched for a module. */
    private static final Set<String> NOT_MODULES = Set.of("node_modules", ".git", VitestJunitParser.REPORTS, "src");

    /** How deep a module may sit under the root. */
    private static final int MAX_DEPTH = 12;

    @Override
    public String kind() {
        return CoverageKind.ID;
    }

    @Override
    public String language() {
        return "java";
    }

    @Override
    public String tool() {
        return "jacoco";
    }

    @Override
    public List<Path> discover(Path root) {
        Path exec = root.resolve(VitestJunitParser.REPORTS).resolve(EXEC);
        return Files.isRegularFile(exec) ? List.of(exec) : List.of();
    }

    @Override
    public LineCoverage parse(Path file, StepContext step) throws IOException {
        ExecFileLoader loader = new ExecFileLoader();
        loader.load(file.toFile());
        ExecutionDataStore executions = loader.getExecutionDataStore();
        LineCoverage coverage = LineCoverage.of(language(), tool());
        Map<String, Lines> byClass = new HashMap<>();
        for (Path classes : classDirectories(step.root())) {
            Path module = classes.getParent().getParent();
            for (Path type : classFiles(classes)) {
                IClassCoverage original = analyze(type, executions);
                if (original == null || original.getSourceFileName() == null) {
                    continue;
                }
                String relative = (original.getPackageName().isEmpty() ? "" : original.getPackageName() + "/")
                        + original.getSourceFileName();
                Path sourceFile = module.resolve(SOURCES).resolve(relative);
                if (!Files.isRegularFile(sourceFile)) {
                    continue;
                }
                byClass.put(original.getName(), new Lines(step.relative(sourceFile), lines(original)));
            }
        }
        for (Path type : dumpedClassFiles(file.resolveSibling(CLASS_DUMP))) {
            IClassCoverage variant = analyze(type, executions);
            if (variant == null || executions.get(variant.getId()) == null) {
                continue;
            }
            Lines original = byClass.get(variant.getName());
            if (original == null) {
                continue;
            }
            lines(variant).forEach((line, covered) -> {
                if (covered) {
                    original.lines().computeIfPresent(line, (l, before) -> true);
                }
            });
        }
        for (Lines lines : byClass.values()) {
            lines.lines().forEach((line, covered) -> coverage.line(lines.source(), line, covered));
        }
        return coverage;
    }

    /** A class's coverable lines in its source file, and whether each is covered. */
    private record Lines(String source, Map<Integer, Boolean> lines) {
    }

    /**
     * One class file against the exec data, on its own: a variant shares its name with the original,
     * and one {@link CoverageBuilder} refuses two classes of one name. Null when JaCoCo cannot read
     * it (a newer class file version, a broken file): that costs its own lines and nothing else.
     */
    private static IClassCoverage analyze(Path type, ExecutionDataStore executions) {
        CoverageBuilder builder = new CoverageBuilder();
        try (InputStream in = Files.newInputStream(type)) {
            new Analyzer(executions, builder).analyzeClass(in, type.toString());
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
        return builder.getClasses().stream().findFirst().orElse(null);
    }

    /** Line number to covered, for every line with code. */
    private static Map<Integer, Boolean> lines(IClassCoverage type) {
        Map<Integer, Boolean> lines = new HashMap<>();
        for (int line = type.getFirstLine(); line > 0 && line <= type.getLastLine(); line++) {
            switch (type.getLine(line).getStatus()) {
                case ICounter.NOT_COVERED -> lines.put(line, false);
                case ICounter.PARTLY_COVERED, ICounter.FULLY_COVERED -> lines.put(line, true);
                default -> {
                    // EMPTY: no code on the line.
                }
            }
        }
        return lines;
    }

    /** Every class file the agent dumped, or none when it dumped nothing. */
    static List<Path> dumpedClassFiles(Path dump) throws IOException {
        if (!Files.isDirectory(dump)) {
            return List.of();
        }
        return classFiles(dump);
    }

    /** Every {@code <module>/target/classes} under the root, in path order. */
    static List<Path> classDirectories(Path root) {
        List<Path> found = new ArrayList<>();
        try {
            Files.walkFileTree(root, Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (!dir.equals(root) && NOT_MODULES.contains(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (name.equals("target")) {
                        if (Files.isDirectory(dir.resolve("classes"))) {
                            found.add(dir.resolve("classes"));
                        }
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException unreadable) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | UncheckedIOException unreadable) {
            // A tree that cannot be walked has no classes to read.
        }
        found.sort(null);
        return found;
    }

    private static List<Path> classFiles(Path classes) throws IOException {
        try (Stream<Path> all = Files.walk(classes)) {
            return all.filter(p -> p.getFileName().toString().endsWith(".class") && Files.isRegularFile(p))
                    .sorted().toList();
        }
    }
}
