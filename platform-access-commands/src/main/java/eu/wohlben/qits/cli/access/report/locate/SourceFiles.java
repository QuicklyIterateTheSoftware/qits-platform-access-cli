package eu.wohlben.qits.cli.access.report.locate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** A test's source file, read the way every locator reads it. */
final class SourceFiles {

    /** Beyond this a file is not a test source worth scanning. */
    static final long MAX_BYTES = 1024 * 1024;

    private SourceFiles() {
    }

    /** {@code file} under {@code root}; empty when null, outside the root, missing or over {@link #MAX_BYTES}. */
    static Optional<String> read(Path root, String file) throws IOException {
        if (file == null || file.isBlank()) {
            return Optional.empty();
        }
        Path base = root.toAbsolutePath().normalize();
        Path path = base.resolve(file).normalize();
        if (!path.startsWith(base) || !Files.isRegularFile(path) || Files.size(path) > MAX_BYTES) {
            return Optional.empty();
        }
        return Optional.of(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
    }
}
