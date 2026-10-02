package eu.wohlben.qits.cli.access.publish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A CycloneDX JSON document as the content hash reads it: the {@code c} lines (one per dependency
 * component) and the {@code e} lines (one per edge), and nothing else.
 *
 * <p>What is kept is the dependency graph: each component's identity (its purl without the
 * version), its version and its hashes, and the edges between them. A transitive bump therefore
 * changes the hash, and a pom reshuffle resolving to the same graph does not. Everything that
 * changes on every build is dropped by construction, because it is simply never read: the serial,
 * the timestamp, the tools, licences, properties, external references, and the root's own version
 * (every edge endpoint equal to the root's bom-ref is the literal {@code <root>}).
 *
 * <p><b>Reactor siblings.</b> A bundled sibling is folded into the root: no {@code c} line, its
 * edge endpoints become {@code <root>}, and a resulting {@code <root>} to {@code <root>} edge is
 * dropped; its bytes are already among the content lines. A linked sibling keeps its {@code c} line
 * at its <em>decided</em> version and with no hashes, so an unchanged linked sibling leaves the
 * dependent's hash alone. A sibling is matched by {@code group:name}.
 */
final class SbomCanonical {

    static final String ROOT = "<root>";

    private static final ObjectMapper JSON = new ObjectMapper();

    private SbomCanonical() {
    }

    /** The {@code c} and {@code e} lines of the document at {@code file}. */
    static List<String> lines(Path file, Reactor reactor) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            throw CliException.policy("no such SBOM: " + file);
        } catch (IOException e) {
            throw CliException.transport("cannot read " + file + ": " + e.getMessage(), e);
        }
        JsonNode document;
        try {
            document = JSON.readTree(bytes);
        } catch (IOException e) {
            throw CliException.policy("the SBOM " + file + " is not JSON: " + e.getMessage());
        }
        if (document == null || !document.isObject()) {
            throw CliException.policy("the SBOM " + file + " is not a JSON object");
        }
        return lines(document, reactor, file.toString());
    }

    static List<String> lines(JsonNode document, Reactor reactor, String what) {
        JsonNode components = document.get("components");
        JsonNode dependencies = document.get("dependencies");
        boolean hasComponents = components != null && components.isArray();
        boolean hasDependencies = dependencies != null && dependencies.isArray();
        if (!hasComponents && !hasDependencies) {
            // Never hashed as empty: an SBOM that says nothing would make every release look alike.
            throw CliException.policy("the SBOM " + what + " has no components and no dependencies");
        }

        String rootRef = text(document.path("metadata").path("component"), "bom-ref");
        Map<String, String> refs = new HashMap<>();
        if (rootRef != null) {
            refs.put(rootRef, ROOT);
        }

        List<String> lines = new ArrayList<>();
        List<JsonNode> all = new ArrayList<>();
        if (hasComponents) {
            collect(components, all);
        }
        for (JsonNode component : all) {
            String ref = text(component, "bom-ref");
            if (rootRef != null && rootRef.equals(ref)) {
                continue;
            }
            String identity = identity(component);
            String coordinate = coordinate(component);
            if (coordinate != null && reactor.bundled().contains(coordinate)) {
                if (ref != null) {
                    refs.put(ref, ROOT);
                }
                continue;
            }
            if (ref != null) {
                refs.put(ref, identity);
            }
            if (coordinate != null && reactor.linked().containsKey(coordinate)) {
                lines.add("c\t" + identity + "\t" + reactor.linked().get(coordinate) + "\t");
            } else {
                lines.add("c\t" + identity + "\t" + orEmpty(text(component, "version")) + "\t" + hashes(component));
            }
        }

        if (hasDependencies) {
            for (JsonNode dependency : dependencies) {
                String from = text(dependency, "ref");
                JsonNode dependsOn = dependency.get("dependsOn");
                if (from == null || dependsOn == null || !dependsOn.isArray()) {
                    continue;
                }
                String mappedFrom = refs.getOrDefault(from, from);
                for (JsonNode to : dependsOn) {
                    if (!to.isTextual()) {
                        continue;
                    }
                    String mappedTo = refs.getOrDefault(to.textValue(), to.textValue());
                    if (mappedFrom.equals(ROOT) && mappedTo.equals(ROOT)) {
                        continue;
                    }
                    lines.add("e\t" + mappedFrom + "\t" + mappedTo);
                }
            }
        }
        return lines;
    }

    private static void collect(JsonNode components, List<JsonNode> into) {
        for (JsonNode component : components) {
            if (!component.isObject()) {
                continue;
            }
            into.add(component);
            JsonNode nested = component.get("components");
            if (nested != null && nested.isArray()) {
                collect(nested, into);
            }
        }
    }

    /**
     * The purl without its version: the {@code @...} after the last {@code /} and before any
     * {@code ?} or {@code #}. Qualifiers and subpath are kept. With no purl, {@code group/name}, or
     * the name alone.
     */
    static String identity(JsonNode component) {
        String purl = text(component, "purl");
        if (purl != null) {
            int end = purl.length();
            int q = purl.indexOf('?');
            int h = purl.indexOf('#');
            if (q >= 0) {
                end = q;
            }
            if (h >= 0 && h < end) {
                end = h;
            }
            String base = purl.substring(0, end);
            int slash = base.lastIndexOf('/');
            int at = base.indexOf('@', slash + 1);
            if (at >= 0) {
                base = base.substring(0, at);
            }
            return base + purl.substring(end);
        }
        String group = text(component, "group");
        String name = orEmpty(text(component, "name"));
        return group == null ? name : group + "/" + name;
    }

    private static String coordinate(JsonNode component) {
        String group = text(component, "group");
        String name = text(component, "name");
        return group == null || name == null ? null : group + ":" + name;
    }

    private static String hashes(JsonNode component) {
        JsonNode hashes = component.get("hashes");
        if (hashes == null || !hashes.isArray()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode hash : hashes) {
            String alg = text(hash, "alg");
            String content = text(hash, "content");
            if (alg != null && content != null) {
                parts.add(alg.toLowerCase(Locale.ROOT) + "=" + content.toLowerCase(Locale.ROOT));
            }
        }
        parts.sort(HashManifest.UTF8_ORDER);
        return String.join(",", parts);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
