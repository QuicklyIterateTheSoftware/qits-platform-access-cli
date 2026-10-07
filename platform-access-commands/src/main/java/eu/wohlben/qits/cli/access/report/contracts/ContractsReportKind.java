package eu.wohlben.qits.cli.access.report.contracts;

import eu.wohlben.qits.cli.access.report.Baseline;
import eu.wohlben.qits.cli.access.report.Highlight;
import eu.wohlben.qits.cli.access.report.ReportKind;
import eu.wohlben.qits.cli.access.report.ReportParser;
import eu.wohlben.qits.cli.access.report.StepContext;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * {@code contracts}, version 1: the pacts a repository holds as consumer (committed {@code pacts/}),
 * the pacts it verified as provider (Pact-JVM's report), and the provider states it declares (its
 * golden-master index). A new pact is a new dependency between two components, and a new provider
 * state a new promise; the highlights name what was added or removed since the baseline
 * ({@link ContractChanges}). Another contract tool is one more parser in
 * {@link eu.wohlben.qits.cli.access.report.ReportKinds}.
 *
 * <p>Reported only in a step that ran tests: the step whose tests vouch for the committed files
 * (java-service's verify step, not its webui and image step), so a two-step run does not report the
 * same tree twice. Which parsers find test results is the {@code test-results} kind's business, so
 * they are handed in from the registry rather than their globs repeated here.
 */
public final class ContractsReportKind implements ReportKind<ContractsReport> {

    public static final String ID = "contracts";
    public static final int VERSION = 1;

    /** Interactions listed in all; beyond it the payload says {@code truncated}. Keeps it well under 1 MiB. */
    public static final int MAX_INTERACTIONS = 2_000;

    static final String ARROW = " → ";
    static final String MINUS = "−";
    static final String ELLIPSIS = "…";

    static final Comparator<PactEntry> PACT_ORDER = Comparator.comparing(PactEntry::role)
            .thenComparing(PactEntry::consumer).thenComparing(PactEntry::provider)
            .thenComparing(PactEntry::source, Comparator.nullsFirst(Comparator.naturalOrder()));

    static final Comparator<InteractionEntry> INTERACTION_ORDER = Comparator.comparing(InteractionEntry::description)
            .thenComparing(i -> String.join("\u0000", ContractChanges.sortedStates(i)));

    private final Consumer<String> warnings;
    private final List<ReportParser<?>> testResults;

    /**
     * @param testResults the parsers of the {@code test-results} kind: the contracts are reported only
     *                    where one of them finds a file
     */
    public ContractsReportKind(Consumer<String> warnings, List<ReportParser<?>> testResults) {
        this.warnings = warnings;
        this.testResults = List.copyOf(testResults);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public Class<ContractsReport> payloadType() {
        return ContractsReport.class;
    }

    @Override
    public Optional<ContractsReport> collect(StepContext step, List<ReportParser<?>> parsers) throws IOException {
        if (testResults.stream().allMatch(parser -> parser.discover(step.root()).isEmpty())) {
            return Optional.empty();
        }
        boolean found = false;
        List<ContractFile> files = new ArrayList<>();
        List<SkippedFile> skipped = new ArrayList<>();
        for (ReportParser<?> parser : parsers) {
            for (Path file : parser.discover(step.root())) {
                found = true;
                try {
                    if (parser.parse(file, step) instanceof ContractFile parsed) {
                        files.add(parsed);
                    } else {
                        throw new IOException("not a contracts input");
                    }
                } catch (IOException | RuntimeException unreadable) {
                    String reason = describe(unreadable);
                    warnings.accept(ID + ": skipped " + step.relative(file) + " (" + parser.tool() + "): " + reason);
                    skipped.add(new SkippedFile(step.relative(file), reason));
                }
            }
        }
        return found ? Optional.of(merged(files, skipped)) : Optional.empty();
    }

    /** The files as one payload: sorted, and cut at {@link #MAX_INTERACTIONS}. */
    static ContractsReport merged(List<ContractFile> files, List<SkippedFile> skipped) {
        List<ContractSource> sources = new ArrayList<>();
        boolean consumer = false;
        boolean provider = false;
        ProviderStatesInventory states = null;
        List<PactEntry> pacts = new ArrayList<>();
        for (ContractFile file : files) {
            sources.add(file.source());
            switch (file.side()) {
                case ContractFile.CONSUMER -> consumer = true;
                case ContractFile.PROVIDER -> provider = true;
                case ContractFile.PROVIDER_STATES -> {
                    if (states == null) {
                        states = file.providerStates();
                    }
                }
                default -> {
                }
            }
            for (PactEntry pact : file.pacts()) {
                List<InteractionEntry> interactions = new ArrayList<>(pact.interactions());
                interactions.sort(INTERACTION_ORDER);
                pacts.add(pact.withInteractions(interactions));
            }
        }
        pacts.sort(PACT_ORDER);
        boolean truncated = false;
        int left = MAX_INTERACTIONS;
        List<PactEntry> kept = new ArrayList<>();
        for (PactEntry pact : pacts) {
            if (pact.interactions().size() > left) {
                truncated = true;
                kept.add(pact.withInteractions(pact.interactions().subList(0, left)));
                left = 0;
            } else {
                kept.add(pact);
                left -= pact.interactions().size();
            }
        }
        return new ContractsReport(sources, new ContractSides(consumer, provider, states != null), states, kept,
                skipped, truncated);
    }

    @Override
    public List<Highlight> highlight(ContractsReport report, Optional<ContractsReport> baseline, StepContext step) {
        if (baseline.isEmpty()) {
            return List.of(new Highlight(Highlight.INFO, "contracts: " + count(report.pacts().size(), "pact") + ", "
                    + count(report.interactionCount(), "interaction") + ", "
                    + count(report.stateCount(), "state") + " (no baseline)", null, null, null));
        }
        ContractChanges changes = ContractChanges.between(report, baseline.get());
        if (changes.none()) {
            String version = step.baseline().map(Baseline::version).orElse("the baseline");
            return List.of(new Highlight(Highlight.GOOD, "contracts unchanged since " + version, null, null, null));
        }
        List<Highlight> lines = new ArrayList<>();
        for (ContractChanges.Pair pair : changes.newPairs()) {
            lines.add(warn(pair(PactEntry.PROVIDER.equals(pair.role()) ? "now verifies pact: " : "new pact: ", pair, "")));
        }
        states(lines, changes.removedStates(), Highlight.WARN, "provider state removed: ", " provider states removed");
        states(lines, changes.newStates(), Highlight.INFO, "new provider state: ", " new provider states");
        List<ContractChanges.Pair> newPairs = changes.newPairs();
        List<ContractChanges.Pair> removedPairs = changes.removedPairs();
        perPair(changes.newInteractions(), newPairs).forEach((pair, n) ->
                lines.add(info(pair("+" + count(n, "interaction") + " ", pair, ""))));
        perPair(changes.removedInteractions(), removedPairs).forEach((pair, n) ->
                lines.add(info(pair(MINUS + count(n, "interaction") + " ", pair, ""))));
        perPair(changes.changedInteractions(), List.of()).forEach((pair, n) ->
                lines.add(info(pair(count(n, "interaction") + " changed ", pair, ""))));
        for (ContractChanges.Pair pair : removedPairs) {
            lines.add(info(pair("pact removed: ", pair, "")));
        }
        if (lines.size() > Highlight.MAX_PER_REPORT) {
            int more = lines.size() - (Highlight.MAX_PER_REPORT - 1);
            List<Highlight> capped = new ArrayList<>(lines.subList(0, Highlight.MAX_PER_REPORT - 1));
            capped.add(info("+" + more + " more contract changes"));
            return capped;
        }
        return lines;
    }

    @Override
    public String describe(ContractsReport report) {
        return count(report.pacts().size(), "pact") + ", " + count(report.interactionCount(), "interaction") + ", "
                + count(report.stateCount(), "state");
    }

    private static void states(List<Highlight> lines, List<String> names, String severity, String one, String many) {
        if (names.size() > 2) {
            lines.add(new Highlight(severity, names.size() + many, null, null, null));
            return;
        }
        for (String name : names) {
            lines.add(new Highlight(severity, fit(one + "%s", name), null, null, null));
        }
    }

    /**
     * How many of {@code interactions} each pair has, in pair order, leaving out the pairs in
     * {@code except}: a new pact's interactions are said by its own line.
     */
    private static Map<ContractChanges.Pair, Integer> perPair(List<ContractChanges.Interaction> interactions,
                                                              List<ContractChanges.Pair> except) {
        Map<ContractChanges.Pair, Integer> counts = new LinkedHashMap<>();
        for (ContractChanges.Interaction interaction : interactions) {
            if (!except.contains(interaction.pair())) {
                counts.merge(interaction.pair(), 1, Integer::sum);
            }
        }
        return counts;
    }

    private static String pair(String prefix, ContractChanges.Pair pair, String suffix) {
        return fit(prefix + "%s" + ARROW + "%s" + suffix, pair.consumer(), pair.provider());
    }

    /**
     * {@code template} with its {@code %s} filled by {@code names}, within {@link Highlight#MAX_TEXT}
     * characters: the longest name is shortened first, each cut one ending in an ellipsis, so both ends
     * of an arrow stay readable.
     */
    static String fit(String template, String... names) {
        int fixed = template.length() - 2 * names.length;
        int budget = Highlight.MAX_TEXT - fixed;
        int[] lengths = new int[names.length];
        int total = 0;
        for (int i = 0; i < names.length; i++) {
            lengths[i] = names[i].length();
            total += lengths[i];
        }
        while (total > budget && total > 0) {
            int longest = 0;
            for (int i = 1; i < lengths.length; i++) {
                if (lengths[i] > lengths[longest]) {
                    longest = i;
                }
            }
            if (lengths[longest] <= 1) {
                break;
            }
            lengths[longest]--;
            total--;
        }
        StringBuilder text = new StringBuilder();
        int from = 0;
        for (int i = 0; i < names.length; i++) {
            int at = template.indexOf("%s", from);
            text.append(template, from, at).append(lengths[i] >= names[i].length() ? names[i]
                    : names[i].substring(0, lengths[i] - 1) + ELLIPSIS);
            from = at + 2;
        }
        return text.append(template, from, template.length()).toString();
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    private static Highlight warn(String text) {
        return new Highlight(Highlight.WARN, text, null, null, null);
    }

    private static Highlight info(String text) {
        return new Highlight(Highlight.INFO, text, null, null, null);
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName()
                : message.replaceAll("\\s+", " ").strip();
    }
}
