package eu.wohlben.qits.cli.access.checkout;

import eu.wohlben.qits.cli.access.contracts.CliRow;
import eu.wohlben.qits.cli.access.contracts.Reached;
import eu.wohlben.qits.cli.access.daemon.Sleeper;
import eu.wohlben.qits.cli.access.platform.PlatformClient;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>{@code qits checkout-daemon}'s one REST call to qits-events</b> (ticket qits-1149): the newest
 * SCMRelease of the checkout's repository, read when the watch starts, on every reconnect and for
 * {@code --once}. A row of {@code events/EventsContract}; the live stream it follows between those
 * reads is Server-Sent Events, no part of the pact. The query is the one {@code
 * CheckoutDaemonCommand} builds: the name, the {@code attr} filters with lower-case keys, the newest
 * one.
 */
public final class ReleaseWatcherRows {

    static final String A_RELEASE = "a repository with a release";

    /** The release the watch holds the checkout at, read from the newest event's payload. */
    private static final CliRow.Call NEWEST = (url, recorded) -> {
        String repository = recorded.params().get("repositoryName");
        String project = recorded.params().get("projectId");
        URI latest = URI.create(url + "/events/api/events?name=" + ReleaseWatcher.EVENT + "&attr=repositoryname="
                + URLEncoder.encode(repository, StandardCharsets.UTF_8) + "&attr=projectid="
                + URLEncoder.encode(project, StandardCharsets.UTF_8) + "&order=desc&limit=1");
        List<Release> held = new ArrayList<>();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ReleaseWatcher watcher = new ReleaseWatcher(new PlatformClient(Reached.CREDENTIAL),
                URI.create(url + "/events/api/stream?names=" + ReleaseWatcher.EVENT), latest, repository, project,
                release -> held.add(release), new PrintStream(out, true, StandardCharsets.UTF_8),
                new Notes(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                        Clock.systemUTC()), Sleeper.real(), ReleaseWatcher.IDLE_LIMIT);
        assertThat(watcher.once()).isZero();
        assertThat(held).singleElement().satisfies(r -> assertThat(r.version()).isNotBlank());
    };

    public static final List<CliRow> ROWS = List.of(
            CliRow.of("qits checkout-daemon", A_RELEASE, "listEvents", "ReleaseWatcher#newest", NEWEST)
                    .consumes("events[].payload"));

    private ReleaseWatcherRows() {
    }
}
