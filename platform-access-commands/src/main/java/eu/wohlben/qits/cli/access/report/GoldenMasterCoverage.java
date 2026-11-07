package eu.wohlben.qits.cli.access.report;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * The {@code golden-master-coverage} payload, version 1 (qits-1940): the operations of the service's
 * OpenAPI document that no golden master records, as {@code qits contracts coverage} found them.
 * Written and read back by Jackson, hence the registrations.
 *
 * @param uncovered one per uncovered operation, in document order; empty is a clean pass
 */
@RegisterForReflection
public record GoldenMasterCoverage(List<Operation> uncovered) {

    public GoldenMasterCoverage {
        uncovered = List.copyOf(uncovered);
    }

    /**
     * @param name   its operationId, or {@code METHOD /path} when it has none
     * @param method upper case: {@code GET}
     * @param path   the document's path template: {@code /work/{id}}
     */
    @RegisterForReflection
    public record Operation(String name, String method, String path) {
    }
}
