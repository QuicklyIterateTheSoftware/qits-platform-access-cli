package eu.wohlben.qits.cli.access.report;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** The repository a step built, as payloads name it. Written into payloads, hence the registration. */
@RegisterForReflection
public record RepositoryRef(String projectId, String name) {
}
