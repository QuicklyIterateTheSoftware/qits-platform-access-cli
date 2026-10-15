package eu.wohlben.qits.cli.access.update;

import eu.wohlben.qits.cli.access.platform.CliFailure;

import java.net.URI;
import java.nio.file.Path;

/** Where the released versions of {@code qits} are. The real one is {@link ArtifactsReleases}. */
interface Releases {

    /** The newest released version, as the store names it. */
    String latest() throws CliFailure, InterruptedException;

    /** What the store says the file of {@code version} is, before it is downloaded. */
    Published describe(String version) throws CliFailure, InterruptedException;

    /** The file of {@code version}, written to {@code target}, which exists and is empty. */
    void download(String version, Path target) throws CliFailure, InterruptedException;

    /** The address of {@code version}'s file, for a person who downloads it by hand. */
    URI address(String version);

    /**
     * @param size   the file's length in bytes
     * @param sha256 its SHA-256 digest, lower-case hex
     */
    record Published(long size, String sha256) {
    }
}
