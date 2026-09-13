package eu.wohlben.qits.cli.access.events;

import eu.wohlben.qits.cli.access.platform.AccessTokens;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.PlatformUrls;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@TuiCommand(interaction = Interaction.STREAMING)
@CommandLine.Command(name = "events", mixinStandardHelpOptions = true,
        description = {
                "Print qits-events domain events as they happen, one JSON object per line on stdout. Use it to wait "
                        + "for something on the platform: a build (BuildSuccessful, BuildFailed) or a release "
                        + "request (ReleaseRequestChanged).",
                "Each line is the event with its payload read as JSON. Live only: the stream has no replay, so start "
                        + "it before the thing you wait for; events that happen while it reconnects are missed. "
                        + "Notes go to stderr. Stops on SIGINT or SIGTERM."},
        footerHeading = HelpText.EXAMPLES,
        footer = {
                "  qits events --filter=BuildSuccessful,BuildFailed",
                "  qits events --filter=ReleaseRequestChanged | jq -r '.payload'",
                "",
                "--filter takes exact event names. A pattern such as Build* is refused."},
        exitCodeListHeading = HelpText.EXIT_CODES,
        exitCodeList = {"0:Stopped by SIGINT or SIGTERM, or stdout was closed.",
                "1:The platform refused the stream (401, 403 or another 4xx).",
                "2:Used wrongly (for example a pattern in --filter), not signed in, or the session ended."})
public class EventsCommand extends PlatformCommand {

    @CommandLine.Option(names = "--filter", paramLabel = "<names>", defaultValue = "*",
            description = "Which events, as services subscribe to them: exact event names, comma-separated "
                    + "(for example BuildSuccessful,BuildFailed), or * for every event. No patterns. "
                    + "Default: ${DEFAULT-VALUE}.")
    String filter;

    @CommandLine.Option(names = "--events-url", paramLabel = "<url>",
            description = "The events service's base URL, without /events. Default: QITS_EVENTS_URL, else the "
                    + "session's idp address with `idp` swapped for `events`.")
    String eventsUrl;

    @Override
    protected int execute(CliContext context) throws CliFailure, InterruptedException {
        String names = names(filter);
        AccessTokens tokens = context.tokens();
        // Fails at once without a session, rather than in the reconnect loop.
        String idpUrl = tokens.session().idpUrl();
        String base = PlatformUrls.events(eventsUrl, context.env(), idpUrl);
        URI uri;
        try {
            uri = URI.create(base + "/events/api/stream?names=" + names);
        } catch (IllegalArgumentException e) {
            throw new CliFailure("'" + base + "' is not a usable events address.", CliFailure.USAGE);
        }
        EventStream stream = new EventStream(new PlatformClient(tokens), uri, context.out(), context.err(),
                context.clock(), context.sleeper(), EventStream.IDLE_LIMIT);
        context.onStop().accept(stream::stop);
        return stream.run();
    }

    /** The {@code names} query value: the names, each encoded, joined by commas; or {@code *}. */
    static String names(String filter) throws CliFailure {
        List<String> names = Arrays.stream(filter == null ? new String[0] : filter.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
        if (names.isEmpty()) {
            throw new CliFailure("--filter names no event. Give event names, comma-separated, or * for every event.",
                    CliFailure.USAGE);
        }
        for (String name : names) {
            if (name.contains("*") && !name.equals("*")) {
                throw new CliFailure("--filter takes exact event names, or * alone for every event. '" + name
                        + "' is neither: the stream matches no patterns.", CliFailure.USAGE);
            }
        }
        if (names.contains("*")) {
            return "*";
        }
        return names.stream().map(n -> URLEncoder.encode(n, StandardCharsets.UTF_8)).collect(Collectors.joining(","));
    }
}
