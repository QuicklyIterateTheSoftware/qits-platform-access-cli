package eu.wohlben.qits.cli.access.publish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * {@code qits artifacts publish npm}, and the npm half of a contract: the tarball built here, in
 * Java, deterministically, and published with the registry's publish document. Nothing runs
 * {@code npm}.
 *
 * <p><b>Replay</b> is folded in from {@code npm plan}: a version below the registry's
 * {@code latest} (by {@link VersionOrder}) is published under the throwaway tag {@code replay}, so
 * a bootstrap that replays old versions never pulls {@code latest} backwards. Replay is about tags
 * only; "newest" for the hash is by version order, never {@code latest}.
 */
final class NpmPublisher {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Never packed, at any depth. */
    private static final Set<String> NEVER = Set.of("node_modules", ".git", ".npmrc", "package-lock.json",
            "pnpm-lock.yaml");

    private final Http http;
    private final Store store;
    private final Console console;

    NpmPublisher(Http http, Store store, Console console) {
        this.http = http;
        this.store = store;
        this.console = console;
    }

    /** A package ready to publish: its manifest as it goes into the document, and its tarball. */
    record Package(String name, String version, ObjectNode manifest, byte[] tarball) {
    }

    // --- packing a directory -----------------------------------------------------------------------

    /**
     * The package in {@code dir}: {@code package.json} must name {@code name@version} and must not be
     * private. Every regular file, less what npm never packs; narrowed by {@code files} when the
     * manifest has it. A {@code .npmignore} is refused: its rules are npm's to interpret, and a
     * second reading of them here would pack something npm would not.
     */
    static Package pack(Path dir, String name, String version) {
        Path manifestFile = dir.resolve("package.json");
        if (!Files.isRegularFile(manifestFile)) {
            throw CliException.policy("no package.json in " + dir + " — set path: on the entry to the built package's "
                    + "directory");
        }
        ObjectNode manifest = manifest(read(manifestFile), manifestFile.toString());
        String declared = manifest.path("name").asText(null);
        String built = manifest.path("version").asText(null);
        if (!name.equals(declared)) {
            throw CliException.policy(manifestFile + " is " + declared + ", not " + name + " — set path: on the entry");
        }
        if (!version.equals(built)) {
            throw CliException.policy(manifestFile + " says version " + built + ", not --version " + version
                    + " — the build did not stamp the release version");
        }
        if (manifest.path("private").asBoolean(false)) {
            throw CliException.policy(manifestFile + " is private: true; a private package is never published");
        }

        List<String> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path file : (Iterable<Path>) walk::iterator) {
                Path relative = dir.relativize(file);
                if (relative.toString().isEmpty() || skipped(relative)) {
                    continue;
                }
                if (file.getFileName().toString().equals(".npmignore")) {
                    throw CliException.policy(file + ": a .npmignore is not supported; say what to pack with "
                            + "\"files\" in package.json");
                }
                if (Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    files.add(relative.toString().replace(java.io.File.separatorChar, '/'));
                }
            }
        } catch (IOException e) {
            throw CliException.transport("cannot list " + dir + ": " + e.getMessage(), e);
        }

        JsonNode patterns = manifest.get("files");
        if (patterns != null && patterns.isArray()) {
            List<String> globs = new ArrayList<>();
            patterns.forEach(p -> globs.add(p.asText()));
            files = files.stream().filter(f -> alwaysPacked(f) || matchesAny(globs, f)).toList();
        }

        List<Archives.Entry> entries = new ArrayList<>();
        for (String file : files) {
            entries.add(new Archives.Entry("package/" + file, read(dir.resolve(file))));
        }
        return new Package(name, version, manifest, Archives.writeTarGz(entries));
    }

    /** A package whose files are already in hand: the contract tarball. */
    static Package of(String name, String version, byte[] manifestJson, List<Archives.Entry> tree) {
        List<Archives.Entry> entries = new ArrayList<>();
        entries.add(new Archives.Entry("package/package.json", manifestJson));
        for (Archives.Entry entry : tree) {
            entries.add(new Archives.Entry("package/" + entry.name(), entry.bytes()));
        }
        return new Package(name, version, manifest(manifestJson, "the generated package.json"),
                Archives.writeTarGz(entries));
    }

    private static boolean skipped(Path relative) {
        for (Path part : relative) {
            if (NEVER.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    /** npm's own always-packed set at the root: the manifest, a README and a licence. */
    private static boolean alwaysPacked(String file) {
        if (file.contains("/")) {
            return false;
        }
        String lower = file.toLowerCase(Locale.ROOT);
        return file.equals("package.json") || lower.startsWith("readme") || lower.startsWith("license")
                || lower.startsWith("licence");
    }

    /**
     * A {@code files} entry keeps a file when it names the file, a directory above it, or a glob
     * that matches either.
     */
    static boolean matchesAny(List<String> patterns, String file) {
        for (String raw : patterns) {
            String pattern = raw;
            while (pattern.startsWith("./")) {
                pattern = pattern.substring(2);
            }
            while (pattern.startsWith("/")) {
                pattern = pattern.substring(1);
            }
            while (pattern.endsWith("/")) {
                pattern = pattern.substring(0, pattern.length() - 1);
            }
            if (pattern.isEmpty()) {
                continue;
            }
            if (file.equals(pattern) || file.startsWith(pattern + "/")) {
                return true;
            }
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            Path path = Path.of(file);
            for (Path at = path; at != null; at = at.getParent()) {
                if (matcher.matches(at)) {
                    return true;
                }
            }
        }
        return false;
    }

    // --- publishing --------------------------------------------------------------------------------

    /**
     * The decision, the publish and, unless this is a replay, the {@code main} dist-tag (on a
     * re-run too: moving a tag onto the version it already names is idempotent).
     */
    Decision.Outcome publish(Package pkg, Optional<Path> sbom, List<String> include, boolean ifChanged,
                             boolean mainTag) {
        String hash = new NpmContentHash().of(BuiltPackage.npm(pkg.tarball()), sbom, include, Reactor.NONE);
        Decision.Outcome outcome = new Decision(new ContentHashes(http, store), console)
                .decide("npm", pkg.name(), pkg.version(), hash, ifChanged, () -> upload(pkg, hash));
        if (outcome.published() && mainTag) {
            if (replay(pkg.name(), pkg.version())) {
                console.info(pkg.name() + "@" + pkg.version() + " is a replay; the main dist-tag stays where it is");
            } else {
                new Npm(http, store, console).distTag(pkg.name(), pkg.version(), "main");
            }
        }
        return outcome;
    }

    private void upload(Package pkg, String hash) {
        boolean replay = replay(pkg.name(), pkg.version());
        ObjectNode document = JSON.createObjectNode();
        document.put("_id", pkg.name());
        document.put("name", pkg.name());
        document.putObject("dist-tags").put(replay ? "replay" : "latest", pkg.version());
        ObjectNode manifest = pkg.manifest().deepCopy();
        manifest.put("_id", pkg.name() + "@" + pkg.version());
        manifest.putObject("dist").put("shasum", sha1(pkg.tarball()));
        document.putObject("versions").set(pkg.version(), manifest);
        ObjectNode attachment = document.putObject("_attachments").putObject(pkg.name() + "-" + pkg.version() + ".tgz");
        attachment.put("content_type", "application/octet-stream");
        attachment.put("data", Base64.getEncoder().encodeToString(pkg.tarball()));
        attachment.put("length", pkg.tarball().length);

        String url = store.npmPackument(pkg.name());
        byte[] body;
        try {
            body = JSON.writeValueAsBytes(document);
        } catch (IOException e) {
            throw new IllegalStateException("writing the publish document failed", e);
        }
        Http.Response response = http.putBytes(url, body, "application/json",
                Map.of("X-Artifacts-Content-Hash", hash));
        if (response.status() != 200 && response.status() != 201) {
            String message = "the npm publish of " + pkg.name() + "@" + pkg.version() + " answered HTTP "
                    + response.status() + " (" + url + ")"
                    + (response.excerpt().isEmpty() ? "" : ": " + response.excerpt());
            throw response.status() >= 500 ? CliException.transport(message) : CliException.policy(message);
        }
        console.info("published " + pkg.name() + "@" + pkg.version() + (replay ? " under the replay tag" : ""));
    }

    /** Below the registry's {@code latest}: a replay. A package with no packument is a first publish. */
    private boolean replay(String name, String version) {
        String url = store.npmPackument(name);
        Http.Response response = http.get(url);
        if (response.status() == 404) {
            return false;
        }
        if (response.status() != 200) {
            String message = "the npm packument for " + name + " answered HTTP " + response.status() + " (" + url + ")";
            throw response.status() >= 500 ? CliException.transport(message) : CliException.policy(message);
        }
        Map<String, Object> packument = Json.parseObject(response.body(), "the npm packument");
        String latest = Json.string(Json.object(packument, "dist-tags"), "latest");
        return latest != null && VersionOrder.compare(version, latest) == VersionOrder.BELOW;
    }

    // --- helpers -----------------------------------------------------------------------------------

    private static ObjectNode manifest(byte[] bytes, String what) {
        try {
            JsonNode tree = JSON.readTree(bytes);
            if (tree instanceof ObjectNode object) {
                return object;
            }
        } catch (IOException e) {
            throw CliException.policy(what + " is not JSON: " + e.getMessage());
        }
        throw CliException.policy(what + " is not a JSON object");
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw CliException.transport("cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    private static String sha1(byte[] bytes) {
        try {
            return HashManifest.hex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("this JDK has no SHA-1", e);
        }
    }
}
