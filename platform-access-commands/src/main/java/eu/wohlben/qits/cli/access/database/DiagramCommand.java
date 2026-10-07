package eu.wohlben.qits.cli.access.database;

import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@code qits database diagram}: the repository's entity diagram, generated from its compiled
 * classes into {@code docs/database/} (epic qits-760). The entity-diagram automation runs it on every
 * release request fold, after a {@code compile} and a {@code dependency:build-classpath}; a person
 * runs it the same way.
 * <p>
 * The generator owns its files, not the directory: it writes every unit's file and deletes only the
 * {@code *.md} files there that carry its header and that it did not write this time. A hand-written
 * file beside them is left alone. Reads local files only, so it touches no session and no platform.
 */
@TuiCommand(interaction = Interaction.CI_ONLY)
@CommandLine.Command(name = "diagram", mixinStandardHelpOptions = true,
        description = {"Write the entity diagram of a maven repository's JPA mapping: one Markdown file with a "
                        + "Mermaid erDiagram per persistence unit it declares, read from its compiled classes.",
                "Compile first: the classes come from every reactor module's target/classes and the libraries from "
                        + "each module's target/qits-classpath.txt, which `dependency:build-classpath "
                        + "-Dmdep.outputFile=target/qits-classpath.txt -DincludeScope=runtime` writes. One line "
                        + "per file on stdout: `written docs/database/ci.md`, `unchanged ...` or `deleted ...`."},
        footerHeading = HelpText.EXAMPLES,
        footer = {"  ./mvnw -q -Dmaven.test.skip=true test-compile dependency:build-classpath "
                        + "-Dmdep.outputFile=target/qits-classpath.txt -DincludeScope=runtime",
                "  qits database diagram",
                "  qits database diagram --root . --out docs/database --check",
                "",
                "- test-compile rather than compile: dependency:build-classpath resolves the test scope too, and in "
                        + "a reactor where one module depends on another's test jar that resolution fails at compile. "
                        + "-Dmaven.test.skip=true still compiles no test. The scope flag is -DincludeScope: the plugin "
                        + "reads no -Dmdep.includeScope, and with it the file lists every scope, the test jars too.",
                "- A unit is an unprofiled quarkus.hibernate-orm.<unit>.packages key (quarkus.hibernate-orm.packages "
                        + "is <default>, written to default.md) in a reactor module's own application.properties or "
                        + "META-INF/microprofile-config.properties, never a library's. A profiled key or a $${...} value "
                        + "is ignored, with a warning on stderr.",
                "- A unit draws every @Entity in its packages and their sub-packages, compiled here or in a library "
                        + "jar; each table names its origin, the reactor module or the library's artifactId. An entity "
                        + "of the repository that no unit lists is drawn in a file named after its Java package.",
                "- Names are Hibernate's under Quarkus' defaults: JPA's implicit naming, and every name as the mapping "
                        + "spells it, unless the unit's physical-naming-strategy key names "
                        + "CamelCaseToUnderscoresNamingStrategy (then snake_case). A construct the diagram does not "
                        + "draw (@OneToOne, @ManyToMany, @EmbeddedId, @Inheritance, @SecondaryTable, @Formula and "
                        + "others) is listed under it as `Not drawn`, never refused.",
                "- Deletes only the *.md files under --out that start with the generator's header and were not written "
                        + "this time. A file without the header is never touched.",
                "- The files hold no version, time or absolute path: two runs over the same classes write the same "
                        + "bytes.",
                "- Reads local files only: it needs no sign-in and calls no service."},
        exitCodeListHeading = HelpText.EXIT_CODES,
        exitCodeList = {"0:Done, or nothing to draw (`no JPA entities found`).",
                "1:With --check: a file would be written or deleted. Otherwise: a file could not be read or written.",
                "2:Used wrongly: --root is not a directory, or nothing is compiled under it (`compile first: no "
                        + "target/classes under <root>`)."})
public class DiagramCommand extends PlatformCommand {

    @CommandLine.Option(names = "--root", paramLabel = "<dir>",
            description = "The repository: the directory of its root pom.xml. Default: the working directory.")
    String root;

    @CommandLine.Option(names = "--out", paramLabel = "<dir>",
            description = "Where the files go. Default: docs/database under --root.")
    String out;

    @CommandLine.Option(names = "--check",
            description = "Write and delete nothing; exit 1 when a file would change. Lines say `would write` and "
                    + "`would delete`.")
    boolean check;

    @Override
    protected int execute(CliContext context) throws CliFailure {
        Path rootDir = Path.of(root == null ? "." : root).toAbsolutePath().normalize();
        if (!Files.isDirectory(rootDir)) {
            throw new CliFailure("--root " + rootDir + " is not a directory.", CliFailure.USAGE);
        }
        Path outDir = (out == null ? rootDir.resolve("docs").resolve("database") : Path.of(out)).toAbsolutePath()
                .normalize();

        List<EntityModelSource> detected = new ArrayList<>();
        for (EntityModelSource source : EntityModelSources.all(line -> context.err().println(line))) {
            if (source.detects(rootDir)) {
                detected.add(source);
            }
        }
        if (detected.isEmpty()) {
            throw new CliFailure("compile first: no target/classes under " + rootDir, CliFailure.USAGE);
        }

        Map<String, String> files = new LinkedHashMap<>();
        for (EntityModelSource source : detected) {
            EntityDiagramFormat format = EntityModelSources.format(source);
            List<UnitModel> units;
            try {
                units = source.read(rootDir);
            } catch (IOException | RuntimeException failed) {
                throw new CliFailure("Could not read the " + source.id() + " mapping under " + rootDir + ": "
                        + failed.getMessage(), CliFailure.FAILED);
            }
            for (UnitModel unit : units) {
                if (files.putIfAbsent(unit.fileName(), format.render(unit)) != null) {
                    context.err().println("warning: two units write " + unit.fileName() + "; the first is kept");
                }
            }
        }

        boolean changed = false;
        try {
            for (Map.Entry<String, String> file : files.entrySet()) {
                Path path = outDir.resolve(file.getKey());
                byte[] bytes = file.getValue().getBytes(StandardCharsets.UTF_8);
                if (Files.isRegularFile(path) && java.util.Arrays.equals(Files.readAllBytes(path), bytes)) {
                    context.out().println("unchanged " + display(rootDir, path));
                    continue;
                }
                changed = true;
                if (check) {
                    context.out().println("would write " + display(rootDir, path));
                } else {
                    Files.createDirectories(outDir);
                    Files.write(path, bytes);
                    context.out().println("written " + display(rootDir, path));
                }
            }
            for (Path stale : stale(outDir, files.keySet())) {
                changed = true;
                if (check) {
                    context.out().println("would delete " + display(rootDir, stale));
                } else {
                    Files.delete(stale);
                    context.out().println("deleted " + display(rootDir, stale));
                }
            }
        } catch (IOException failed) {
            throw new CliFailure("Could not write under " + outDir + ": " + failed.getMessage(), CliFailure.FAILED);
        }
        if (files.isEmpty()) {
            context.out().println("no JPA entities found");
        }
        return check && changed ? CliFailure.FAILED : 0;
    }

    /** The generated files under {@code outDir} that this run did not write. */
    private static List<Path> stale(Path outDir, java.util.Set<String> written) throws IOException {
        if (!Files.isDirectory(outDir)) {
            return List.of();
        }
        List<Path> stale = new ArrayList<>();
        try (Stream<Path> files = Files.list(outDir)) {
            for (Path path : files.sorted().toList()) {
                String name = path.getFileName().toString();
                if (!name.endsWith(".md") || written.contains(name) || !Files.isRegularFile(path)) {
                    continue;
                }
                if (MermaidEntityDiagramFormat.isGenerated(head(path))) {
                    stale.add(path);
                }
            }
        }
        return stale;
    }

    /** The start of a file, enough for its first line. */
    private static String head(Path path) throws IOException {
        try (var in = Files.newInputStream(path)) {
            return new String(in.readNBytes(512), StandardCharsets.UTF_8);
        }
    }

    private static String display(Path root, Path path) {
        return path.startsWith(root) ? root.relativize(path).toString().replace('\\', '/') : path.toString();
    }
}
