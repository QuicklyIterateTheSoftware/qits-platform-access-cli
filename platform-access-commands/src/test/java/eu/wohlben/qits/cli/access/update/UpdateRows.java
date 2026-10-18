package eu.wohlben.qits.cli.access.update;

import eu.wohlben.qits.cli.access.contracts.CliRow;
import eu.wohlben.qits.cli.access.contracts.Reached;
import eu.wohlben.qits.cli.access.platform.PlatformClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>What {@code qits update} asks qits-artifacts</b> (ticket qits-1149), rows of {@code
 * publish/ArtifactsContract}: the daemons list that names the newest release, the HEAD that says
 * the binary's length and digest, and the binary itself. The binary is published under one fixed
 * name, {@code qits-platform-access-cli}, which the recorded daemon states do not carry, so every
 * row waits for a state that publishes that name at a release version.
 */
public final class UpdateRows {

    /** Not recorded: the CLI's own daemon, under its name, at a release version. */
    static final String THE_CLI = "the qits CLI published as a daemon";

    public static final Class<?> CLIENT = ArtifactsReleases.class;

    public static final String[] DOORS = {"latest", "describe", "download"};

    private static ArtifactsReleases releases(String url) {
        return new ArtifactsReleases(new PlatformClient(Reached.CREDENTIAL), url);
    }

    public static final List<CliRow> ROWS = List.of(
            CliRow.of("qits update", THE_CLI, "listDaemons", "ArtifactsReleases#latest",
                            (url, recorded) -> assertThat(QitsVersion.isRelease(releases(url).latest())).isTrue())
                    .consumes("daemons[].name", "daemons[].latestVersion")
                    .request("GET /artifacts/api/repositories/{repository}/daemons"),
            CliRow.of("qits update", THE_CLI, "headDaemon", "ArtifactsReleases#describe", (url, recorded) -> {
                        Releases.Published published = releases(url).describe(recorded.params().get("version"));
                        assertThat(published.size()).isPositive();
                        assertThat(published.sha256()).hasSize(64);
                    })
                    .readsHeader("Content-Length", "Docker-Content-Digest")
                    .request("HEAD /artifacts/daemons/{daemon}/{version}"),
            CliRow.of("qits update", THE_CLI, "getDaemon", "ArtifactsReleases#download", (url, recorded) -> {
                        Path target = Files.createTempFile("pact-qits-", ".bin");
                        target.toFile().deleteOnExit();
                        releases(url).download(recorded.params().get("version"), target);
                        assertThat(target).exists();
                    })
                    .request("GET /artifacts/daemons/{daemon}/{version}"));

    private UpdateRows() {
    }
}
