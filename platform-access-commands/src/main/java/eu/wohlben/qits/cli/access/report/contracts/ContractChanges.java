package eu.wohlben.qits.cli.access.report.contracts;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * What changed in the contracts between the baseline's report and this one, each change named. The
 * highlights here and the view in {@code @qits/ui-components} use the same rules, pinned by the shared
 * fixtures under {@code src/test/resources/report/contracts/diff/}, and the JSON shape of this record
 * is the one that library's {@code contractChanges} returns: keys in this order, every list present.
 *
 * <p>Identity: a provider state by its name; a pact by role, consumer and provider; an interaction by
 * its pact, its description and its provider-state names, sorted. Same identity with another
 * {@code contentHash} is a changed interaction. A role whose side is not reported in either report
 * ({@code sides.consumer} for CONSUMER, {@code sides.provider} for PROVIDER) contributes nothing, and
 * states are compared only when both reports have an index. The interactions of a new pact are new
 * interactions too, and those of a removed pact removed ones.
 *
 * <p>Ordering: pacts by (role, consumer, provider); interactions by (role, consumer, provider,
 * description, provider states joined by NUL); states by name. Plain {@link String#compareTo}, which
 * is what JavaScript's {@code <} does on strings.
 */
@RegisterForReflection
public record ContractChanges(List<Pair> newPairs, List<Pair> removedPairs, List<Interaction> newInteractions,
                              List<Interaction> removedInteractions, List<Interaction> changedInteractions,
                              List<String> newStates, List<String> removedStates) {

    /** One pact, by its identity. */
    @RegisterForReflection
    public record Pair(String role, String consumer, String provider) {
    }

    /** One interaction, by its identity; {@code providerStates} sorted. */
    @RegisterForReflection
    public record Interaction(String role, String consumer, String provider, String description,
                              List<String> providerStates) {

        public Interaction {
            providerStates = List.copyOf(providerStates);
        }

        public Pair pair() {
            return new Pair(role, consumer, provider);
        }
    }

    static final Comparator<Pair> PAIR_ORDER = Comparator.comparing(Pair::role).thenComparing(Pair::consumer)
            .thenComparing(Pair::provider);

    static final Comparator<Interaction> INTERACTION_ORDER = Comparator.comparing(Interaction::pair, PAIR_ORDER)
            .thenComparing(Interaction::description)
            .thenComparing(i -> String.join("\u0000", i.providerStates()));

    public ContractChanges {
        newPairs = List.copyOf(newPairs);
        removedPairs = List.copyOf(removedPairs);
        newInteractions = List.copyOf(newInteractions);
        removedInteractions = List.copyOf(removedInteractions);
        changedInteractions = List.copyOf(changedInteractions);
        newStates = List.copyOf(newStates);
        removedStates = List.copyOf(removedStates);
    }

    /** True when there is nothing to name. */
    public boolean none() {
        return newPairs.isEmpty() && removedPairs.isEmpty() && newInteractions.isEmpty()
                && removedInteractions.isEmpty() && changedInteractions.isEmpty() && newStates.isEmpty()
                && removedStates.isEmpty();
    }

    /** The changes from {@code baseline} to {@code report}. */
    public static ContractChanges between(ContractsReport report, ContractsReport baseline) {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(baseline, "baseline");
        Map<Pair, Map<String, InteractionEntry>> now = index(report, baseline);
        Map<Pair, Map<String, InteractionEntry>> before = index(baseline, report);

        List<Pair> newPairs = new ArrayList<>();
        List<Pair> removedPairs = new ArrayList<>();
        List<Interaction> added = new ArrayList<>();
        List<Interaction> removed = new ArrayList<>();
        List<Interaction> changed = new ArrayList<>();
        for (Map.Entry<Pair, Map<String, InteractionEntry>> pair : now.entrySet()) {
            Map<String, InteractionEntry> then = before.get(pair.getKey());
            if (then == null) {
                newPairs.add(pair.getKey());
            }
            for (Map.Entry<String, InteractionEntry> interaction : pair.getValue().entrySet()) {
                InteractionEntry old = then == null ? null : then.get(interaction.getKey());
                if (old == null) {
                    added.add(named(pair.getKey(), interaction.getValue()));
                } else if (interaction.getValue().contentHash() != null && old.contentHash() != null
                        && !interaction.getValue().contentHash().equals(old.contentHash())) {
                    changed.add(named(pair.getKey(), interaction.getValue()));
                }
            }
        }
        for (Map.Entry<Pair, Map<String, InteractionEntry>> pair : before.entrySet()) {
            Map<String, InteractionEntry> current = now.get(pair.getKey());
            if (current == null) {
                removedPairs.add(pair.getKey());
            }
            for (Map.Entry<String, InteractionEntry> interaction : pair.getValue().entrySet()) {
                if (current == null || !current.containsKey(interaction.getKey())) {
                    removed.add(named(pair.getKey(), interaction.getValue()));
                }
            }
        }

        List<String> newStates = new ArrayList<>();
        List<String> removedStates = new ArrayList<>();
        if (report.sides().providerStates() && baseline.sides().providerStates()) {
            Set<String> stateNow = stateNames(report);
            Set<String> stateBefore = stateNames(baseline);
            stateNow.stream().filter(s -> !stateBefore.contains(s)).forEach(newStates::add);
            stateBefore.stream().filter(s -> !stateNow.contains(s)).forEach(removedStates::add);
        }

        newPairs.sort(PAIR_ORDER);
        removedPairs.sort(PAIR_ORDER);
        added.sort(INTERACTION_ORDER);
        removed.sort(INTERACTION_ORDER);
        changed.sort(INTERACTION_ORDER);
        return new ContractChanges(newPairs, removedPairs, added, removed, changed, newStates, removedStates);
    }

    /** An interaction's identity within its pact: its description and its sorted provider-state names. */
    static String identity(InteractionEntry interaction) {
        return interaction.description() + "\u0001" + String.join("\u0000", sortedStates(interaction));
    }

    static List<String> sortedStates(InteractionEntry interaction) {
        List<String> states = new ArrayList<>(interaction.providerStates());
        states.sort(null);
        return states;
    }

    private static Interaction named(Pair pair, InteractionEntry interaction) {
        return new Interaction(pair.role(), pair.consumer(), pair.provider(), interaction.description(),
                sortedStates(interaction));
    }

    /** The pacts of {@code report} on the sides both reports have, by pair and interaction identity. */
    private static Map<Pair, Map<String, InteractionEntry>> index(ContractsReport report, ContractsReport other) {
        Map<Pair, Map<String, InteractionEntry>> index = new LinkedHashMap<>();
        for (PactEntry pact : report.pacts()) {
            if (!compared(pact.role(), report) || !compared(pact.role(), other)) {
                continue;
            }
            Map<String, InteractionEntry> interactions = index.computeIfAbsent(
                    new Pair(pact.role(), pact.consumer(), pact.provider()), p -> new LinkedHashMap<>());
            for (InteractionEntry interaction : pact.interactions()) {
                interactions.put(identity(interaction), interaction);
            }
        }
        return index;
    }

    private static boolean compared(String role, ContractsReport report) {
        return PactEntry.CONSUMER.equals(role) ? report.sides().consumer()
                : PactEntry.PROVIDER.equals(role) && report.sides().provider();
    }

    private static Set<String> stateNames(ContractsReport report) {
        Set<String> names = new TreeSet<>();
        if (report.providerStates() != null) {
            report.providerStates().states().forEach(s -> names.add(s.name()));
        }
        return names;
    }
}
