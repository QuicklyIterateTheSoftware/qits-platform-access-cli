package eu.wohlben.qits.cli.access.publish;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The content hash of an npm package: every regular file of the tarball this CLI built, with the
 * first path segment ({@code package/}) removed, plus the canonical SBOM. The root
 * {@code package.json} is hashed as {@link CanonicalJson} without its {@code version}, so a release
 * that changed nothing but the version it was stamped with hashes the same.
 */
final class NpmContentHash implements ContentHash {

    @Override
    public String type() {
        return "npm";
    }

    @Override
    public String of(BuiltPackage built, Optional<Path> sbom, List<String> include, Reactor reactor) {
        HashManifest manifest = new HashManifest("npm", include);
        for (Archives.Entry entry : Archives.readTarGz(built.bytes(), "the tarball")) {
            int slash = entry.name().indexOf('/');
            String path = slash < 0 ? entry.name() : entry.name().substring(slash + 1);
            byte[] bytes = path.equals("package.json")
                    ? CanonicalJson.manifestWithoutVersion(entry.bytes())
                    : entry.bytes();
            manifest.file(path, bytes);
        }
        sbom.ifPresent(file -> manifest.sbom(SbomCanonical.lines(file, reactor)));
        return manifest.value();
    }
}
