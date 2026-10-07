package eu.wohlben.qits.cli.access.report.contracts;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * One provider state and the operations its golden masters record.
 *
 * @param operations the operationIds, in the index's order
 */
@RegisterForReflection
public record ProviderStateEntry(String name, List<String> operations) {

    public ProviderStateEntry {
        operations = operations == null ? List.of() : List.copyOf(operations);
    }
}
