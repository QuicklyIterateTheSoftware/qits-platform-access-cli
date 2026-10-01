package eu.wohlben.qits.cli.access.observe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.PlatformUrls;
import eu.wohlben.qits.cli.access.platform.QueryWindow;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code qits observe query}: what qits-observability still holds in its buffer for a window,
 * filtered with the live command's grammar, from {@code POST
 * /observability/api/telemetry/records/search}. Never the socket.
 * <p>
 * The service answers with the live stream's frames, newest {@code limit} kept and oldest first,
 * so {@link RecordLine} prints them as it prints the live ones. {@code bufferedSince} is how far back
 * the buffer reaches; a window that starts before it asks about what the service has forgotten, and
 * stderr says so, because an empty answer there is not proof of absence.
 */
@TuiCommand(interaction = Interaction.PLAIN)
@CommandLine.Command(name = "query", mixinStandardHelpOptions = true,
        description = {
                "Print the logs, spans and metrics qits-observability still holds for a window of time, oldest "
                        + "first, filtered as `qits observe` filters. It answers with what the service holds when "
                        + "it is called and never waits for a new record; `qits observe` is the live form.",
                "The window is --since to --until, both inclusive; --since is an hour ago and --until is now unless "
                        + "given. A record is in the window by its own time: a log's time, a span's start. A metric "
                        + "keeps only its latest point, so a metric is found only when that point is in the window. "
                        + "When more than --limit records match, the newest --limit are kept.",
                "The service keeps a bounded buffer, not a history. When the window starts before the oldest record "
                        + "it still holds, a note on stderr says so: an empty answer there is not proof that nothing "
                        + "happened.",
                "The text form starts with a line giving the window as two absolute instants, then prints one line "
                        + "per record, the line `qits observe` prints, and ends with a line saying so when the answer "
                        + "was cut. -o json prints one object: {\"records\": [...], \"truncated\": true|false, "
                        + "\"window\": {\"since\": ..., \"until\": ...}}, each record the frame the live stream sends.",
                "--filter takes the conditions and fields `qits observe` takes; see `qits observe --help`."},
        footerHeading = HelpText.EXAMPLES,
        footer = {
                "  qits observe query --filter 'kind=log level>=ERROR' --since 2h",
                "  qits observe query --filter 'trace=4bf92f3577b34da6a3ce929d0e0e4736' --since 1d --limit 1000",
                "  qits observe query --filter 'kind=span status=ERROR' --source _service/qits-ci -o json",
                "",
                "- --source takes a key as the service's telemetry sources list it (_service/<name>, a repository or "
                        + "a workspace). Without it every source is searched."},
        exitCodeListHeading = HelpText.EXIT_CODES,
        exitCodeList = {HelpText.DONE, HelpText.REFUSED,
                "2:Used wrongly (a --filter that cannot be read or that the service refused, a time that cannot be "
                        + "read, --since after --until, a --limit outside 1..1000), not signed in, or the session "
                        + "ended."})
public class ObserveQueryCommand extends PlatformCommand {

    @CommandLine.Option(names = "--filter", required = true, paramLabel = "<conditions>",
            description = "One group of conditions, for example 'kind=log level>=ERROR'. Give it again for another "
                    + "group. '*' matches every record.")
    List<String> filters;

    @CommandLine.Option(names = "--source", paramLabel = "<key>",
            description = "Only this source's records: a key such as _service/qits-ci. Default: every source.")
    String source;

    @CommandLine.Option(names = "--observability-url", paramLabel = "<url>",
            description = "The observability service's base URL, without /observability. Default: "
                    + "QITS_OBSERVABILITY_URL, else the session's idp address with `idp` swapped for `observability`.")
    String observabilityUrl;

    @CommandLine.Option(names = {"-o", "--output"}, paramLabel = "<text|json>", defaultValue = "text",
            description = "text: the window, then one line per record (the default). json: one object with the "
                    + "records, whether the answer was cut, and the window.")
    String output;

    @CommandLine.Mixin
    QueryWindow window;

    @Override
    protected int execute(CliContext context) throws CliFailure, InterruptedException {
        boolean json = QueryWindow.json(output);
        List<List<FilterGrammar.Condition>> groups = new ArrayList<>();
        for (String filter : filters) {
            groups.add(FilterGrammar.parse(filter));
        }
        QueryWindow.Resolved resolved = window.resolve(context.clock());
        ObjectNode body = body(groups, resolved, source);
        PlatformClient client = new PlatformClient(context.credential());
        URI uri = searchUri(PlatformUrls.observability(observabilityUrl, context.env(), context.idpUrl()));

        JsonNode answer;
        try {
            answer = client.post(uri, body);
        } catch (CliFailure failure) {
            if (failure.status() == 400) {
                // The service read the filter or the window and refused it: the caller's to fix.
                throw new CliFailure("The observability service refused the search. " + failure.getMessage(),
                        CliFailure.USAGE);
            }
            throw failure;
        }
        JsonNode records = answer.path("records");
        if (!records.isArray()) {
            throw new CliFailure("POST " + uri + " answered without a records list.", CliFailure.FAILED);
        }
        Instant bufferedSince = instant(answer.path("bufferedSince"));
        String note = bufferNote(resolved.since(), bufferedSince);
        if (note != null) {
            context.err().println("qits observe query: " + note);
            context.err().flush();
        }
        List<JsonNode> frames = new ArrayList<>();
        records.forEach(frames::add);
        print(context.out(), json, frames, answer.path("truncated").asBoolean(false), resolved);
        return 0;
    }

    /** {@code {subscribe, since, until, limit, source}}: the live subscribe frame's groups and the window. */
    static ObjectNode body(List<List<FilterGrammar.Condition>> groups, QueryWindow.Resolved window, String source) {
        ObjectNode body = SafeText.JSON.createObjectNode();
        try {
            // The same groups the socket's subscribe frame carries, written by the same code.
            body.set("subscribe", SafeText.JSON.readTree(FilterGrammar.subscribeFrame(groups)).get("subscribe"));
        } catch (IOException impossible) {
            throw new IllegalStateException("the subscribe frame is JSON this class wrote", impossible);
        }
        body.put("since", window.since().toString());
        body.put("until", window.until().toString());
        body.put("limit", window.limit());
        if (source == null || source.isBlank()) {
            body.putNull("source");
        } else {
            body.put("source", source.strip());
        }
        return body;
    }

    /**
     * What stderr says about the buffer's reach, or null when the window lies within it. With no
     * oldest record the searched sources hold nothing at all, which an empty answer must not hide.
     */
    static String bufferNote(Instant since, Instant bufferedSince) {
        if (bufferedSince == null) {
            return "the searched sources hold no records at all, so an empty answer is not proof that nothing "
                    + "happened.";
        }
        if (since.isBefore(bufferedSince)) {
            return "the buffer reaches back only to " + bufferedSince + ", and the window starts at " + since
                    + ". Before " + bufferedSince + " the service has forgotten, so an empty answer there is not "
                    + "proof that nothing happened.";
        }
        return null;
    }

    static URI searchUri(String base) throws CliFailure {
        try {
            URI uri = URI.create(base + "/observability/api/telemetry/records/search");
            if (uri.getHost() == null) {
                throw new IllegalArgumentException("no host");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new CliFailure("'" + base + "' is not a usable observability address. Give an http or https base "
                    + "URL.", CliFailure.USAGE);
        }
    }

    private static Instant instant(JsonNode node) {
        if (!node.isTextual()) {
            return null;
        }
        try {
            return Instant.parse(node.asText());
        } catch (DateTimeException unreadable) {
            return null;
        }
    }

    private static void print(PrintStream out, boolean json, List<JsonNode> frames, boolean truncated,
                              QueryWindow.Resolved window) {
        if (json) {
            ObjectNode answer = SafeText.JSON.createObjectNode();
            ArrayNode list = answer.putArray("records");
            frames.forEach(list::add);
            answer.put("truncated", truncated);
            answer.putObject("window").put("since", window.since().toString()).put("until", window.until().toString());
            try {
                out.println(SafeText.JSON.writeValueAsString(answer));
            } catch (IOException impossible) {
                out.println("{}");
            }
            return;
        }
        out.println(QueryWindow.windowLine(window));
        ZoneId zone = ZoneId.systemDefault();
        for (JsonNode frame : frames) {
            out.println(RecordLine.render(frame, zone));
        }
        if (truncated) {
            out.println(QueryWindow.truncatedLine(frames.size()));
        }
    }
}
