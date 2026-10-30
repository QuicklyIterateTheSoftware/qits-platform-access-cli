package eu.wohlben.qits.cli.access.deployments;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliRow;
import eu.wohlben.qits.cli.access.contracts.Reached;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.pact.consumer.GoldenMasters.Operation;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>What {@code qits deployments settings} asks qits-deployments, and why</b> (epic qits-765,
 * qits-1390): list, set, delete and preview, through {@link DeploymentsApi}, written to {@code
 * pacts/qits-platform-access-cli_qits-deployments-service.json} once the provider records them.
 * <p>
 * Every row is pending until a pinned {@code qits-deployments-golden-masters} records its state.
 * The set row sends the recorded body ({@code {"value", "secret"}}) through the client, so the pact
 * holds the command's own body shape.
 */
public final class DeploymentsContract {

    /** An application with a plain and a secret setting in one environment. */
    static final String WITH_SETTINGS = "an application with settings in an environment";
    /** An application deployed in an environment whose settings changed since that deployment. */
    static final String SETTINGS_CHANGED = "an application whose settings changed since its deployment";

    private static final CliPact BASE = CliPact.of("qits-deployments-service", "qits-deployments");

    /** The settings table, and the mask that needs `secret`. */
    static final List<String> SETTING = List.of("application", "environment", "key", "value", "secret", "updatedBy");

    @FunctionalInterface
    interface Call {
        void run(DeploymentsApi api, Map<String, String> params, Operation recorded) throws Exception;
    }

    private static CliRow.Call api(Call call) {
        return (url, recorded) -> call.run(new DeploymentsApi(new PlatformClient(Reached.CREDENTIAL), url),
                recorded.params(), recorded);
    }

    private static final Call LIST = (api, params, recorded) -> {
        JsonNode answer = api.settings(params.get("application"), params.get("environment"));
        List<JsonNode> settings = new ArrayList<>();
        answer.path("settings").forEach(settings::add);
        assertThat(settings).isNotEmpty().allSatisfy(s -> assertThat(s.path("key").asText()).isNotBlank());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        SettingsCommand.printSettings(new PrintStream(bytes, true, StandardCharsets.UTF_8), settings);
        assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("KEY");
    };

    private static final Call SET = (api, params, recorded) -> {
        JsonNode sent = CliRow.exampleBody(recorded);
        JsonNode stored = api.putSetting(params.get("application"), params.get("environment"), params.get("key"),
                sent.path("value").asText(), sent.path("secret").asBoolean());
        assertThat(stored.path("key").asText()).isEqualTo(params.get("key"));
    };

    private static final Call DELETE = (api, params, recorded) ->
            api.deleteSetting(params.get("application"), params.get("environment"), params.get("key"));

    private static final Call PREVIEW = (api, params, recorded) -> {
        JsonNode preview = api.previewSettings(params.get("application"), params.get("environment"), false);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        SettingsCommand.printPreview(new PrintStream(bytes, true, StandardCharsets.UTF_8), preview);
        assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("unchanged.");
    };

    static final List<CliRow> ROWS = List.of(
            row("qits deployments settings list", WITH_SETTINGS, "listSettings", "settings", LIST)
                    .consumes(BASE.held(WITH_SETTINGS, "listSettings", SETTING.stream().map(p -> "settings[]." + p).toList()))
                    .request("GET /deployments/api/settings?application={application}&environment={environment}"),
            row("qits deployments settings set", WITH_SETTINGS, "putSetting", "putSetting", SET)
                    .consumes(BASE.held(WITH_SETTINGS, "putSetting", SETTING))
                    .request("PUT /deployments/api/settings/{application}/{environment}/{key}"),
            row("qits deployments settings delete", WITH_SETTINGS, "deleteSetting", "deleteSetting", DELETE)
                    .request("DELETE /deployments/api/settings/{application}/{environment}/{key}"),
            row("qits deployments settings preview", SETTINGS_CHANGED, "previewSettings", "previewSettings", PREVIEW)
                    .consumes(BASE.held(SETTINGS_CHANGED, "previewSettings", List.of(
                            "added[].key", "added[].value", "added[].secret",
                            "removed[].key", "removed[].value", "removed[].secret",
                            "changed[].key", "changed[].from", "changed[].to", "changed[].secret", "unchanged")))
                    .request("GET /deployments/api/settings/preview/{application}/{environment}"));

    public static final CliPact CONTRACT = BASE.client(DeploymentsApi.class).rows(ROWS);

    private static CliRow row(String command, String state, String operationId, String method, Call call) {
        return CliRow.of(command, state, operationId, "DeploymentsApi#" + method, api(call));
    }

    private DeploymentsContract() {
    }
}
