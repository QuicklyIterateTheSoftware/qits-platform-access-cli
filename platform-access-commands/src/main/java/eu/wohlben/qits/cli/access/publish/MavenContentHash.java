package eu.wohlben.qits.cli.access.publish;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The content hash of a maven package: every entry of the jar this CLI uploads (the module's own
 * jar with every bundled sibling merged in), by name and bytes only, plus the canonical SBOM.
 *
 * <p>Excluded: directory entries, {@code META-INF/MANIFEST.MF} and everything under
 * {@code META-INF/maven/}, the parts a build writes differently each time without the content
 * changing. Timestamps, extra fields, compression and order are never read. The pom is never
 * hashed, flattened or not (owner decision): what it says is either in the SBOM or not content.
 */
final class MavenContentHash implements ContentHash {

    @Override
    public String of(BuiltPackage built, Optional<Path> sbom, List<String> include, Reactor reactor) {
        HashManifest manifest = new HashManifest("maven", include);
        if (built.bytes() != null) {
            for (Archives.Entry entry : Archives.readJar(built.bytes(), "the jar")) {
                if (counts(entry.name())) {
                    manifest.file(entry.name(), entry.bytes());
                }
            }
        }
        sbom.ifPresent(file -> manifest.sbom(SbomCanonical.lines(file, reactor)));
        return manifest.value();
    }

    static boolean counts(String name) {
        return !name.endsWith("/")
                && !name.equals("META-INF/MANIFEST.MF")
                && !name.startsWith("META-INF/maven/");
    }
}
