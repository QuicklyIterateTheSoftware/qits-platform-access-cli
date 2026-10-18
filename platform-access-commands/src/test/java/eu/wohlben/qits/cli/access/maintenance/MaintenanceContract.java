package eu.wohlben.qits.cli.access.maintenance;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliRow;
import eu.wohlben.qits.cli.access.contracts.Reached;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.pact.consumer.GoldenMasters.Operation;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>What the commands ask qits-maintenance, and why</b> (ticket qits-1149): {@code qits maintenance
 * automations}, {@code automation run} and {@code bump}, through {@link MaintenanceApi}, written to
 * {@code pacts/qits-platform-access-cli_qits-maintenance-service.json}.
 * <p>
 * qits-maintenance records the automations of a request at a named fold, and a re-run with no body
 * but {@code {}}: those are what {@code --fold} and a run without {@code --work-item} send. The list
 * at the request's newest fold (no {@code ?foldSha=}) and a run naming a work item ({@code
 * {"workItem": …}}, where the recording sends {@code {"workItem": null, "repository": …}}) are
 * pending on their states.
 */
public final class MaintenanceContract {

    static final String WITH_AUTOMATIONS = "a release request with automations";
    static final String TO_RE_RUN = "a release request with an automation to re-run";
    static final String PENDING_BUMPS = "pending bumps";
    /** Not recorded: listReleaseRequestAutomations without foldSha. */
    static final String AT_NEWEST_FOLD = "a release request with automations at its newest fold";
    /** Not recorded: runReleaseRequestAutomation with {"workItem": "<id>"}. */
    static final String FOR_A_WORK_ITEM = "a release request with an automation to re-run for a work item";

    private static final CliPact BASE = CliPact.of("qits-maintenance-service", "qits-maintenance");

    /** The automations table: kind, state, fold, newest run and detail. */
    static final List<String> AUTOMATIONS = List.of("foldSha", "automations[].kind", "automations[].state",
            "automations[].runIds", "automations[].detail");

    /** One bump as {@code MaintenanceCommand.printBump} prints it, each field only where the service sends it. */
    static final List<String> BUMP = List.of("id", "mode", "repository", "branch", "status", "message", "resultSha",
            "releaseRequestId", "ciRunId", "startedAt", "finishedAt");

    @FunctionalInterface
    interface Call {
        void run(MaintenanceApi api, Map<String, String> params, Operation recorded) throws Exception;
    }

    private static CliRow.Call api(Call call) {
        return (url, recorded) -> call.run(new MaintenanceApi(new PlatformClient(Reached.CREDENTIAL), url),
                recorded.params(), recorded);
    }

    private static Call automations(boolean atFold) {
        return (api, params, recorded) -> {
            JsonNode answer = api.automations(params.get("requestId"), atFold ? params.get("foldSha") : null);
            assertThat(answer.path("foldSha").asText()).isNotBlank();
            assertThat(answer.path("automations")).isNotEmpty().allSatisfy(a -> {
                assertThat(a.path("kind").asText()).isNotBlank();
                assertThat(a.path("state").asText()).isNotBlank();
                assertThat(a.path("runIds").isArray()).isTrue();
            });
        };
    }

    private static Call run(boolean workItem) {
        return (api, params, recorded) -> assertThat(api.runAutomation(params.get("requestId"), params.get("kind"),
                workItem ? params.get("workItem") : null).path("id").asText()).isNotBlank();
    }

    private static final Call BUMP_READ = (api, params, recorded) -> {
        String id = recorded.examplePath().substring(recorded.examplePath().lastIndexOf('/') + 1);
        JsonNode bump = api.bump(id);
        assertThat(bump.path("id").asText()).isEqualTo(id);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        MaintenanceCommand.printBump(new PrintStream(bytes, true, StandardCharsets.UTF_8), bump);
        assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("status");
    };

    static final List<CliRow> ROWS = List.of(
            row("qits maintenance automations", WITH_AUTOMATIONS, "listReleaseRequestAutomations", "automations",
                    automations(true)).consumes(AUTOMATIONS),
            row("qits maintenance automations", AT_NEWEST_FOLD, "listReleaseRequestAutomations", "automations",
                    automations(false)).consumes(AUTOMATIONS)
                    .request("GET /maintenance/api/release-requests/{requestId}/automations"),
            row("qits maintenance automation run", TO_RE_RUN, "runReleaseRequestAutomation", "runAutomation",
                    run(false)).consumes("id"),
            row("qits maintenance automation run", FOR_A_WORK_ITEM, "runReleaseRequestAutomation", "runAutomation",
                    run(true)).consumes("id")
                    .request("POST /maintenance/api/release-requests/{requestId}/automations/{kind}/runs"),
            row("qits maintenance bump", PENDING_BUMPS, "getBump", "bump", BUMP_READ)
                    .consumes(BASE.held(PENDING_BUMPS, "getBump", BUMP)));

    public static final CliPact CONTRACT = BASE.client(MaintenanceApi.class).rows(ROWS);

    private static CliRow row(String command, String state, String operationId, String method, Call call) {
        return CliRow.of(command, state, operationId, "MaintenanceApi#" + method, api(call));
    }

    private MaintenanceContract() {
    }
}
