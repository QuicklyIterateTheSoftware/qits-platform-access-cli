package eu.wohlben.qits.cli.access.report.contracts;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * The provider states the golden-master index declares.
 *
 * @param provider the application name, as the index spells it ({@code qits-projects})
 * @param states   ordered by name
 */
@RegisterForReflection
public record ProviderStatesInventory(String provider, List<ProviderStateEntry> states) {

    public ProviderStatesInventory {
        states = states == null ? List.of() : List.copyOf(states);
    }
}
