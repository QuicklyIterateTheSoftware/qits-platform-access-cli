package eu.wohlben.qits.cli.access.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.maven.model.Model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The platform's contract packagers: a provider's golden masters or a consumer's pacts, packed from
 * a directory into a maven jar or an npm tarball, deterministically and in Java (no JDK tool and no
 * node on the step image).
 *
 * <p><b>The root inside the package is fixed by the kind</b> ({@code golden-masters/} or
 * {@code pacts/}), never by the directory's name, which is where every provider's {@code
 * ClasspathPactLoader} and every {@code golden-masters/index.json} reader look.
 *
 * <p><b>A pacts package holds one provider's pacts, chosen by file name.</b> A consumer keeps all its
 * pacts flat in one directory, named {@code <consumer>_<provider>.json} by repository name, so the
 * pacts package for {@code --provider qits-projects-service} holds exactly the top-level files
 * named {@code *_qits-projects-service.json}. Another provider's pact is not in it, so a change to
 * that pact does not publish, and bump, this provider's package.
 *
 * <p>The jar carries directory entries, and they are mandatory: {@code ClasspathPactLoader} asks the
 * class loader for the {@code pacts/} directory, which only answers when the jar has that entry.
 */
final class ContractPackager {

    private static final ObjectMapper JSON = new ObjectMapper();

    enum Kind {
        GOLDEN_MASTERS("golden-masters"),
        PACTS("pacts");

        final String root;

        Kind(String root) {
            this.root = root;
        }

        static Kind of(String value) {
            for (Kind kind : values()) {
                if (kind.root.equals(value)) {
                    return kind;
                }
            }
            throw CliException.policy("--kind '" + value + "' is golden-masters or pacts");
        }
    }

    private ContractPackager() {
    }

    /** The tree of {@code from}, re-rooted under the kind's root: directories (with {@code /}) and files. */
    static List<Archives.Entry> tree(Path from, String root) {
        if (!Files.isDirectory(from)) {
            throw CliException.policy("--from " + from + " is not a directory");
        }
        List<Archives.Entry> entries = new ArrayList<>();
        entries.add(new Archives.Entry(root + "/", new byte[0]));
        boolean anyFile = false;
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                String relative = from.relativize(path).toString().replace(java.io.File.separatorChar, '/');
                if (relative.isEmpty()) {
                    continue;
                }
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    entries.add(new Archives.Entry(root + "/" + relative + "/", new byte[0]));
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    entries.add(new Archives.Entry(root + "/" + relative, Files.readAllBytes(path)));
                    anyFile = true;
                }
            }
        } catch (IOException e) {
            throw CliException.transport("cannot read " + from + ": " + e.getMessage(), e);
        }
        if (!anyFile) {
            throw CliException.policy("--from " + from + " holds no file; there is no contract to publish");
        }
        return entries;
    }

    /**
     * One provider's pacts: the {@code pacts/} directory entry and every top-level regular file of
     * {@code from} named {@code *_<provider>.json}, sorted by name. Anything else in {@code from} is
     * another provider's, and is left out.
     */
    static List<Archives.Entry> pacts(Path from, String provider) {
        if (!Files.isDirectory(from)) {
            throw CliException.policy("--from " + from + " is not a directory");
        }
        String suffix = "_" + provider + ".json";
        List<Archives.Entry> entries = new ArrayList<>();
        entries.add(new Archives.Entry(Kind.PACTS.root + "/", new byte[0]));
        try (Stream<Path> list = Files.list(from)) {
            for (Path path : list.sorted().toList()) {
                String name = path.getFileName().toString();
                if (name.endsWith(suffix) && name.length() > suffix.length()
                        && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    entries.add(new Archives.Entry(Kind.PACTS.root + "/" + name, Files.readAllBytes(path)));
                }
            }
        } catch (IOException e) {
            throw CliException.transport("cannot read " + from + ": " + e.getMessage(), e);
        }
        if (entries.size() == 1) {
            throw CliException.policy("--from " + from + " holds no *" + suffix
                    + "; a pact with " + provider + " is a file named <consumer>" + suffix);
        }
        return entries;
    }

    /** What a package of {@code kind} holds: the whole tree, or one provider's pacts. */
    static List<Archives.Entry> entries(Path from, Kind kind, String provider) {
        return kind == Kind.PACTS ? pacts(from, provider) : tree(from, kind.root);
    }

    /** The maven jar: {@code META-INF/}, a one-line manifest, and the entries. STORED, sorted. */
    static byte[] jar(Path from, Kind kind, String provider) {
        List<Archives.Entry> entries = new ArrayList<>(entries(from, kind, provider));
        entries.add(new Archives.Entry("META-INF/", new byte[0]));
        entries.add(new Archives.Entry("META-INF/MANIFEST.MF",
                "Manifest-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8)));
        return Archives.writeJar(entries, true);
    }

    /** The minimal pom: the coordinate, {@code jar}, a description. No parent, no dependencies. */
    static byte[] pom(String groupId, String artifactId, String version, String description) {
        Model model = new Model();
        model.setModelVersion("4.0.0");
        model.setGroupId(groupId);
        model.setArtifactId(artifactId);
        model.setVersion(version);
        model.setPackaging("jar");
        model.setDescription(description);
        return MavenPublisher.write(model);
    }

    /** The npm package: a generated {@code package.json} and the tree, under {@code package/}. */
    static NpmPublisher.Package npm(Path from, Kind kind, String provider, String name, String version,
            String description) {
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("name", name);
        manifest.put("version", version);
        manifest.put("description", description);
        manifest.put("license", "UNLICENSED");
        manifest.putArray("files").add(kind.root + "/");
        byte[] bytes;
        try {
            bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest);
        } catch (IOException e) {
            throw new IllegalStateException("writing package.json failed", e);
        }
        List<Archives.Entry> files = entries(from, kind, provider).stream().filter(e -> !e.directory()).toList();
        return NpmPublisher.of(name, version, bytes, files);
    }

    static String description(Kind kind, String application, String provider) {
        return kind == Kind.GOLDEN_MASTERS
                ? application + " golden masters (contracts)"
                : application + " pacts with " + provider + " (contracts)";
    }
}
