package eu.wohlben.qits.cli.access.database;

import io.quarkus.hibernate.orm.panache.PanacheEntity;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/**
 * A compiled maven reactor on disk, as {@code qits database diagram} finds one after {@code compile}
 * and {@code dependency:build-classpath}, made from the fixture classes under {@code fixtures/}:
 *
 * <pre>
 *   pom.xml                 modules app, libs
 *   app/                    model, stray and unsupported; units model, unsupported and "library"
 *                           (quoted), a profiled key and an expression; the classpath file
 *   libs/pom.xml            module core
 *   libs/core/              other (and other.sub); the &lt;default&gt; unit, in microprofile-config
 *   ../repo/qits-fixture-library-1.0.jar   library, with pom.properties
 *   ../repo/fixture-plain-2.3.4.jar        plain, without, and a unit of its own that is not drawn
 * </pre>
 *
 * The classpath also names the Panache jar the test runs with, which holds PanacheEntity.
 */
final class FixtureProject {

    static final String FIXTURES = "eu.wohlben.qits.cli.access.database.fixtures";

    private FixtureProject() {
    }

    /** Makes the project under {@code dir} (emptied first) and returns its root, {@code dir/project}. */
    static Path create(Path dir) throws IOException {
        return create(dir, List.of());
    }

    /** The same, with {@code extra} lines at the end of app's application.properties. */
    static Path create(Path dir, List<String> extra) throws IOException {
        delete(dir);
        Path root = dir.resolve("project");
        Path repo = dir.resolve("repo");
        Files.createDirectories(repo);

        pom(root, "fixture-root", List.of("app", "libs"));
        pom(root.resolve("app"), "fixture-app", List.of());
        pom(root.resolve("libs"), "fixture-libs", List.of("core"));
        pom(root.resolve("libs/core"), "fixture-core", List.of());

        Path app = root.resolve("app/target/classes");
        copyPackage("model", app);
        copyPackage("stray", app);
        copyPackage("unsupported", app);
        write(app.resolve("application.properties"), String.join("\n",
                "quarkus.hibernate-orm.model.datasource=main",
                "quarkus.hibernate-orm.model.packages=" + FIXTURES + ".model",
                "quarkus.hibernate-orm.unsupported.packages=" + FIXTURES + ".unsupported",
                "quarkus.hibernate-orm.\"library\".packages=" + FIXTURES + ".library, " + FIXTURES + ".plain",
                "%dev.quarkus.hibernate-orm.devonly.packages=" + FIXTURES + ".stray",
                "quarkus.hibernate-orm.expr.packages=${FIXTURE_PACKAGES}",
                "quarkus.hibernate-orm.database.generation=none",
                String.join("\n", extra),
                ""));

        Path core = root.resolve("libs/core/target/classes");
        copyPackage("other", core);
        write(core.resolve("META-INF/microprofile-config.properties"),
                "quarkus.hibernate-orm.packages=" + FIXTURES + ".other\n");

        Path library = repo.resolve("qits-fixture-library-1.0.jar");
        jar(library, "library", Map.of("META-INF/maven/eu.wohlben.qits/qits-fixture-library/pom.properties",
                "groupId=eu.wohlben.qits\nartifactId=qits-fixture-library\nversion=1.0\n"));
        Path plain = repo.resolve("fixture-plain-2.3.4.jar");
        jar(plain, "plain", Map.of("META-INF/microprofile-config.properties",
                "quarkus.hibernate-orm.jarunit.packages=" + FIXTURES + ".plain\n"));

        String panache = panacheJar().toString();
        write(root.resolve("app/target/qits-classpath.txt"), String.join(java.io.File.pathSeparator, panache,
                library.toString(), plain.toString(), app.toString(), repo.resolve("gone-1.0.jar").toString()));
        write(root.resolve("libs/core/target/qits-classpath.txt"), panache);
        return root;
    }

    /** The jar PanacheEntity was loaded from. */
    static Path panacheJar() {
        try {
            return Path.of(PanacheEntity.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException wrong) {
            throw new IllegalStateException(wrong);
        }
    }

    /** Where the test classes are: target/test-classes. */
    static Path testClasses() {
        try {
            return Path.of(FixtureProject.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException wrong) {
            throw new IllegalStateException(wrong);
        }
    }

    private static Path packageDir(String pkg) {
        return testClasses().resolve((FIXTURES + "." + pkg).replace('.', '/'));
    }

    private static void copyPackage(String pkg, Path classes) throws IOException {
        Path from = packageDir(pkg);
        Path to = classes.resolve((FIXTURES + "." + pkg).replace('.', '/'));
        try (Stream<Path> files = Files.walk(from)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path target = to.resolve(from.relativize(file).toString());
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            }
        }
    }

    private static void jar(Path jar, String pkg, Map<String, String> extra) throws IOException {
        Path from = packageDir(pkg);
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream zip = new JarOutputStream(out);
             Stream<Path> files = Files.walk(from)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String name = (FIXTURES + "." + pkg).replace('.', '/') + "/" + from.relativize(file).toString()
                        .replace('\\', '/');
                zip.putNextEntry(new JarEntry(name));
                zip.write(Files.readAllBytes(file));
                zip.closeEntry();
            }
            for (Map.Entry<String, String> entry : extra.entrySet()) {
                zip.putNextEntry(new JarEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    private static void pom(Path dir, String artifactId, List<String> modules) throws IOException {
        StringBuilder pom = new StringBuilder("<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                + "  <modelVersion>4.0.0</modelVersion>\n  <groupId>eu.wohlben.qits.fixture</groupId>\n"
                + "  <artifactId>" + artifactId + "</artifactId>\n  <version>1</version>\n");
        if (!modules.isEmpty()) {
            pom.append("  <packaging>pom</packaging>\n  <modules>\n");
            modules.forEach(module -> pom.append("    <module>").append(module).append("</module>\n"));
            pom.append("  </modules>\n");
        }
        pom.append("</project>\n");
        write(dir.resolve("pom.xml"), pom.toString());
    }

    static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    static void delete(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException failed) {
                    throw new UncheckedIOException(failed);
                }
            });
        }
    }
}
