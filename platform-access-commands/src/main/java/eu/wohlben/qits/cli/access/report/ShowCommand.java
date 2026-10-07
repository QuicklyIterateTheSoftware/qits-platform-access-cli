package eu.wohlben.qits.cli.access.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import eu.wohlben.qits.cli.access.ci.CiCommand;
import eu.wohlben.qits.cli.access.complete.RunSource;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.Table;
import eu.wohlben.qits.cli.tui.api.Completes;
import picocli.CommandLine;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code qits ci report show}: a run's release reports, read with the session's credential like
 * {@code qits ci run}. Everything a report says was written by the code the run built, so every value
 * goes through {@link SafeText}.
 */
@CommandLine.Command(name = "show", mixinStandardHelpOptions = true,
        description = {"Show a CI run's release reports: each kind, its version, the step that submitted it, and "
                        + "its highlights (\"3 tests failed\").",
                "--kind also prints that kind's whole report as JSON: for test-results, the totals, the suites, "
                        + "and each failing test with its file, class, name, shape and message; for coverage, the "
                        + "total, the baseline's total, the changed lines' coverage with the uncovered ones, and a "
                        + "line per file; for contracts, the pacts by role (CONSUMER, PROVIDER) and pair with their "
                        + "interactions, and the provider states with their operations; for entity-changes, each "
                        + "diagram's unit with its status, the tables, columns and relations that changed, and its "
                        + "Mermaid text before and after."},
        footerHeading = HelpText.EXAMPLES,
        footer = {"  qits ci report show 5f2c0a9e-1b7d-4c2e-9a41-3d8e6f0b2c17",
                "  qits ci report show 5f2c0a9e --project qits --repository qits-ci-service --kind test-results",
                "  qits ci report show 5f2c0a9e --project qits --repository qits-ci-service --kind coverage",
                "  qits ci report show 5f2c0a9e --project qits --repository qits-landing-app --kind contracts",
                "  qits ci report show 5f2c0a9e --project qits --repository qits-ci-service --kind entity-changes",
                "  qits ci report show 5f2c0a9e-1b7d-4c2e-9a41-3d8e6f0b2c17 -o json",
                "",
                "- A release request's QA run is the one with reports: `qits ci runs --release-request <id>` "
                        + "finds it.",
                "- <run id> is the run's whole id, or its start when --project and --repository name the "
                        + "repository.",
                "- -o json prints the service's answer: the summaries, or with --kind the whole reports of that "
                        + "kind."},
        exitCodeListHeading = HelpText.EXIT_CODES,
        exitCodeList = {"0:Done, whatever the reports say, and also when the run has none.",
                "1:The platform refused (for example no such run, HTTP 404), or cannot be reached.",
                "2:Used wrongly (for example an id start that fits no run of the repository, or more than one), "
                        + "not signed in, or the session ended."})
public class ShowCommand extends PlatformCommand {

    @CommandLine.ParentCommand
    ReportCommand parent;

    @Completes(RunSource.class)
    @CommandLine.Parameters(index = "0", paramLabel = "<run id>",
            description = "The run: its id, or its start when --project and --repository name its repository.")
    String runId;

    @CommandLine.Option(names = "--kind", paramLabel = "<kind>",
            description = "Only this kind of report (test-results, coverage, contracts, entity-changes), "
                    + "and print the whole of it.")
    String kind;

    @Override
    protected int execute(CliContext context) throws CliFailure, InterruptedException {
        boolean json = json(parent.ci().output());
        if (runId == null || runId.isBlank()) {
            throw new CliFailure("Name the run: qits ci report show <run id>.", CliFailure.USAGE);
        }
        String wantedKind = kind == null || kind.isBlank() ? null : kind.strip();
        CiCommand.Target target = parent.ci().target(context, runId.strip());
        JsonNode answer;
        try {
            answer = target.ci().reports(target.runId());
        } catch (CliFailure refused) {
            throw refused.status() == 404 ? target.noSuchRun() : refused;
        }
        List<JsonNode> shown = new ArrayList<>();
        for (JsonNode report : answer.path("reports")) {
            if (wantedKind == null || wantedKind.equals(report.path("kind").asText())) {
                shown.add(report);
            }
        }
        PrintStream out = context.out();
        if (wantedKind == null) {
            if (json) {
                printJson(out, answer);
            } else {
                printSummaries(out, answer, shown, target.runId());
            }
            return 0;
        }
        List<JsonNode> whole = new ArrayList<>();
        for (JsonNode summary : shown) {
            whole.add(target.ci().report(target.runId(), summary.path("id").asText()));
        }
        if (json) {
            ArrayNode array = SafeText.JSON.createArrayNode();
            whole.forEach(array::add);
            printJson(out, array);
            return 0;
        }
        if (whole.isEmpty()) {
            out.println("Run " + SafeText.line(target.runId()) + " has no " + SafeText.line(wantedKind) + " report.");
            return 0;
        }
        printSummaries(out, answer, shown, target.runId());
        for (JsonNode report : whole) {
            out.println();
            out.println(SafeText.line(report.path("kind").asText()) + " (version "
                    + SafeText.line(report.path("kindVersion").asText()) + ", step "
                    + SafeText.line(report.path("stepIndex").asText()) + "):");
            JsonNode payload = report.path("payload");
            if (payload.isTextual()) {
                try {
                    payload = SafeText.JSON.readTree(payload.asText());
                } catch (JsonProcessingException notJson) {
                    // Printed as the string it is.
                }
            }
            printJson(out, payload);
        }
        return 0;
    }

    private static void printSummaries(PrintStream out, JsonNode answer, List<JsonNode> reports, String runId) {
        String commit = answer.path("commitSha").asText("");
        String request = answer.path("releaseRequestId").asText("");
        out.println("Run " + SafeText.line(runId)
                + (commit.isEmpty() ? "" : ", commit " + SafeText.line(commit.length() > 12 ? commit.substring(0, 12) : commit))
                + (request.isEmpty() ? "" : ", release request " + SafeText.line(request)));
        JsonNode baseline = answer.path("baseline");
        out.println(baseline.isObject()
                ? "Baseline: " + SafeText.line(baseline.path("version").asText("-")) + " (run "
                + SafeText.line(baseline.path("runId").asText("-")) + ")"
                : "Baseline: none");
        if (reports.isEmpty()) {
            out.println("No reports.");
            return;
        }
        List<List<String>> rows = new ArrayList<>();
        for (JsonNode report : reports) {
            List<String> highlights = new ArrayList<>();
            for (JsonNode highlight : report.path("highlights")) {
                highlights.add("[" + SafeText.line(highlight.path("severity").asText()) + "] "
                        + SafeText.line(highlight.path("text").asText()));
            }
            if (highlights.isEmpty()) {
                highlights.add("-");
            }
            for (int i = 0; i < highlights.size(); i++) {
                rows.add(i == 0
                        ? List.of(Table.cell(SafeText.line(report.path("kind").asText()), 40),
                        SafeText.line(report.path("kindVersion").asText("-")),
                        SafeText.line(report.path("stepIndex").asText("-")), highlights.get(i))
                        : List.of("", "", "", highlights.get(i)));
            }
        }
        Table.print(out, "", List.of("KIND", "VERSION", "STEP", "HIGHLIGHTS"), rows);
    }

    /** The service's answer, indented, with every control character written as an escape. */
    private static void printJson(PrintStream out, JsonNode node) throws CliFailure {
        try {
            out.println(SafeText.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node));
        } catch (JsonProcessingException impossible) {
            throw new CliFailure("cannot print the answer as JSON", CliFailure.FAILED);
        }
    }
}
