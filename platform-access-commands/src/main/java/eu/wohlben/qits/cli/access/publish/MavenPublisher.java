package eu.wohlben.qits.cli.access.publish;

import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Exclusion;
import org.apache.maven.model.License;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code qits artifacts publish maven}: one declared maven entry of a reactor, flattened, bundled
 * and uploaded, with its content hash (owner decisions of 2026-10-01).
 *
 * <ul>
 *   <li><b>Flattened.</b> The uploaded pom is generated from maven's effective model: no
 *       {@code <parent>}, every version resolved, no dependencyManagement, build, profiles,
 *       repositories or modules, and only the external dependencies (plus linked siblings). A
 *       published pom can never name a reactor parent nobody published, or a sibling version that
 *       was never released.
 *   <li><b>Bundled.</b> Every compile, runtime or provided dependency on a module of the same
 *       reactor is merged into the jar, transitively: its classes and resources, its
 *       {@code META-INF/services} files merged line by line, and any other path present twice with
 *       different bytes refused. Its own external dependencies move into the flattened pom.
 *   <li><b>Linked.</b> A sibling named by {@code --link} stays a pom dependency at its decided
 *       version: the release version when the store holds it at that version, else its newest.
 *       qits-ci orders the calls so a linked sibling is always decided first.
 *   <li><b>A pom-packaging entry is a product</b> (a parent or BOM another repository consumes):
 *       it keeps its dependencyManagement, properties and pluginManagement, with a reactor parent
 *       merged in. Internal reactor parents are simply never declared, and never uploaded.
 * </ul>
 */
final class MavenPublisher {

    private static final Set<String> PUBLISHED_SCOPES = Set.of("compile", "runtime", "provided");

    private final Http http;
    private final Store store;
    private final Console console;
    private final ContentHashes hashes;

    MavenPublisher(Http http, Store store, Console console) {
        this.http = http;
        this.store = store;
        this.console = console;
        this.hashes = new ContentHashes(http, store);
    }

    /** What one entry looks like once built: the jar (or none), the pom, and what the hash needs. */
    record Built(byte[] jar, byte[] pom, Reactor reactor, List<String> bundled) {
    }

    Decision.Outcome publish(Path root, String name, String version, String path, Optional<Path> sbom,
                             List<String> include, List<String> links, boolean ifChanged) {
        Built built = build(root, name, version, path, links);
        String hash = new MavenContentHash().of(BuiltPackage.maven(built.jar()), sbom, include, built.reactor());
        String[] ga = name.split(":", -1);
        return new Decision(hashes, console).decide("maven", name, version, hash, ifChanged,
                () -> upload(ga[0], ga[1], version, built, hash));
    }

    /** A contract jar the packager built: no reactor, no SBOM, always {@code if-changed}. */
    Decision.Outcome publishContract(String name, String version, byte[] jar, String description) {
        String[] ga = name.split(":", -1);
        if (ga.length != 2 || ga[0].isEmpty() || ga[1].isEmpty()) {
            throw CliException.policy("--name '" + name + "' is not groupId:artifactId");
        }
        Built built = new Built(jar, ContractPackager.pom(ga[0], ga[1], version, description), Reactor.NONE, List.of());
        String hash = new MavenContentHash().of(BuiltPackage.maven(jar), Optional.empty(), List.of(), Reactor.NONE);
        return new Decision(hashes, console).decide("maven", name, version, hash, true,
                () -> upload(ga[0], ga[1], version, built, hash));
    }

    Built build(Path root, String name, String version, String path, List<String> links) {
        String[] ga = name.split(":", -1);
        if (ga.length != 2 || ga[0].isEmpty() || ga[1].isEmpty()) {
            throw CliException.policy("--name '" + name + "' is not groupId:artifactId");
        }
        for (String link : links) {
            if (link.split(":", -1).length != 2) {
                throw CliException.policy("--link '" + link + "' is not groupId:artifactId");
            }
        }

        MavenReactor reactor = MavenReactor.read(root, http, store.mavenReadRoots());
        Path dir = reactor.root().resolve(path).normalize();
        MavenReactor.Module module = reactor.moduleAt(dir).orElseThrow(() -> CliException.policy(
                "--path '" + path + "' is not a module of the reactor at " + reactor.root()
                        + " — set path: on the entry to the published module's directory"));
        if (!module.coordinate().equals(name)) {
            throw CliException.policy(path + "/pom.xml is " + module.coordinate() + ", not " + name
                    + " — set path: on the entry");
        }
        Model model = reactor.effective(dir);
        if (!version.equals(model.getVersion())) {
            throw CliException.policy(path + "/pom.xml builds " + name + ":" + model.getVersion() + ", not --version "
                    + version + " — the release did not stamp this module, or the entry names another one");
        }
        for (String link : links) {
            if (!reactor.contains(link)) {
                throw CliException.policy("--link " + link + " is not a module of this reactor; only a reactor "
                        + "sibling can be linked");
            }
        }

        if ("pom".equals(model.getPackaging())) {
            if (!links.isEmpty()) {
                throw CliException.policy(name + " is pom-packaging (a product); it bundles nothing, so --link "
                        + "means nothing on it");
            }
            console.info(name + " is pom-packaging: published as a product, with its dependencyManagement");
            return new Built(null, product(model, reactor, module), Reactor.NONE, List.of());
        }

        Closure closure = closure(reactor, model, Set.copyOf(links));
        for (String link : links) {
            if (!closure.linked().containsKey(link)) {
                throw CliException.policy("--link " + link + " is not a dependency of " + name
                        + ", directly or through a bundled sibling; there is nothing to link");
            }
        }
        Map<String, String> decided = new LinkedHashMap<>();
        for (String link : closure.linked().keySet()) {
            decided.put(link, decidedVersion(link, version));
        }

        byte[] jar = jar(reactor, module, model, closure.bundled());
        byte[] pom = flattened(model, closure, decided);
        if (!closure.bundled().isEmpty()) {
            console.info(name + " bundles " + String.join(", ", closure.bundled()));
        }
        decided.forEach((link, at) -> console.info(name + " links " + link + " at " + at));
        return new Built(jar, pom, new Reactor(Set.copyOf(closure.bundled()), decided),
                List.copyOf(closure.bundled()));
    }

    // --- the dependency closure --------------------------------------------------------------------

    /**
     * {@code bundled}: every internal module merged in, in discovery order. {@code external}: the
     * dependencies the flattened pom lists, by {@code g:a}. {@code linked}: the linked siblings
     * reached, with the dependency that reached them first.
     */
    record Closure(List<String> bundled, Map<String, Dependency> external, Map<String, Dependency> linked) {
    }

    private Closure closure(MavenReactor reactor, Model root, Set<String> links) {
        String self = root.getGroupId() + ":" + root.getArtifactId();
        List<String> bundled = new ArrayList<>();
        Map<String, Dependency> external = new LinkedHashMap<>();
        Map<String, Dependency> linked = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        seen.add(self);
        Deque<Model> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Model current = queue.poll();
            for (Dependency dependency : current.getDependencies()) {
                String scope = dependency.getScope() == null ? "compile" : dependency.getScope();
                if (!PUBLISHED_SCOPES.contains(scope)) {
                    continue;
                }
                String ga = dependency.getGroupId() + ":" + dependency.getArtifactId();
                if (reactor.contains(ga)) {
                    if (links.contains(ga)) {
                        linked.putIfAbsent(ga, dependency);
                    } else if (seen.add(ga)) {
                        bundled.add(ga);
                        queue.add(reactor.effective(reactor.module(ga).dir()));
                    }
                    continue;
                }
                Dependency kept = external.get(ga);
                if (kept == null) {
                    external.put(ga, dependency.clone());
                } else if (!kept.getVersion().equals(dependency.getVersion())) {
                    String managed = managedVersion(root, ga);
                    if (managed == null) {
                        throw CliException.policy(ga + " is needed at " + kept.getVersion() + " and at "
                                + dependency.getVersion() + " by the modules bundled into "
                                + self + ", and " + self + " manages no version for it — manage one");
                    }
                    kept.setVersion(managed);
                }
            }
        }
        return new Closure(bundled, external, linked);
    }

    private static String managedVersion(Model root, String ga) {
        DependencyManagement management = root.getDependencyManagement();
        if (management == null) {
            return null;
        }
        for (Dependency managed : management.getDependencies()) {
            if (ga.equals(managed.getGroupId() + ":" + managed.getArtifactId())) {
                return managed.getVersion();
            }
        }
        return null;
    }

    /** V when the store holds the sibling at this release's version, else its newest U. */
    private String decidedVersion(String link, String version) {
        if (hashes.version("maven", link, version).isPresent()) {
            return version;
        }
        return hashes.newest("maven", link)
                .map(ContentHashes.Stored::version)
                .orElseThrow(() -> CliException.policy("linked " + link + " has not been decided in this release"));
    }

    // --- the jar -----------------------------------------------------------------------------------

    private byte[] jar(MavenReactor reactor, MavenReactor.Module module, Model model, List<String> bundled) {
        byte[] own = readJar(module.dir(), model.getArtifactId(), model.getVersion());
        if (bundled.isEmpty()) {
            return own;
        }
        Map<String, Archives.Entry> entries = new LinkedHashMap<>();
        Map<String, String> owners = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> services = new LinkedHashMap<>();
        merge(module.coordinate(), Archives.readJar(own, module.coordinate()), true, entries, owners, services);
        for (String ga : bundled) {
            MavenReactor.Module sibling = reactor.module(ga);
            Model siblingModel = reactor.effective(sibling.dir());
            if ("pom".equals(siblingModel.getPackaging())) {
                continue;
            }
            byte[] bytes = readJar(sibling.dir(), siblingModel.getArtifactId(), siblingModel.getVersion());
            merge(ga, Archives.readJar(bytes, ga), false, entries, owners, services);
        }
        List<Archives.Entry> all = new ArrayList<>(entries.values());
        services.forEach((name, lines) -> all.add(new Archives.Entry(name,
                (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8))));
        return Archives.writeJar(all, false);
    }

    private static void merge(String owner, List<Archives.Entry> jar, boolean own, Map<String, Archives.Entry> entries,
                              Map<String, String> owners, Map<String, LinkedHashSet<String>> services) {
        for (Archives.Entry entry : jar) {
            String name = entry.name();
            if (name.startsWith("META-INF/maven/") || name.equals("META-INF/maven/")) {
                continue;
            }
            if (name.equals("META-INF/MANIFEST.MF") && !own) {
                continue;
            }
            if (name.startsWith("META-INF/services/") && !entry.directory()) {
                LinkedHashSet<String> lines = services.computeIfAbsent(name, n -> new LinkedHashSet<>());
                for (String line : new String(entry.bytes(), StandardCharsets.UTF_8).split("\\R")) {
                    if (!line.isBlank()) {
                        lines.add(line.strip());
                    }
                }
                continue;
            }
            Archives.Entry present = entries.get(name);
            if (present == null) {
                entries.put(name, entry);
                owners.put(name, owner);
            } else if (!entry.directory() && !java.util.Arrays.equals(present.bytes(), entry.bytes())) {
                throw CliException.policy(name + " is in both " + owners.get(name) + " and " + owner
                        + " with different bytes; bundling would have to drop one of them");
            }
        }
    }

    private static byte[] readJar(Path dir, String artifactId, String version) {
        Path jar = dir.resolve("target").resolve(artifactId + "-" + version + ".jar");
        if (!Files.isRegularFile(jar)) {
            throw CliException.policy("no jar at " + jar + " — the release step must package every published module "
                    + "and every module it bundles before publishing");
        }
        try {
            return Files.readAllBytes(jar);
        } catch (IOException e) {
            throw CliException.transport("cannot read " + jar + ": " + e.getMessage(), e);
        }
    }

    // --- the poms ----------------------------------------------------------------------------------

    private static byte[] flattened(Model model, Closure closure, Map<String, String> decided) {
        Model flat = header(model, "jar");
        Map<String, Dependency> listed = new LinkedHashMap<>();
        closure.external().forEach((ga, dependency) -> listed.put(ga, published(dependency, dependency.getVersion())));
        closure.linked().forEach((ga, dependency) -> listed.put(ga, published(dependency, decided.get(ga))));
        flat.setDependencies(new ArrayList<>(listed.values()));
        return write(flat);
    }

    private static Dependency published(Dependency source, String version) {
        Dependency dependency = new Dependency();
        dependency.setGroupId(source.getGroupId());
        dependency.setArtifactId(source.getArtifactId());
        dependency.setVersion(version);
        if (source.getType() != null && !source.getType().equals("jar")) {
            dependency.setType(source.getType());
        }
        if (source.getClassifier() != null && !source.getClassifier().isEmpty()) {
            dependency.setClassifier(source.getClassifier());
        }
        if (source.getScope() != null && !source.getScope().equals("compile")) {
            dependency.setScope(source.getScope());
        }
        if (source.isOptional()) {
            dependency.setOptional(true);
        }
        for (Exclusion exclusion : source.getExclusions()) {
            Exclusion copy = new Exclusion();
            copy.setGroupId(exclusion.getGroupId());
            copy.setArtifactId(exclusion.getArtifactId());
            dependency.addExclusion(copy);
        }
        return dependency;
    }

    private static byte[] product(Model model, MavenReactor reactor, MavenReactor.Module module) {
        Model product = header(model, "pom");
        Model raw = MavenReactor.raw(module.dir().resolve("pom.xml"));
        Parent parent = raw.getParent();
        if (parent != null && !reactor.contains(parent.getGroupId() + ":" + parent.getArtifactId())) {
            // An external parent stays a reference: it is a published product of its own.
            Parent copy = new Parent();
            copy.setGroupId(parent.getGroupId());
            copy.setArtifactId(parent.getArtifactId());
            copy.setVersion(parent.getVersion());
            copy.setRelativePath("");
            product.setParent(copy);
        }
        product.setProperties(model.getProperties());
        product.setDependencyManagement(model.getDependencyManagement());
        Build build = model.getBuild();
        if (build != null && build.getPluginManagement() != null) {
            Build kept = new Build();
            kept.setPluginManagement(build.getPluginManagement());
            product.setBuild(kept);
        }
        return write(product);
    }

    private static Model header(Model model, String packaging) {
        Model out = new Model();
        out.setModelVersion("4.0.0");
        out.setGroupId(model.getGroupId());
        out.setArtifactId(model.getArtifactId());
        out.setVersion(model.getVersion());
        out.setPackaging(packaging);
        out.setName(model.getName());
        out.setDescription(model.getDescription());
        out.setUrl(model.getUrl());
        for (License license : model.getLicenses()) {
            out.addLicense(license.clone());
        }
        return out;
    }

    static byte[] write(Model model) {
        StringWriter text = new StringWriter();
        try {
            new MavenXpp3Writer().write(text, model);
        } catch (IOException e) {
            throw new IllegalStateException("writing a pom in memory failed", e);
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    // --- the upload --------------------------------------------------------------------------------

    /** The jar first, then the pom with the hash: the pom is the version's "published" marker. */
    private void upload(String groupId, String artifactId, String version, Built built, String hash) {
        if (built.jar() != null) {
            put(store.mavenFile(groupId, artifactId, version, artifactId + "-" + version + ".jar"), built.jar(),
                    "application/java-archive", Map.of(), true);
        }
        put(store.mavenFile(groupId, artifactId, version, artifactId + "-" + version + ".pom"), built.pom(),
                "application/xml", Map.of("X-Artifacts-Content-Hash", hash), false);
        console.info("uploaded " + groupId + ":" + artifactId + ":" + version);
    }

    private void put(String url, byte[] bytes, String contentType, Map<String, String> headers, boolean jar) {
        Http.Response response = http.putBytes(url, bytes, contentType, headers);
        int status = response.status();
        if (status == 200 || status == 201) {
            return;
        }
        String message = "PUT " + url + " answered HTTP " + status
                + (response.excerpt().isEmpty() ? "" : ": " + response.excerpt());
        if (status == 403 && jar) {
            throw CliException.policy(message + " — the store already holds different bytes at this version. A "
                    + "re-run built a different jar, which only a non-reproducible build does: set "
                    + "project.build.outputTimestamp so the jar is the same bytes every time");
        }
        throw status >= 500 ? CliException.transport(message) : CliException.policy(message);
    }
}
