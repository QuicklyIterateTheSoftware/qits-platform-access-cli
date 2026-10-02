package eu.wohlben.qits.cli.access.publish;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelProblem;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.building.StringModelSource;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.resolution.ModelResolver;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * A maven reactor read from disk: every module {@code <modules>} reaches from the root pom, by
 * {@code g:a}, and each module's effective model.
 *
 * <p>The effective model is maven's own ({@code maven-model-builder}, the 3.9 line every wrapper
 * pins): parents merged, properties interpolated, imported BOMs expanded and managed versions
 * injected, exactly what the reactor build saw. A parent is read from the reactor on disk; an
 * external parent or an imported BOM is fetched over {@link Http} from the hosted maven repository
 * ({@code QITS_MAVEN_REGISTRY_URL}) and then the proxy ({@code QITS_MAVEN_PROXY_URL}), with the
 * run's bearer. Nothing here runs {@code mvn}.
 */
final class MavenReactor {

    /** One reactor module: where it is, and its coordinate. */
    record Module(String groupId, String artifactId, Path dir) {

        String coordinate() {
            return groupId + ":" + artifactId;
        }
    }

    private final Path root;
    private final Map<String, Module> modules;
    private final ModelResolver resolver;
    private final Map<Path, Model> effective = new HashMap<>();
    private final ModelBuilder builder = new DefaultModelBuilderFactory().newInstance();

    private MavenReactor(Path root, Map<String, Module> modules, ModelResolver resolver) {
        this.root = root;
        this.modules = modules;
        this.resolver = resolver;
    }

    /** Walks {@code <modules>} from {@code root/pom.xml}, recursively. */
    static MavenReactor read(Path root, Http http, Env env) {
        Path normal = root.toAbsolutePath().normalize();
        Map<String, Module> modules = new LinkedHashMap<>();
        walk(normal, modules);
        return new MavenReactor(normal, modules, new HttpResolver(http, env, modules));
    }

    private static void walk(Path dir, Map<String, Module> into) {
        Model raw = raw(dir.resolve("pom.xml"));
        String groupId = raw.getGroupId() != null ? raw.getGroupId()
                : raw.getParent() != null ? raw.getParent().getGroupId() : null;
        Module module = new Module(groupId, raw.getArtifactId(), dir);
        if (into.putIfAbsent(module.coordinate(), module) != null) {
            return;
        }
        for (String name : raw.getModules()) {
            Path child = dir.resolve(name).normalize();
            if (Files.isRegularFile(child) && child.getFileName().toString().endsWith(".xml")) {
                child = child.getParent();
            }
            walk(child, into);
        }
    }

    static Model raw(Path pom) {
        if (!Files.isRegularFile(pom)) {
            throw CliException.policy("no pom at " + pom);
        }
        try (InputStream in = Files.newInputStream(pom)) {
            return new MavenXpp3Reader().read(in, false);
        } catch (IOException | XmlPullParserException e) {
            throw CliException.policy(pom + " is not a readable pom: " + e.getMessage());
        }
    }

    Path root() {
        return root;
    }

    /** The module at {@code dir}, or empty when the reactor has none there. */
    java.util.Optional<Module> moduleAt(Path dir) {
        Path normal = dir.toAbsolutePath().normalize();
        return modules.values().stream().filter(m -> m.dir().equals(normal)).findFirst();
    }

    Module module(String coordinate) {
        return modules.get(coordinate);
    }

    boolean contains(String coordinate) {
        return modules.containsKey(coordinate);
    }

    /** Maven's effective model of the module at {@code dir}, built once per run. */
    Model effective(Path dir) {
        Path normal = dir.toAbsolutePath().normalize();
        Model cached = effective.get(normal);
        if (cached != null) {
            return cached;
        }
        DefaultModelBuildingRequest request = new DefaultModelBuildingRequest();
        request.setPomFile(normal.resolve("pom.xml").toFile());
        request.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);
        request.setProcessPlugins(false);
        request.setTwoPhaseBuilding(false);
        request.setLocationTracking(false);
        Properties system = new Properties();
        system.putAll(System.getProperties());
        request.setSystemProperties(system);
        request.setModelResolver(resolver);
        Model model;
        try {
            model = builder.build(request).getEffectiveModel();
        } catch (ModelBuildingException e) {
            List<String> problems = new ArrayList<>();
            for (ModelProblem problem : e.getProblems()) {
                if (problem.getSeverity() != ModelProblem.Severity.WARNING) {
                    problems.add(problem.getMessage());
                }
            }
            throw CliException.policy("the effective model of " + normal.resolve("pom.xml") + " cannot be built: "
                    + String.join("; ", problems));
        }
        effective.put(normal, model);
        return model;
    }

    /**
     * Parents and imported BOMs: the reactor on disk first, then the hosted repository, then the
     * proxy. A 404 at both is exit 1 (the pom names something that does not exist); a 5xx or no
     * answer is exit 2.
     */
    private static final class HttpResolver implements ModelResolver {

        private final Http http;
        private final Env env;
        private final Map<String, Module> modules;

        HttpResolver(Http http, Env env, Map<String, Module> modules) {
            this.http = http;
            this.env = env;
            this.modules = modules;
        }

        @Override
        public ModelSource resolveModel(String groupId, String artifactId, String version) {
            Module local = modules.get(groupId + ":" + artifactId);
            if (local != null) {
                Model raw = raw(local.dir().resolve("pom.xml"));
                String localVersion = raw.getVersion() != null ? raw.getVersion()
                        : raw.getParent() != null ? raw.getParent().getVersion() : null;
                if (version.equals(localVersion)) {
                    return new FileModelSource(local.dir().resolve("pom.xml").toFile());
                }
            }
            if (version.contains("[") || version.contains("(") || version.contains(",")) {
                throw CliException.policy(groupId + ":" + artifactId + ":" + version
                        + " is a version range; a published pom resolves only fixed versions");
            }
            String coordinate = groupId + ":" + artifactId + ":" + version;
            List<String> tried = new ArrayList<>();
            for (String variable : new String[] {"QITS_MAVEN_REGISTRY_URL", "QITS_MAVEN_PROXY_URL"}) {
                String root = env.get(variable);
                if (root == null) {
                    continue;
                }
                String url = Store.mavenFileUnder(root, groupId, artifactId, version,
                        artifactId + "-" + version + ".pom");
                Http.Response response = http.get(url);
                if (response.status() == 200) {
                    return new StringModelSource(response.body(), url);
                }
                if (response.status() != 404) {
                    String message = "reading the pom of " + coordinate + " answered HTTP " + response.status()
                            + " (" + url + ")";
                    throw response.status() >= 500 ? CliException.transport(message) : CliException.policy(message);
                }
                tried.add(url);
            }
            if (tried.isEmpty()) {
                throw CliException.transport("the pom of " + coordinate + " is not in this reactor, and neither "
                        + "QITS_MAVEN_REGISTRY_URL nor QITS_MAVEN_PROXY_URL is set to read it from");
            }
            throw CliException.policy("the pom of " + coordinate + " is in neither the reactor nor the store ("
                    + String.join(", ", tried) + " answered 404)");
        }

        @Override
        public ModelSource resolveModel(Parent parent) {
            return resolveModel(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
        }

        @Override
        public ModelSource resolveModel(Dependency dependency) {
            return resolveModel(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
        }

        @Override
        public void addRepository(Repository repository) {
            // The platform's two roots are the only repositories this reads from.
        }

        @Override
        public void addRepository(Repository repository, boolean replace) {
        }

        @Override
        public ModelResolver newCopy() {
            return this;
        }
    }
}
