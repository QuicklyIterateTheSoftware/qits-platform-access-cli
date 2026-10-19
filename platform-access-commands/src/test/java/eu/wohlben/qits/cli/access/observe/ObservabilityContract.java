package eu.wohlben.qits.cli.access.observe;

import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliRow;
import eu.wohlben.qits.cli.access.contracts.InPlatformCli;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>What {@code qits observe} asks qits-observability over REST, and why</b> (ticket qits-1149):
 * the records search of {@code observe query} and the buffer reads of {@code observe sources|errors|
 * slow-spans|metrics}, each row the real command against a pact mock server, written to {@code
 * pacts/qits-platform-access-cli_qits-observability-service.json}. The live stream is a WebSocket
 * ({@code ObserveStream}) and no part of this pact.
 * <p>
 * {@code observe slow-spans} always sends its {@code --threshold-ms} (500 by default), which
 * qits-observability records with the one service's telemetry ({@code thresholdMs=500}).
 */
public final class ObservabilityContract {

    static final String ONE_SERVICE = "telemetry from one platform service";
    static final String EVERY_KIND = "telemetry records of every kind";

    private static final CliPact BASE = CliPact.of("qits-observability-service", "qits-observability");

    private static CliRow.Call command(String... args) {
        return (url, recorded) -> {
            List<String> argv = new ArrayList<>();
            for (String arg : args) {
                argv.add(recorded.example(arg));
            }
            InPlatformCli.Result result = InPlatformCli.run(Map.of("QITS_OBSERVABILITY_URL", url),
                    argv.toArray(String[]::new));
            assertThat(result.exit()).as(result.err()).isZero();
            assertThat(result.out()).isNotBlank();
        };
    }

    static final List<CliRow> ROWS = List.of(
            CliRow.of("qits observe query", EVERY_KIND, "searchTelemetryRecords", "ObserveQueryCommand#execute",
                            command("observe", "query", "--filter", "*", "--since", "{since}", "--until", "{until}",
                                    "--limit", "100", "--source", "{source}"))
                    .consumes(BASE.held(EVERY_KIND, "searchTelemetryRecords", List.of("records[].kind",
                            "records[].receivedAtMillis", "records[].source", "records[].record", "truncated",
                            "bufferedSince"))),
            read("qits observe sources", ONE_SERVICE, "listTelemetrySources", command("observe", "sources"))
                    .consumes("sources[].key", "sources[].kind", "sources[].label", "sources[].spans",
                            "sources[].logs", "sources[].metricSeries", "sources[].newestReceivedAt"),
            read("qits observe errors", ONE_SERVICE, "listTelemetryErrors",
                    command("observe", "errors", "--source", "{source}"))
                    .consumes(BASE.held(ONE_SERVICE, "listTelemetryErrors", List.of("groups[].traceId",
                            "groups[].serviceName", "groups[].errorSpans[].name", "groups[].errorSpans[].statusMessage",
                            "groups[].errorLogs[].body", "truncated", "total"))),
            read("qits observe slow-spans", ONE_SERVICE, "listSlowSpans",
                    command("observe", "slow-spans", "--source", "{source}"))
                    .consumes(BASE.held(ONE_SERVICE, "listSlowSpans", List.of("spans[].durationMs",
                            "spans[].serviceName", "spans[].name", "spans[].status", "spans[].traceId", "truncated",
                            "total"))),
            read("qits observe metrics", ONE_SERVICE, "listTelemetryMetrics",
                    command("observe", "metrics", "--source", "{source}"))
                    .consumes(BASE.held(ONE_SERVICE, "listTelemetryMetrics", List.of("metrics[].name",
                            "metrics[].value", "metrics[].unit", "metrics[].serviceName", "metrics[].attributes"))));

    public static final CliPact CONTRACT = BASE
            .client(ObserveQueryCommand.class, "execute")
            .client(ObserveTelemetryCommands.TelemetryRead.class, "get")
            .rows(ROWS);

    private static CliRow read(String command, String state, String operationId, CliRow.Call call) {
        return CliRow.of(command, state, operationId, "ObserveTelemetryCommands$TelemetryRead#get", call);
    }

    private ObservabilityContract() {
    }
}
