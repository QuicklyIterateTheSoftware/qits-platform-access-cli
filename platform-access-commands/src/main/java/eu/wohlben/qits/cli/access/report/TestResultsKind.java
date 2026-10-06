package eu.wohlben.qits.cli.access.report;

import com.fasterxml.jackson.core.JsonProcessingException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * {@code test-results}, version 1: which tests ran, and for each one that failed, its class, its name,
 * its file and its message. Fed by surefire, failsafe and vitest ({@link ReportKinds}).
 */
public final class TestResultsKind implements ReportKind<TestResults> {

    public static final String ID = "test-results";
    public static final int VERSION = 1;

    /** Failing test cases listed; beyond it the payload says {@code truncated}. */
    public static final int MAX_FAILURES = 200;
    public static final int MESSAGE_BYTES = 4 * 1024;
    public static final int STACK_TRACE_BYTES = 16 * 1024;

    /**
     * What the payload may grow to, under the 1 MiB qits-ci takes with room for the highlights: 200
     * failures at their full caps would be 4 MiB, and a report refused for its size is lost exactly
     * when there is most to read in it. Past it, the stack traces are cut to
     * {@link #SHORT_STACK_TRACE_BYTES}, then left out, and only then the messages cut to
     * {@link #SHORT_MESSAGE_BYTES}: a failure's class, name and message matter more than its trace.
     */
    static final int PAYLOAD_BUDGET = 900 * 1024;
    static final int SHORT_STACK_TRACE_BYTES = 2 * 1024;
    static final int SHORT_MESSAGE_BYTES = 1024;

    private final Consumer<String> warnings;

    public TestResultsKind(Consumer<String> warnings) {
        this.warnings = warnings;
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
    public Class<TestResults> payloadType() {
        return TestResults.class;
    }

    @Override
    public Optional<TestResults> collect(StepContext step, List<ReportParser<?>> parsers) throws IOException {
        List<TestFile> files = new ArrayList<>();
        for (ReportParser<?> parser : parsers) {
            for (Path file : parser.discover(step.root())) {
                try {
                    if (parser.parse(file, step) instanceof TestFile parsed) {
                        files.add(parsed);
                    }
                } catch (IOException | RuntimeException unreadable) {
                    warnings.accept(ID + ": skipped " + step.relative(file) + " (" + parser.tool() + "): "
                            + describe(unreadable));
                }
            }
        }
        if (files.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(fitted(summed(files, step)));
    }

    private static TestResults summed(List<TestFile> files, StepContext step) {
        Map<String, TestResults.Suite> suites = new LinkedHashMap<>();
        int tests = 0;
        int failed = 0;
        int errored = 0;
        int skipped = 0;
        long durationMs = 0;
        List<TestResults.Failure> failures = new ArrayList<>();
        for (TestFile file : files) {
            tests += file.tests();
            failed += file.failed();
            errored += file.errored();
            skipped += file.skipped();
            durationMs += file.durationMs();
            String key = file.language() + "\n" + file.tool() + "\n" + file.module();
            TestResults.Suite before = suites.get(key);
            suites.put(key, before == null
                    ? new TestResults.Suite(file.language(), file.tool(), file.module(), file.tests(), file.failed(),
                    file.errored(), file.skipped(), file.durationMs())
                    : new TestResults.Suite(file.language(), file.tool(), file.module(), before.tests() + file.tests(),
                    before.failed() + file.failed(), before.errored() + file.errored(),
                    before.skipped() + file.skipped(), before.durationMs() + file.durationMs()));
            failures.addAll(file.failures());
        }
        boolean truncated = failures.size() > MAX_FAILURES;
        List<TestResults.Failure> kept = new ArrayList<>();
        for (TestResults.Failure failure : failures.subList(0, Math.min(failures.size(), MAX_FAILURES))) {
            kept.add(failure.withCoordinates(step.locators().locate(step.root(), failure.coordinates())));
        }
        int passed = Math.max(0, tests - failed - errored - skipped);
        return new TestResults(new TestResults.Totals(tests, passed, failed, errored, skipped, durationMs),
                List.copyOf(suites.values()), kept, truncated);
    }

    /** The report within {@link #PAYLOAD_BUDGET}: shorter stack traces, then none, then shorter messages. */
    static TestResults fitted(TestResults report) {
        if (size(report) <= PAYLOAD_BUDGET) {
            return report;
        }
        TestResults shorter = shortened(report, SHORT_STACK_TRACE_BYTES, MESSAGE_BYTES);
        if (size(shorter) <= PAYLOAD_BUDGET) {
            return shorter;
        }
        TestResults traceless = shortened(report, 0, MESSAGE_BYTES);
        return size(traceless) <= PAYLOAD_BUDGET ? traceless : shortened(report, 0, SHORT_MESSAGE_BYTES);
    }

    private static TestResults shortened(TestResults report, int traceBytes, int messageBytes) {
        List<TestResults.Failure> cut = report.failures().stream()
                .map(f -> new TestResults.Failure(f.coordinates(), f.shape(), f.failureType(),
                        Text.cap(f.message(), messageBytes),
                        traceBytes == 0 ? null : Text.cap(f.stackTrace(), traceBytes), f.durationMs()))
                .toList();
        return new TestResults(report.totals(), report.suites(), cut, report.truncated());
    }

    private static int size(TestResults report) {
        try {
            return ReportJson.MAPPER.writeValueAsBytes(report).length;
        } catch (JsonProcessingException impossible) {
            return Integer.MAX_VALUE;
        }
    }

    @Override
    public List<Highlight> highlight(TestResults report, Optional<TestResults> baseline, StepContext step) {
        TestResults.Totals totals = report.totals();
        int failing = totals.failed() + totals.errored();
        List<Highlight> highlights = new ArrayList<>();
        if (failing > 0) {
            highlights.add(new Highlight(Highlight.BAD, failing + (failing == 1 ? " test failed" : " tests failed"),
                    "tests.failed", (double) failing, null));
        } else if (totals.tests() == 0) {
            highlights.add(new Highlight(Highlight.INFO, "no tests ran", "tests.total", 0.0, null));
        } else {
            highlights.add(new Highlight(Highlight.GOOD, "all " + totals.passed() + " tests passed"
                    + (totals.skipped() > 0 ? ", " + totals.skipped() + " skipped" : ""),
                    "tests.passed", (double) totals.passed(), null));
        }
        if (baseline.isPresent()) {
            int delta = totals.tests() - baseline.get().totals().tests();
            if (delta != 0) {
                String version = step.baseline().map(Baseline::version).orElse("the baseline");
                highlights.add(new Highlight(Highlight.INFO, (delta > 0 ? "+" : "") + delta + " tests vs " + version,
                        "tests.total", (double) totals.tests(), (double) delta));
            }
        }
        if (step.exitCode() != 0 && failing == 0) {
            highlights.add(new Highlight(Highlight.WARN, "step exited " + step.exitCode() + ", no test failed",
                    null, null, null));
        }
        return highlights;
    }

    @Override
    public String describe(TestResults report) {
        TestResults.Totals totals = report.totals();
        return totals.tests() + " tests, " + (totals.failed() + totals.errored()) + " failed";
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName()
                : message.replaceAll("\\s+", " ").strip();
    }
}
