package eu.wohlben.qits.cli.access.publish;

/**
 * A package as this CLI built it, about to be uploaded: a jar (maven) or a gzipped tarball (npm).
 * {@code bytes} is {@code null} for a maven pom-packaging product, which uploads a pom and nothing
 * else, and so has no content lines in its hash.
 */
record BuiltPackage(String type, byte[] bytes) {

    static BuiltPackage maven(byte[] jar) {
        return new BuiltPackage("maven", jar);
    }

    static BuiltPackage npm(byte[] tarball) {
        return new BuiltPackage("npm", tarball);
    }
}
