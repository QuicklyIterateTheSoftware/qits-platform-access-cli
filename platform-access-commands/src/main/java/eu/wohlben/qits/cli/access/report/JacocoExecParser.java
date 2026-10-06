package eu.wohlben.qits.cli.access.report;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.ISourceFileCoverage;
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
import java.util.List;
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
 */
public final class JacocoExecParser implements ReportParser<LineCoverage> {

    static final String EXEC = "jacoco.exec";
    static final String SOURCES = "src/main/java";

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
        LineCoverage coverage = LineCoverage.of(language(), tool());
        for (Path classes : classDirectories(step.root())) {
            Path module = classes.getParent().getParent();
            CoverageBuilder builder = new CoverageBuilder();
            Analyzer analyzer = new Analyzer(loader.getExecutionDataStore(), builder);
            for (Path type : classFiles(classes)) {
                try (InputStream in = Files.newInputStream(type)) {
                    analyzer.analyzeClass(in, type.toString());
                } catch (IOException | RuntimeException unreadable) {
                    // One class JaCoCo cannot read (a newer class file version, a broken file) costs
                    // its own lines and nothing else.
                }
            }
            for (ISourceFileCoverage source : builder.getSourceFiles()) {
                String relative = (source.getPackageName().isEmpty() ? "" : source.getPackageName() + "/")
                        + source.getName();
                Path sourceFile = module.resolve(SOURCES).resolve(relative);
                if (!Files.isRegularFile(sourceFile)) {
                    continue;
                }
                String path = step.relative(sourceFile);
                for (int line = source.getFirstLine(); line > 0 && line <= source.getLastLine(); line++) {
                    switch (source.getLine(line).getStatus()) {
                        case ICounter.NOT_COVERED -> coverage.line(path, line, false);
                        case ICounter.PARTLY_COVERED, ICounter.FULLY_COVERED -> coverage.line(path, line, true);
                        default -> {
                            // EMPTY: no code on the line.
                        }
                    }
                }
            }
        }
        return coverage;
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
