package eu.wohlben.qits.cli.access.events;

import eu.wohlben.qits.cli.access.checkout.ReleaseWatcher;
import eu.wohlben.qits.cli.access.checkout.ReleaseWatcherRows;
import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliRow;
import eu.wohlben.qits.cli.access.contracts.InPlatformCli;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>What the commands ask qits-events over REST, and why</b> (ticket qits-1149): {@code qits events
 * query}'s pages of the events log and {@code qits checkout-daemon}'s newest-release read ({@link
 * ReleaseWatcherRows}), written to {@code pacts/qits-platform-access-cli_qits-events-service.json}.
 * The live streams ({@code qits events}, the checkout's watch) are Server-Sent Events, no part of it.
 * <p>
 * {@code events query} pages back from {@code --until} with {@code since}, {@code order=desc}, a
 * {@code limit} and a {@code cursor}; qits-events records the log paged forwards only, so that row
 * is pending on its state.
 */
public final class EventsContract {

    /** Not recorded: listEvents with since, order=desc, limit and a cursor. */
    static final String A_WINDOW = "events in a window to page back through";

    /** One line per event, the frame the live stream sends, and the page's cursor to the next. */
    private static final CliRow.Call QUERY = (url, recorded) -> {
        InPlatformCli.Result result = InPlatformCli.run(Map.of("QITS_EVENTS_URL", url), "events", "query",
                "--since", recorded.params().get("since"), "--until", recorded.params().get("until"),
                "--limit", recorded.params().get("limit"));
        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(result.out().lines().count()).isGreaterThan(1);
    };

    static final List<CliRow> ROWS = List.of(
            CliRow.of("qits events query", A_WINDOW, "listEvents", "EventsQueryCommand#execute", QUERY)
                    .consumes("events[].id", "events[].name", "events[].occurredAt", "events[].payload",
                            "events[].description", "events[].parentId", "events[].environment", "nextCursor")
                    .request("GET /events/api/events?since={since}&order=desc&limit={limit}&cursor={cursor}"));

    public static final CliPact CONTRACT = CliPact.of("qits-events-service", "qits-events")
            .client(EventsQueryCommand.class, "execute")
            .client(ReleaseWatcher.class, "newest", "loop")
            .excluded("ReleaseWatcher#loop", "the watch's live stream, GET /events/api/stream: Server-Sent Events")
            .rows(ROWS)
            .rows(ReleaseWatcherRows.ROWS);

    private EventsContract() {
    }
}
