package eu.wohlben.qits.cli.access.observe;

import eu.wohlben.qits.cli.access.platform.AccessTokens;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.PlatformUrls;
import picocli.CommandLine;

import java.net.URI;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@CommandLine.Command(name = "observe", mixinStandardHelpOptions = true,
        description = {
                "Print what qits-observability takes in (logs, spans with their events, metrics) as it arrives. "
                        + "The service applies the filters and sends only the records that match.",
                "Each --filter is one group of conditions, separated by spaces, that must all hold. A record that fits "
                        + "any group is printed. Live only: records that arrive while it reconnects are missed. "
                        + "Notes go to stderr. Stops on SIGINT or SIGTERM.",
                "",
                "Conditions: F=V equal and F^=V starts with (both match case: status=ERROR, not status=error), "
                        + "F~V contains (any case), F? present, !F absent, level>=V severity at or above V "
                        + "(TRACE, DEBUG, INFO, WARN, ERROR, FATAL or 1-24).",
                "Fields: kind service trace span level body name status event attr.<key> resource.<key>. "
                        + "attr.<key> is the record's own attribute: a span's exception is on its event, so "
                        + "attr.exception.type? matches logs, and event=exception matches spans. "
                        + "Quote a value that holds spaces: body~\"connection refused\".",
                "",
                "Example: qits observe --filter 'kind=log level>=ERROR' --filter 'kind=span event=exception'",
                ""})
public class ObserveCommand extends PlatformCommand {

    @CommandLine.Option(names = "--filter", required = true, paramLabel = "<conditions>",
            description = "One group of conditions, for example 'kind=log level>=ERROR'. Give it again for another "
                    + "group. '*' streams every record.")
    List<String> filters;

    @CommandLine.Option(names = "--observability-url", paramLabel = "<url>",
            description = "The observability service's base URL, without /observability. Default: "
                    + "QITS_OBSERVABILITY_URL, else the session's idp address with `idp` swapped for `observability`.")
    String observabilityUrl;

    @CommandLine.Option(names = {"-o", "--output"}, paramLabel = "<text|json>", defaultValue = "text",
            description = "text: one line per record (the default). json: each record's frame as the service sends "
                    + "it, one per line.")
    String output;

    @Override
    protected int execute(CliContext context) throws CliFailure, InterruptedException {
        boolean json = switch (output == null ? "text" : output) {
            case "text" -> false;
            case "json" -> true;
            default -> throw new CliFailure("--output must be text or json, not '" + output + "'.", CliFailure.USAGE);
        };
        List<List<FilterGrammar.Condition>> groups = new ArrayList<>();
        for (String filter : filters) {
            groups.add(FilterGrammar.parse(filter));
        }
        String frame = FilterGrammar.subscribeFrame(groups);
        AccessTokens tokens = context.tokens();
        // Fails at once without a session, rather than in the reconnect loop.
        String idpUrl = tokens.session().idpUrl();
        URI uri = socketUri(PlatformUrls.observability(observabilityUrl, context.env(), idpUrl));
        ObserveStream stream = new ObserveStream(new PlatformClient(tokens), uri, frame, json, ZoneId.systemDefault(),
                context.out(), context.err(), context.clock(), context.sleeper(), ObserveStream.IDLE_LIMIT,
                ObserveStream.PING_EVERY);
        context.onStop().accept(stream::stop);
        return stream.run();
    }

    /** The socket's address: the base URL with {@code ws} for http and {@code wss} for https. */
    static URI socketUri(String base) throws CliFailure {
        String refused = "'" + base + "' is not a usable observability address. Give an http or https base URL.";
        int colon = base.indexOf("://");
        String scheme = colon < 0 ? "" : base.substring(0, colon).toLowerCase(Locale.ROOT);
        String socketScheme = switch (scheme) {
            case "https", "wss" -> "wss";
            case "http", "ws" -> "ws";
            default -> throw new CliFailure(refused, CliFailure.USAGE);
        };
        try {
            URI uri = URI.create(socketScheme + base.substring(colon) + "/observability/stream");
            if (uri.getHost() == null) {
                throw new CliFailure(refused, CliFailure.USAGE);
            }
            return uri;
        } catch (IllegalArgumentException notAUri) {
            throw new CliFailure(refused, CliFailure.USAGE);
        }
    }
}
