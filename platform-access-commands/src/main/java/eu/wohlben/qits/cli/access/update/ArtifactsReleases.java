package eu.wohlben.qits.cli.access.update;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.nio.file.Path;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/**
 * The daemons surface of qits-artifacts, where each release publishes the binary: the list that
 * names the newest version, and the file itself. The same two doors install.sh uses, with the
 * session's bearer on every request.
 */
final class ArtifactsReleases implements Releases {

    /**
     * The name the binary is published under. The same string as {@code
     * PlatformAccessCliBinary.DAEMON_NAME} in the pin module, and the one the release recipe writes;
     * the commands do not depend on the pin, so it is written here a second time.
     */
    static final String DAEMON_NAME = "qits-platform-access-cli";

    private static final Pattern SHA256 = Pattern.compile("sha256:([0-9a-f]{64})");

    private final PlatformClient client;
    private final String origin;

    /** @param origin qits-artifacts' base URL, {@code https://registry.qits.<QITS_DOMAIN>} */
    ArtifactsReleases(PlatformClient client, String origin) {
        this.client = client;
        this.origin = origin;
    }

    @Override
    public String latest() throws CliFailure, InterruptedException {
        URI list = URI.create(origin + "/artifacts/api/repositories/daemons/daemons");
        JsonNode answer = client.get(list);
        for (JsonNode daemon : answer.path("daemons")) {
            if (DAEMON_NAME.equals(daemon.path("name").asText(null))) {
                String version = daemon.path("latestVersion").asText("");
                if (!QitsVersion.isRelease(version)) {
                    throw new CliFailure("The store's latest version of " + DAEMON_NAME
                            + " is not a release version.", CliFailure.FAILED);
                }
                return version;
            }
        }
        throw new CliFailure("The store at " + list + " names no released version of " + DAEMON_NAME + ".",
                CliFailure.FAILED);
    }

    @Override
    public Published describe(String version) throws CliFailure, InterruptedException {
        URI file = address(version);
        HttpHeaders headers = client.head(file);
        OptionalLong size;
        try {
            size = headers.firstValueAsLong("Content-Length");
        } catch (NumberFormatException notANumber) {
            size = OptionalLong.empty();
        }
        String digest = headers.firstValue("Docker-Content-Digest").orElse("").strip().toLowerCase(Locale.ROOT);
        var matcher = SHA256.matcher(digest);
        if (size.isEmpty() || size.getAsLong() <= 0 || !matcher.matches()) {
            throw new CliFailure("HEAD " + file + " did not say the file's length and sha256 digest, so a download "
                    + "could not be checked.", CliFailure.FAILED);
        }
        return new Published(size.getAsLong(), matcher.group(1));
    }

    @Override
    public void download(String version, Path target) throws CliFailure, InterruptedException {
        client.download(address(version), target);
    }

    @Override
    public URI address(String version) {
        return URI.create(origin + "/artifacts/daemons/" + DAEMON_NAME + "/" + version);
    }
}
