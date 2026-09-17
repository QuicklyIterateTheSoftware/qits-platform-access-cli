package eu.wohlben.qits.cli.tui.complete;

import eu.wohlben.qits.cli.tui.api.CompletionSource;
import eu.wohlben.qits.cli.tui.model.CommandNode;
import eu.wohlben.qits.cli.tui.model.OptionRow;
import eu.wohlben.qits.cli.tui.screen.ChoiceList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The completion sources, resolved once, asked off the render thread, and remembered for as long as
 * the screen is open.
 * <p>
 * Nothing here is written to disk. A dropdown's contents are what one session was told, and the
 * next process asks again — a cache that outlived a process would show a project somebody has since
 * been taken off.
 * <p>
 * A source is asked on its own thread and the answer is collected by the loop that paints, so every
 * change to what is on screen still happens in one place.
 */
public final class Completions {

    /** How a source class becomes a source. In the binary that is CDI; a test gives its own. */
    public interface Resolver {

        CompletionSource source(Class<? extends CompletionSource> type);
    }

    /** One call in flight. The fetching thread only writes here; the loop reads it and acts. */
    public static final class Pending {

        private final String key;
        private volatile List<ChoiceList.Choice> choices;
        private volatile String failure;
        private volatile boolean done;

        private Pending(String key) {
            this.key = key;
        }

        public boolean done() {
            return done;
        }

        /** The values, or null when the call failed. */
        public List<ChoiceList.Choice> choices() {
            return choices;
        }

        /** Why there are no values, in one line, or null when there are. */
        public String failure() {
            return failure;
        }
    }

    private final Resolver resolver;
    private final Map<Class<? extends CompletionSource>, CompletionSource> sources = new HashMap<>();
    private final Map<String, List<ChoiceList.Choice>> cache = new HashMap<>();
    private final ExecutorService fetcher = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "qits-tui-complete");
        thread.setDaemon(true);
        return thread;
    });

    public Completions(Resolver resolver) {
        this.resolver = resolver;
    }

    /** The source for a row, or null when it has none or the bean cannot be had. */
    public CompletionSource sourceOf(OptionRow row) {
        if (!row.completed()) {
            return null;
        }
        return sources.computeIfAbsent(row.completionSource(), type -> {
            try {
                return resolver.source(type);
            } catch (RuntimeException | LinkageError noBean) {
                return null;
            }
        });
    }

    /** What must be chosen before this row can be. Empty when it has no source. */
    public Set<String> dependsOn(OptionRow row) {
        CompletionSource source = sourceOf(row);
        if (source == null) {
            return Set.of();
        }
        try {
            Set<String> declared = source.dependsOn();
            return declared == null ? Set.of() : declared;
        } catch (RuntimeException badSource) {
            return Set.of();
        }
    }

    /**
     * The rows with every dependency in front of the row that needs it, keeping the order they came
     * in otherwise — so {@code --project} is above {@code --repository} without either of them
     * having said anything about position.
     */
    public List<OptionRow> ordered(List<OptionRow> rows) {
        List<OptionRow> left = new ArrayList<>(rows);
        List<OptionRow> out = new ArrayList<>(rows.size());
        Set<String> placed = new LinkedHashSet<>();
        while (!left.isEmpty()) {
            OptionRow next = null;
            for (OptionRow row : left) {
                boolean ready = true;
                for (String dependency : dependsOn(row)) {
                    if (!placed.contains(dependency) && holds(left, dependency)) {
                        ready = false;
                        break;
                    }
                }
                if (ready) {
                    next = row;
                    break;
                }
            }
            // Nothing is ready only when what is left depends on itself. The cycle check refuses
            // that at startup; here the order simply stops improving rather than looping.
            if (next == null) {
                out.addAll(left);
                return out;
            }
            left.remove(next);
            placed.add(next.key());
            out.add(next);
        }
        return out;
    }

    private static boolean holds(List<OptionRow> rows, String key) {
        return rows.stream().anyMatch(row -> row.key().equals(key));
    }

    /** The first dependency of {@code row} with no value yet, or null when it can be asked. */
    public String missingDependency(OptionRow row, CommandNode node, Map<String, String> chosen) {
        for (String dependency : new java.util.TreeSet<>(dependsOn(row))) {
            if (node.row(dependency) != null && isBlank(chosen.get(dependency))) {
                return dependency;
            }
        }
        return null;
    }

    /** What a source already answered for these dependency values, or null when it has not been asked. */
    public List<ChoiceList.Choice> cached(OptionRow row, Map<String, String> chosen) {
        return cache.get(key(row, chosen));
    }

    /** Forget what a source answered, so the next open asks again. */
    public void drop(OptionRow row, Map<String, String> chosen) {
        cache.remove(key(row, chosen));
    }

    /** Ask the source, on another thread. The caller polls the answer and puts it on screen. */
    public Pending fetch(OptionRow row, Map<String, String> chosen) {
        Pending pending = new Pending(key(row, chosen));
        CompletionSource source = sourceOf(row);
        Map<String, String> values = Map.copyOf(chosen);
        if (source == null) {
            pending.failure = "no completion source for " + row.name();
            pending.done = true;
            return pending;
        }
        fetcher.submit(() -> {
            try {
                List<CompletionSource.Choice> answered = source.choices(values);
                List<ChoiceList.Choice> choices = new ArrayList<>();
                if (answered != null) {
                    answered.forEach(choice -> choices.add(new ChoiceList.Choice(choice.value(), choice.label())));
                }
                pending.choices = List.copyOf(choices);
            } catch (Exception | LinkageError failed) {
                pending.failure = oneLine(row, failed);
            } finally {
                pending.done = true;
            }
        });
        return pending;
    }

    /** Remember a finished call's answer. A failure is not remembered: the next open tries again. */
    public void remember(Pending pending) {
        if (pending.choices != null) {
            cache.put(pending.key, pending.choices);
        }
    }

    /** {@code could not list --repository: 401}: what went wrong, on one line, with no stack. */
    private static String oneLine(OptionRow row, Throwable failed) {
        String detail = failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage();
        return "could not list " + row.name() + ": " + detail.lines().findFirst().orElse(detail);
    }

    /**
     * Refuses a command whose sources depend on each other in a circle, at startup rather than when
     * somebody opens the picker that never opens.
     */
    public void refuseCycles(CommandNode node) {
        for (OptionRow row : node.rows()) {
            walk(node, row, new LinkedHashSet<>());
        }
        node.children().forEach(this::refuseCycles);
    }

    private void walk(CommandNode node, OptionRow row, Set<String> seen) {
        if (!seen.add(row.key())) {
            throw new IllegalStateException("completion sources of " + node.name()
                    + " depend on each other in a circle: " + String.join(" -> ", seen) + " -> " + row.key());
        }
        for (String dependency : dependsOn(row)) {
            OptionRow next = node.row(dependency);
            if (next != null) {
                walk(node, next, seen);
            }
        }
        seen.remove(row.key());
    }

    /** The source and the values it depends on: two projects are two entries, one project is one. */
    private String key(OptionRow row, Map<String, String> chosen) {
        Map<String, String> depends = new TreeMap<>();
        for (String dependency : dependsOn(row)) {
            depends.put(dependency, chosen.get(dependency));
        }
        return row.completionSource().getName() + depends;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
