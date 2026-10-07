package eu.wohlben.qits.cli.access.report.contracts;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** A file that was found and could not be read, and why. */
@RegisterForReflection
public record SkippedFile(String file, String reason) {
}
