package eu.wohlben.qits.cli.access.registry;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;

import java.io.PrintStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Which registries the blocks name, as the platform lists them: qits-artifacts'
 * {@code GET /artifacts/api/repositories} (hosted npm and Maven) and qits-mirror's
 * {@code GET /mirror/api/repositories} (the npmjs cache). A listing that cannot be read falls back
 * to the registries every deployment ships, and says so.
 */
final class RegistryDiscovery {

    static final String NPM_HOSTED = "npm-packages";
    static final String NPM_PROXY = "npm-proxy";
    static final String MAVEN_HOSTED = "maven-packages";
    /** The hosted Maven repository the poms' {@code qits-maven} id means. */
    static final String MAVEN_REPOSITORY = "maven";

    private RegistryDiscovery() {
    }

    /** The npm prefixes and the Maven URL, from {@code registry} and {@code mirror} base URLs. */
    record Found(List<String> npm, String mavenUrl) {
    }

    static Found find(PlatformClient client, String registry, String mirror, PrintStream err)
            throws InterruptedException {
        List<String> npm = new ArrayList<>();
        String mavenUrl = registry + "/artifacts/maven/" + MAVEN_REPOSITORY;

        JsonNode hosted = list(client, registry + "/artifacts/api/repositories", err);
        if (hosted == null) {
            npm.add(key(registry) + "/artifacts/npm/npm/");
        } else {
            for (JsonNode repository : hosted) {
                if (NPM_HOSTED.equals(repository.path("type").asText())) {
                    npm.add(key(registry) + "/artifacts/npm/" + repository.path("name").asText() + "/");
                }
            }
        }
        JsonNode cached = list(client, mirror + "/mirror/api/repositories", err);
        if (cached == null) {
            npm.add(key(mirror) + "/npm/npmjs/");
        } else {
            for (JsonNode repository : cached) {
                if (NPM_PROXY.equals(repository.path("type").asText())) {
                    npm.add(key(mirror) + "/npm/" + repository.path("name").asText() + "/");
                }
            }
        }
        return new Found(List.copyOf(npm), mavenUrl);
    }

    private static JsonNode list(PlatformClient client, String url, PrintStream err) throws InterruptedException {
        try {
            JsonNode repositories = client.get(URI.create(url)).path("repositories");
            if (repositories.isArray()) {
                return repositories;
            }
            err.println("Cannot read the registry list at " + url + "; using the default registries.");
        } catch (CliFailure failure) {
            err.println("Cannot read the registry list at " + url + " (" + failure.getMessage()
                    + "); using the default registries.");
        }
        return null;
    }

    /** {@code https://registry.qits.example} becomes npm's scheme-less {@code //registry.qits.example}. */
    static String key(String base) {
        URI uri = URI.create(base);
        return "//" + uri.getRawAuthority() + (uri.getRawPath() == null ? "" : uri.getRawPath().replaceAll("/+$", ""));
    }
}
