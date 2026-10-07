package eu.wohlben.qits.cli.access.database;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.Index;
import org.jboss.jandex.IndexReader;
import org.jboss.jandex.Indexer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * What the jpa source reads: the reactor's own {@code target/classes} and the runtime classpath its
 * modules' {@code target/qits-classpath.txt} name, as one pool of classes.
 * <p>
 * The reactor's classes are indexed with Jandex up front, all of them; that is the repository's own
 * code and it is small. A jar is only listed (its class names, which is cheap), and a class of a
 * jar is indexed the first time somebody asks for it: the classes in a unit's packages and the
 * superclass chains and attribute types those lead to. A jar's own {@code META-INF/jandex.idx} is
 * used when it carries one. Nothing here loads a class, so the repository's code never runs.
 */
final class JpaInputs implements AutoCloseable {

    /** The file {@code dependency:build-classpath -Dmdep.outputFile=target/qits-classpath.txt} writes. */
    static final String CLASSPATH_FILE = "target/qits-classpath.txt";

    private static final Pattern VERSION_SUFFIX = Pattern.compile("^(.+?)-\\d[^-]*(?:-.*)?$");

    /** One reactor module: its directory and the name tables it compiled carry as their origin. */
    record Module(String name, Path dir) {

        Path classes() {
            return dir.resolve("target").resolve("classes");
        }
    }

    private final Path root;
    private final List<Module> modules;
    private final Index reactor;
    private final Map<DotName, String> reactorOrigin;
    private final Map<String, Jar> jarOf;
    private final List<Jar> jars;
    private final Map<DotName, Optional<ClassInfo>> resolved = new HashMap<>();

    private JpaInputs(Path root, List<Module> modules, Index reactor, Map<DotName, String> reactorOrigin,
                      Map<String, Jar> jarOf, List<Jar> jars) {
        this.root = root;
        this.modules = modules;
        this.reactor = reactor;
        this.reactorOrigin = reactorOrigin;
        this.jarOf = jarOf;
        this.jars = jars;
    }

    /** The modules {@code <modules>} reaches from {@code root/pom.xml}, root first; just the root without one. */
    static List<Module> modules(Path root) {
        Path normal = root.toAbsolutePath().normalize();
        Map<Path, Module> found = new LinkedHashMap<>();
        walk(normal, normal, found, 0);
        return List.copyOf(found.values());
    }

    /** The modules that hold a {@code target/classes}. */
    static List<Module> compiledModules(Path root) {
        return modules(root).stream().filter(module -> Files.isDirectory(module.classes())).toList();
    }

    private static void walk(Path root, Path dir, Map<Path, Module> into, int depth) {
        if (into.containsKey(dir) || depth > 16) {
            return;
        }
        Path pom = dir.resolve("pom.xml");
        Model model = Files.isRegularFile(pom) ? readPom(pom) : null;
        String name;
        if (dir.equals(root)) {
            // The root's directory name is wherever the checkout happened to land; its artifactId is
            // the same on every machine.
            name = model != null && model.getArtifactId() != null ? model.getArtifactId() : ".";
        } else {
            name = root.relativize(dir).toString().replace('\\', '/');
        }
        into.put(dir, new Module(name, dir));
        if (model == null) {
            return;
        }
        for (String child : model.getModules()) {
            Path childDir = dir.resolve(child.strip()).normalize();
            if (childDir.getFileName() != null && childDir.getFileName().toString().endsWith(".xml")) {
                childDir = childDir.getParent();
            }
            if (childDir != null && Files.isDirectory(childDir)) {
                walk(root, childDir, into, depth + 1);
            }
        }
    }

    private static Model readPom(Path pom) {
        try (InputStream in = Files.newInputStream(pom)) {
            return new MavenXpp3Reader().read(in, false);
        } catch (Exception unreadable) {
            return null;
        }
    }

    /** Indexes the reactor and lists the classpath's jars. */
    static JpaInputs open(Path root, Consumer<String> warnings) throws IOException {
        Path normal = root.toAbsolutePath().normalize();
        List<Module> modules = compiledModules(normal);
        Indexer indexer = new Indexer();
        Map<DotName, String> origin = new HashMap<>();
        for (Module module : modules) {
            List<Path> classFiles;
            try (Stream<Path> walk = Files.walk(module.classes())) {
                classFiles = walk.filter(path -> isClassFile(path.getFileName().toString()))
                        .filter(Files::isRegularFile).sorted().toList();
            }
            for (Path classFile : classFiles) {
                DotName name;
                try (InputStream in = Files.newInputStream(classFile)) {
                    name = indexer.indexWithSummary(in).name();
                } catch (IOException | RuntimeException unreadable) {
                    warnings.accept("warning: skipped " + normal.relativize(classFile)
                            + ": not a class file Jandex reads (" + unreadable.getMessage() + ")");
                    continue;
                }
                origin.putIfAbsent(name, module.name());
            }
        }
        Index reactor = indexer.complete();

        Map<String, Jar> jarOf = new HashMap<>();
        List<Jar> jars = new ArrayList<>();
        for (Path entry : classpath(modules)) {
            if (!Files.isRegularFile(entry) || !entry.getFileName().toString().endsWith(".jar")) {
                // A reactor module's target/classes (indexed above) or test-classes, or an entry that
                // is gone: none of them is a library's classes.
                continue;
            }
            Jar jar;
            try {
                jar = Jar.list(entry);
            } catch (IOException unreadable) {
                warnings.accept("warning: skipped " + entry + ": " + unreadable.getMessage());
                continue;
            }
            jars.add(jar);
            for (String name : jar.classes) {
                jarOf.putIfAbsent(name, jar);
            }
        }
        return new JpaInputs(normal, modules, reactor, origin, jarOf, jars);
    }

    /** Every entry of every module's classpath file, in module order, each once. */
    private static Collection<Path> classpath(List<Module> modules) throws IOException {
        Set<Path> entries = new LinkedHashSet<>();
        for (Module module : modules) {
            Path file = module.dir().resolve(CLASSPATH_FILE);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            String content = Files.readString(file, StandardCharsets.UTF_8).strip();
            if (content.isEmpty()) {
                continue;
            }
            for (String entry : content.split(java.io.File.pathSeparator)) {
                if (!entry.isBlank()) {
                    entries.add(Path.of(entry.strip()));
                }
            }
        }
        return entries;
    }

    private static boolean isClassFile(String fileName) {
        return fileName.endsWith(".class") && !fileName.equals("module-info.class")
                && !fileName.equals("package-info.class");
    }

    Path root() {
        return root;
    }

    List<Module> modules() {
        return modules;
    }

    /** The class of this name, from the reactor or a jar; empty when neither has it. */
    Optional<ClassInfo> classInfo(DotName name) {
        Optional<ClassInfo> known = resolved.get(name);
        if (known != null) {
            return known;
        }
        ClassInfo found = reactor.getClassByName(name);
        if (found == null) {
            Jar jar = jarOf.get(name.toString());
            if (jar != null) {
                found = jar.classInfo(name);
            }
        }
        Optional<ClassInfo> result = Optional.ofNullable(found);
        resolved.put(name, result);
        return result;
    }

    /** True when the reactor compiled this class. */
    boolean inReactor(DotName name) {
        return reactorOrigin.containsKey(name);
    }

    /** The module that compiled the class, or the artifactId of the jar it came from. */
    String origin(DotName name) {
        String module = reactorOrigin.get(name);
        if (module != null) {
            return module;
        }
        Jar jar = jarOf.get(name.toString());
        return jar == null ? "?" : jar.origin();
    }

    /** Every class the reactor compiled, by name. */
    List<DotName> reactorClasses() {
        List<DotName> names = new ArrayList<>(reactorOrigin.keySet());
        names.sort(null);
        return names;
    }

    /**
     * Every class, reactor or jar, whose package is one of {@code packages} or under one, by name.
     * The jar classes are only named here; nothing is indexed until it is asked for.
     */
    List<DotName> classesIn(Collection<String> packages) {
        TreeSet<String> names = new TreeSet<>();
        for (DotName name : reactorOrigin.keySet()) {
            if (inPackages(name.toString(), packages)) {
                names.add(name.toString());
            }
        }
        for (String name : jarOf.keySet()) {
            if (inPackages(name, packages)) {
                names.add(name);
            }
        }
        return names.stream().map(DotName::createSimple).toList();
    }

    static boolean inPackages(String className, Collection<String> packages) {
        int dot = className.lastIndexOf('.');
        String pkg = dot < 0 ? "" : className.substring(0, dot);
        for (String wanted : packages) {
            if (pkg.equals(wanted) || pkg.startsWith(wanted + ".")) {
                return true;
            }
        }
        return false;
    }

    static String packageOf(DotName name) {
        String text = name.toString();
        int dot = text.lastIndexOf('.');
        return dot < 0 ? "" : text.substring(0, dot);
    }

    @Override
    public void close() {
        for (Jar jar : jars) {
            jar.close();
        }
    }

    /**
     * The artifactId a jar was built as: its {@code META-INF/maven/<g>/<a>/pom.properties}, or else its
     * file name without the version.
     */
    static String artifactId(Path jarPath, ZipFile zip) {
        String fileName = jarPath.getFileName().toString();
        String base = fileName.endsWith(".jar") ? fileName.substring(0, fileName.length() - 4) : fileName;
        TreeMap<String, String> found = new TreeMap<>();
        if (zip != null) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("META-INF/maven/") && name.endsWith("/pom.properties")) {
                    Properties properties = new Properties();
                    try (InputStream in = zip.getInputStream(entry)) {
                        properties.load(in);
                    } catch (IOException | IllegalArgumentException unreadable) {
                        continue;
                    }
                    String artifactId = properties.getProperty("artifactId");
                    if (artifactId != null && !artifactId.isBlank()) {
                        found.put(name, artifactId.strip());
                    }
                }
            }
        }
        // A shaded jar carries the pom.properties of everything it swallowed: the one its own file is
        // named after is the jar's.
        for (String artifactId : found.values()) {
            if (base.equals(artifactId) || base.startsWith(artifactId + "-")) {
                return artifactId;
            }
        }
        if (!found.isEmpty()) {
            return found.firstEntry().getValue();
        }
        Matcher versioned = VERSION_SUFFIX.matcher(base);
        return versioned.matches() ? versioned.group(1) : base;
    }

    /** One jar of the classpath: its class names, and what has been indexed from it so far. */
    static final class Jar {

        private final Path path;
        private final Set<String> classes;
        private final boolean hasIndex;
        private ZipFile zip;
        private Index index;
        private boolean indexRead;
        private String origin;

        private Jar(Path path, Set<String> classes, boolean hasIndex) {
            this.path = path;
            this.classes = classes;
            this.hasIndex = hasIndex;
        }

        static Jar list(Path path) throws IOException {
            Set<String> classes = new LinkedHashSet<>();
            boolean hasIndex = false;
            try (ZipFile zip = new ZipFile(path.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (name.equals("META-INF/jandex.idx")) {
                        hasIndex = true;
                    } else if (!name.startsWith("META-INF/") && isClassFile(name.substring(name.lastIndexOf('/') + 1))) {
                        classes.add(name.substring(0, name.length() - ".class".length()).replace('/', '.'));
                    }
                }
            }
            return new Jar(path, classes, hasIndex);
        }

        String origin() {
            if (origin == null) {
                origin = artifactId(path, open());
            }
            return origin;
        }

        ClassInfo classInfo(DotName name) {
            ZipFile file = open();
            if (file == null) {
                return null;
            }
            if (hasIndex && !indexRead) {
                indexRead = true;
                ZipEntry entry = file.getEntry("META-INF/jandex.idx");
                try (InputStream in = file.getInputStream(entry)) {
                    index = new IndexReader(in).read();
                } catch (IOException | RuntimeException unreadable) {
                    // An index this Jandex cannot read (a newer format): the class files still are.
                    index = null;
                }
            }
            if (index != null) {
                ClassInfo indexed = index.getClassByName(name);
                if (indexed != null) {
                    return indexed;
                }
            }
            ZipEntry entry = file.getEntry(name.toString().replace('.', '/') + ".class");
            if (entry == null) {
                return null;
            }
            try (InputStream in = file.getInputStream(entry)) {
                return Index.singleClass(in);
            } catch (IOException | RuntimeException unreadable) {
                return null;
            }
        }

        private ZipFile open() {
            if (zip == null) {
                try {
                    zip = new ZipFile(path.toFile());
                } catch (IOException unreadable) {
                    // Listed a moment ago and unreadable now: its classes are simply not found.
                    return null;
                }
            }
            return zip;
        }

        void close() {
            if (zip != null) {
                try {
                    zip.close();
                } catch (IOException ignored) {
                    // Read-only; nothing to lose.
                }
                zip = null;
            }
        }
    }
}
