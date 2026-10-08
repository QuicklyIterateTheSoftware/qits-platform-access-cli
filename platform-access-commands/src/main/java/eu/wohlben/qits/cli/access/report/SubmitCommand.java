package eu.wohlben.qits.cli.access.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.ci.CiApi;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.publish.PublishCredential;
import eu.wohlben.qits.cli.access.publish.Store;
import eu.wohlben.qits.cli.access.report.screenshots.VitestBrowserScreenshots;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * {@code qits ci report submit}: a QA step's reports, collected from its tree and uploaded to qits-ci.
 * <p>
 * Run by the hook qits-ci composes after every QA step's declared script, whatever that script exited
 * with, and guarded there so that nothing here can change the step's verdict. It reads only the step's
 * environment and files: never {@code t.json}, never {@code git.json}. The bearer is the publish
 * chain's ({@link PublishCredential#forCiStep}), the address {@code https://ci.qits.$QITS_DOMAIN}
 * ({@link Store#publicOrigin}); no variable names another.
 */
@TuiCommand(interaction = Interaction.CI_ONLY)
@CommandLine.Command(name = "submit", mixinStandardHelpOptions = true,
        description = {"Collect this CI step's release reports from its files and upload them to qits-ci. Run in a "
                        + "QA step, after the step's own script, with that script's exit code.",
                "For each kind: find its inputs under --root, parse them, compare with the baseline's report of "
                        + "the same kind, and PUT the result to the run's step. A kind with no inputs is not "
                        + "reported, and nothing is sent for it. One line per kind on stdout: `test-results: "
                        + "submitted (412 tests, 3 failed)`, `coverage: submitted (10031 lines, 80.9%% covered)` or "
                        + "`coverage: not reported (no inputs)`."},
        footerHeading = HelpText.EXAMPLES,
        footer = {"  qits ci report submit --exit-code \"$qits_step_exit\"",
                "  qits ci report submit --exit-code 1 --root /workspace/checkout",
                "",
                "- test-results reads **/target/surefire-reports/TEST-*.xml, **/target/failsafe-reports/TEST-*.xml "
                        + "and .qits-reports/vitest-*.xml. A file that does not parse is skipped with a warning.",
                "- A failing test gets the lines it sits on in its file for Java with surefire or failsafe (the JUnit "
                        + "test method, its annotations included) and for TypeScript or JavaScript with vitest (the "
                        + "it/test block); any other failure has none.",
                "- coverage reads .qits-reports/jacoco.exec (JaCoCo 0.8.14's format) against every "
                        + "**/target/classes, and coverage/**/coverage-final.json or "
                        + ".qits-reports/coverage/**/coverage-final.json (istanbul's json, as vitest writes it). "
                        + "With a baseline it fetches the baseline's tag (git fetch --depth=1 "
                        + "\"$QITS_CI_REPOSITORY_URL\" refs/tags/<version>) and measures the lines `git diff -U0 "
                        + "<version> HEAD` names; when git cannot, the diff coverage is left out, with one warning.",
                "- contracts reads pacts/*.json (the committed consumer pacts, Pact v2 to v4), "
                        + ".qits-reports/pact-verification/*.json (Pact-JVM's JSON verification report: the pacts "
                        + "the provider verified) and golden-masters/index.json (the provider states), and only in "
                        + "a step where test-results found a file, so a run reports its tree once.",
                "- entity-changes reads the generated docs/database/*.md (the files `qits database diagram` wrote; "
                        + "a hand-written file there is ignored), only in step 0, and compares them with the same "
                        + "files at the baseline's tag, fetched as for coverage. Without a baseline, or at a "
                        + "baseline from before the diagrams, every unit is CURRENT: the diagram is new.",
                "- screenshots reads the committed screenshot baselines (vitest browser mode's "
                        + "**/__screenshots__/<spec file>/<name>-<browser>-<platform>.png) from `git ls-tree` at HEAD "
                        + "and at the baseline's tag, fetched as for coverage, and lists the NEW, CHANGED and "
                        + "REMOVED ones by path and blob id, never their bytes, with the keys of "
                        + "**/testing/browser/renderer.txt that changed. Only in a step that rendered them: one on "
                        + "the renderer image (/etc/qits-renderer-provenance) or with @qits/angular's run record "
                        + "(node_modules/.cache/@qits/angular/screenshot-references.json); otherwise `screenshots: "
                        + "not reported (not rendered in this step)`, and `(no screenshot baselines)` when neither "
                        + "side holds one. QITS_CI_REPO_ID names the repository for the images. Without a "
                        + "baseline every image is NEW.",
                "- The step's environment says which run and step: QITS_CI_RUN_ID, QITS_CI_STEP_INDEX, QITS_CI_SHA, "
                        + "QITS_CI_REPO_NAME and QITS_CI_PROJECT_ID, all required. qits-ci is "
                        + "https://ci.qits.$QITS_DOMAIN (QITS_DOMAIN defaults to wohlben.eu); no variable and no "
                        + "option names another address.",
                "- The bearer comes from QITS_PUBLISH_TOKEN_COMMAND, QITS_PUBLISH_TOKEN, or QITS_COMMISSIONED_CLIENT_ID "
                        + "and QITS_COMMISSIONED_CLIENT_SECRET, the first that is set, as for `qits artifacts "
                        + "publish`. qits-ci takes only the run's own ci-run token, while the run is running.",
                "- No baseline (a first release, or qits-ci cannot say) is not an error: the report is sent without "
                        + "the comparison.",
                "- Gives up after 120 seconds in all, whatever is still waiting.",
                "- The exit code says whether the reports were stored, never whether the tests passed. The hook "
                        + "that runs this ignores it, so a report never changes a step's verdict."},
        exitCodeListHeading = HelpText.EXIT_CODES,
        exitCodeList = {"0:Every kind that found inputs was stored, or no kind found any.",
                "1:qits-ci refused a report (the message names the status: 403 for another run's token, 409 for a "
                        + "run that is not running, 404 for a qits-ci without the doors), could not be reached, or "
                        + "did not answer within 120 seconds.",
                "2:Used wrongly: --exit-code missing, a QITS_CI_* variable missing, --root not a directory, or no "
                        + "credential in the environment."})
public class SubmitCommand extends PlatformCommand {

    /** The whole command's limit. A step image may not have `timeout`, so the command keeps its own. */
    static final Duration DEADLINE = Duration.ofSeconds(120);

    static final String RUN_ID = "QITS_CI_RUN_ID";
    static final String STEP_INDEX = "QITS_CI_STEP_INDEX";
    static final String SHA = "QITS_CI_SHA";
    static final String REPO_NAME = "QITS_CI_REPO_NAME";
    static final String PROJECT_ID = "QITS_CI_PROJECT_ID";
    /** Where the step cloned from; the kinds that read the baseline's tree fetch its tag from here. */
    static final String REPOSITORY_URL = "QITS_CI_REPOSITORY_URL";

    @CommandLine.ParentCommand
    ReportCommand parent;

    @CommandLine.Option(names = "--exit-code", required = true, paramLabel = "<n>",
            description = "What the step's own script exited with. Kept with the report; a non-zero code with no "
                    + "failing test is a highlight of its own.")
    int exitCode;

    @CommandLine.Option(names = "--root", paramLabel = "<dir>",
            description = "Where the step's tree is: the reports are looked for, and file paths are relative to, "
                    + "this directory. Default: the working directory.")
    String root;

    /**
     * qits-ci's origin when the suite says so; null, as in every real run, composes it from
     * {@code QITS_DOMAIN}. A field set by the test's factory and not a variable, so nothing a step
     * container carries can point the step's bearer anywhere else ({@code AbstractPublishCommand.hosts}
     * is the same seam).
     */
    String ciOrigin;

    /** {@link #DEADLINE}, unless the suite waits less. */
    Duration deadline = DEADLINE;

    /**
     * The renderer image's provenance record, which says a step rendered screenshots; another path only
     * when the suite says so, because the machine running it may well be the renderer image.
     */
    Path rendererProvenance = VitestBrowserScreenshots.PROVENANCE;

    /** What the step's environment says, checked. */
    record Step(String runId, int stepIndex, String commitSha, RepositoryRef repository, String repositoryUrl) {
    }

    @Override
    protected int execute(CliContext context) throws CliFailure, InterruptedException {
        List<String> refused = new ArrayList<>(parent.ci().addressingOptionsGiven());
        if (parent.ci().output() != null) {
            refused.add("--output");
        }
        if (!refused.isEmpty()) {
            throw new CliFailure(String.join(", ", refused) + (refused.size() == 1 ? " does" : " do")
                    + " not apply to `qits ci report submit`: the run comes from QITS_CI_*, the address from "
                    + "QITS_DOMAIN, and the output is one line per kind.", CliFailure.USAGE);
        }
        Step step = step(context.env());
        Path tree = root(root);
        String origin = ciOrigin != null ? ciOrigin : Store.publicOrigin("ci", context.env());
        CiApi ci = new CiApi(new PlatformClient(PublishCredential.forCiStep(context.env(), context.err(),
                context.clock())), origin);
        FutureTask<Integer> task = new FutureTask<>(() -> submit(context, ci, step, tree));
        Thread worker = new Thread(task, "qits-report-submit");
        worker.setDaemon(true);
        worker.start();
        try {
            return task.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException late) {
            task.cancel(true);
            throw new CliFailure("Gave up after " + deadline.toSeconds() + " seconds: qits-ci (" + origin
                    + ") did not answer in time. What was not stored by then is not reported.", CliFailure.FAILED);
        } catch (InterruptedException interrupted) {
            task.cancel(true);
            throw interrupted;
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof CliFailure failure) {
                throw failure;
            }
            if (cause instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(cause);
        }
    }

    static Step step(Map<String, String> env) throws CliFailure {
        List<String> missing = new ArrayList<>();
        for (String name : List.of(RUN_ID, STEP_INDEX, SHA, REPO_NAME, PROJECT_ID)) {
            if (value(env, name) == null) {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            throw new CliFailure("Not in a CI step: " + String.join(", ", missing)
                    + (missing.size() == 1 ? " is" : " are") + " not set. qits-ci sets them in every step.",
                    CliFailure.USAGE);
        }
        int stepIndex;
        try {
            stepIndex = Integer.parseInt(value(env, STEP_INDEX));
        } catch (NumberFormatException notANumber) {
            stepIndex = -1;
        }
        if (stepIndex < 0) {
            throw new CliFailure(STEP_INDEX + " must be a step's index (0 or more), not '" + value(env, STEP_INDEX)
                    + "'.", CliFailure.USAGE);
        }
        return new Step(value(env, RUN_ID), stepIndex, value(env, SHA),
                new RepositoryRef(value(env, PROJECT_ID), value(env, REPO_NAME)), value(env, REPOSITORY_URL));
    }

    private static Path root(String given) throws CliFailure {
        Path tree;
        try {
            tree = given == null || given.isBlank() ? Path.of("") : Path.of(given.strip());
        } catch (InvalidPathException notAPath) {
            throw new CliFailure("--root '" + given + "' is not a path.", CliFailure.USAGE);
        }
        tree = tree.toAbsolutePath().normalize();
        if (!Files.isDirectory(tree)) {
            throw new CliFailure("--root " + tree + " is not a directory.", CliFailure.USAGE);
        }
        return tree;
    }

    private static String value(Map<String, String> env, String name) {
        String value = env.get(name);
        return value == null || value.isBlank() ? null : value.strip();
    }

    // --- the submit, on the worker thread ---------------------------------------------------------

    private int submit(CliContext context, CiApi ci, Step step, Path tree) throws CliFailure, InterruptedException {
        PrintStream out = context.out();
        PrintStream err = context.err();
        Optional<Baseline> baseline = baseline(ci, step.runId(), err);
        Consumer<String> warnings = line -> err.println("WARN: " + line);
        // Both lazy: nothing touches git unless a kind that found inputs asks for the baseline's tree.
        BaselineTag tag = baseline.isPresent()
                ? BaselineTag.in(tree, baseline, step.repositoryUrl(), warnings) : BaselineTag.none();
        ChangedLines changed = baseline.isPresent() ? new GitChangedLines(tag, warnings) : ChangedLines.unavailable();
        StepContext stepContext = new StepContext(tree, step.runId(), step.stepIndex(), step.repository(),
                step.commitSha(), exitCode, baseline, changed, TestCaseLocators.registered(), tag);
        ReportKinds kinds = ReportKinds.standard(warnings, context.env()::get, rendererProvenance);
        boolean allStored = true;
        for (ReportKind<?> kind : kinds.kinds()) {
            allStored &= submit(kind, kinds, stepContext, ci, out, err);
        }
        out.flush();
        return allStored ? 0 : CliFailure.FAILED;
    }

    /**
     * The run's baseline, or none. Nothing about it is an error: a 404 is a qits-ci that has no
     * baseline to give (or no door yet), and any other failure is said and read as none, so the
     * report still goes out without its comparison. That includes a missing credential: a step with
     * nothing to report needs none, and one with something meets it again at the PUT, as a usage
     * error.
     */
    private static Optional<Baseline> baseline(CiApi ci, String runId, PrintStream err) throws InterruptedException {
        JsonNode answer;
        try {
            answer = ci.baseline(runId);
        } catch (CliFailure failed) {
            if (failed.status() == 404) {
                err.println("baseline: none (qits-ci answered HTTP 404)");
            } else {
                err.println("WARN: baseline: could not be read, so none: " + oneLine(failed.getMessage()));
            }
            return Optional.empty();
        }
        JsonNode found = answer.path("baseline");
        String version = text(found, "version");
        String baselineRun = text(found, "runId");
        if (!found.isObject() || version == null || baselineRun == null) {
            err.println("baseline: none");
            return Optional.empty();
        }
        err.println("baseline: " + version + " (run " + baselineRun + ")");
        return Optional.of(new Baseline(version, baselineRun, text(found, "releaseRequestId"), text(found, "tagSha")));
    }

    /** One kind: collect, compare, PUT. True when it was stored or had nothing to store. */
    private static <P> boolean submit(ReportKind<P> kind, ReportKinds kinds, StepContext step, CiApi ci,
                                      PrintStream out, PrintStream err) throws CliFailure, InterruptedException {
        Optional<P> report;
        try {
            report = kind.collect(step, kinds.parsersOf(kind.id()));
        } catch (IOException | RuntimeException broken) {
            out.println(kind.id() + ": not reported (could not collect: " + oneLine(String.valueOf(broken.getMessage()))
                    + ")");
            return true;
        }
        if (report.isEmpty()) {
            out.println(kind.id() + ": not reported (" + kind.notReported() + ")");
            return true;
        }
        Optional<P> baseline = step.baseline().isPresent()
                ? baselinePayload(kind, step, ci, err) : Optional.empty();
        P compared = kind.compared(report.get(), baseline, step);
        List<Highlight> highlights = kind.highlight(compared, baseline, step);
        if (highlights.size() > Highlight.MAX_PER_REPORT) {
            highlights = highlights.subList(0, Highlight.MAX_PER_REPORT);
        }
        ObjectNode submission = ReportJson.MAPPER.createObjectNode();
        submission.put("kindVersion", kind.version());
        submission.set("highlights", ReportJson.MAPPER.valueToTree(highlights));
        if (step.baseline().isPresent()) {
            submission.putObject("baseline")
                    .put("runId", step.baseline().get().runId())
                    .put("version", step.baseline().get().version());
        } else {
            submission.putNull("baseline");
        }
        submission.set("payload", ReportJson.MAPPER.valueToTree(compared));
        try {
            ci.putReport(step.runId(), step.stepIndex(), kind.id(), submission);
        } catch (CliFailure refused) {
            if (refused.exitCode() == CliFailure.USAGE) {
                throw refused;
            }
            out.println(kind.id() + ": not submitted ("
                    + (refused.status() > 0 ? "HTTP " + refused.status() : "qits-ci could not be reached") + ")");
            err.println(refused.getMessage());
            return false;
        }
        String said = kind.describe(compared);
        out.println(kind.id() + ": submitted" + (said.isEmpty() ? "" : " (" + said + ")"));
        return true;
    }

    /**
     * The baseline's report of this kind, read back as the kind's payload. Only one of the same
     * version: another version is another schema, and reads as no baseline. Where the baseline run
     * has several (one per step), the one from the same step index is preferred.
     */
    private static <P> Optional<P> baselinePayload(ReportKind<P> kind, StepContext step, CiApi ci, PrintStream err)
            throws CliFailure, InterruptedException {
        JsonNode reports;
        try {
            reports = ci.baselineReports(step.runId(), kind.id());
        } catch (CliFailure failed) {
            if (failed.exitCode() == CliFailure.USAGE) {
                throw failed;
            }
            if (failed.status() != 404) {
                err.println("WARN: " + kind.id() + ": the baseline's report could not be read, so none: "
                        + oneLine(failed.getMessage()));
            }
            return Optional.empty();
        }
        JsonNode chosen = null;
        for (JsonNode candidate : reports) {
            if (candidate.path("kindVersion").asInt(-1) != kind.version()) {
                continue;
            }
            if (chosen == null || candidate.path("stepIndex").asInt(-1) == step.stepIndex()) {
                chosen = candidate;
            }
        }
        if (chosen == null) {
            return Optional.empty();
        }
        try {
            JsonNode payload = chosen.path("payload");
            if (payload.isTextual()) {
                payload = ReportJson.MAPPER.readTree(payload.asText());
            }
            if (!payload.isObject()) {
                return Optional.empty();
            }
            return Optional.of(ReportJson.MAPPER.treeToValue(payload, kind.payloadType()));
        } catch (IOException | IllegalArgumentException unreadable) {
            err.println("WARN: " + kind.id() + ": the baseline's report does not read as version " + kind.version()
                    + ", so none.");
            return Optional.empty();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }
}
