package eu.wohlben.qits.cli.access.publish;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The one content hash of a package: {@code v1:sha256:<hex>} over the package's normalised content
 * plus, for software, its normalised SBOM. One implementation per package type.
 *
 * <p>The value is opaque to everyone but this CLI. qits-artifacts stores it beside the version and
 * serves it back; qits-ci never reads it. So the whole definition lives here, in {@link
 * HashManifest} and {@link SbomCanonical}, and {@code ContentHashGoldenTest} pins it with the
 * fixtures F1 to F5 of the dossier page "The content hash v1". <b>Any change to what the manifest
 * says is a new version, {@code v2}</b>: a stored value under another version prefix reads as
 * "changed", which errs towards publishing, never towards a silent "unchanged".
 */
interface ContentHash {

    String VERSION = "v1";

    /** The algorithm part of the value; everything after the version prefix. */
    String ALGORITHM = "sha256";

    /** The prefix every value this CLI computes starts with. */
    String PREFIX = VERSION + ":" + ALGORITHM + ":";

    /**
     * The value for one built package.
     *
     * @param built   the archive this CLI is about to upload, byte for byte
     * @param sbom    the CycloneDX document of the build, or empty for data with no SBOM (contracts)
     * @param include globs narrowing the content lines; empty keeps every entry
     * @param reactor the reactor siblings this build bundled or linked; {@link Reactor#NONE} for npm
     */
    String of(BuiltPackage built, Optional<Path> sbom, List<String> include, Reactor reactor);

    /** The package type (ecosystem) this implementation hashes: {@code maven}, {@code npm}, … Unique. */
    String type();

    /**
     * Every ecosystem this CLI can hash, one class each. A new package manager (OCI, cargo, pypi,
     * go) is one more class here and nothing else: the decision ({@link Decision}), the store reads
     * ({@link ContentHashes}) and link groups know no ecosystem. A list rather than a ServiceLoader
     * because the CLI is a Quarkus native image, which registers no service providers by default.
     */
    List<ContentHash> ALL = List.of(new MavenContentHash(), new NpmContentHash());

    /** The implementation for a package type. */
    static ContentHash forType(String type) {
        return ALL.stream()
                .filter(hash -> hash.type().equals(type))
                .findFirst()
                .orElseThrow(() -> CliException.policy("no content hash is defined for package type '" + type + "'"));
    }
}
