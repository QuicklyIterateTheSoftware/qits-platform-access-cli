package eu.wohlben.qits.cli.access.platform;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The query window: its two forms of time, its defaults, the clamp at now and its refusals. */
class QueryWindowTest {

    private static final Instant NOW = Instant.parse("2026-10-01T18:00:00.123456789Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    /** Now as the window measures it: microseconds, the finest time the events log keeps. */
    private static final Instant NOW_MICROS = Instant.parse("2026-10-01T18:00:00.123456Z");

    @Test
    void theDefaultsAreTheLastHourUpToNowAndAHundred() throws Exception {
        QueryWindow.Resolved window = QueryWindow.resolve("1h", null, 100, CLOCK);
        assertThat(window.since()).isEqualTo(NOW_MICROS.minusSeconds(3600));
        assertThat(window.until()).isEqualTo(NOW_MICROS);
        assertThat(window.limit()).isEqualTo(100);
    }

    @Test
    void aDurationIsBackFromNowInEachUnit() throws Exception {
        assertThat(QueryWindow.time("--since", "90s", NOW_MICROS)).isEqualTo(NOW_MICROS.minusSeconds(90));
        assertThat(QueryWindow.time("--since", "15m", NOW_MICROS)).isEqualTo(NOW_MICROS.minusSeconds(900));
        assertThat(QueryWindow.time("--since", "2h", NOW_MICROS)).isEqualTo(NOW_MICROS.minusSeconds(7200));
        assertThat(QueryWindow.time("--since", "7d", NOW_MICROS)).isEqualTo(NOW_MICROS.minusSeconds(7 * 86400));
        assertThat(QueryWindow.time("--since", "0s", NOW_MICROS)).isEqualTo(NOW_MICROS);
    }

    @Test
    void anInstantIsTakenAsItStands() throws Exception {
        QueryWindow.Resolved window = QueryWindow.resolve("2026-10-01T17:00:00Z", "2026-10-01T17:30:00.5Z", 7, CLOCK);
        assertThat(window.since()).isEqualTo(Instant.parse("2026-10-01T17:00:00Z"));
        assertThat(window.until()).isEqualTo(Instant.parse("2026-10-01T17:30:00.5Z"));
        assertThat(QueryWindow.time("--until", "2026-10-01T19:00:00+02:00", NOW_MICROS))
                .isEqualTo(Instant.parse("2026-10-01T17:00:00Z"));
    }

    @Test
    void anUntilAfterNowIsNow() throws Exception {
        assertThat(QueryWindow.resolve("1h", "2026-10-02T00:00:00Z", 1, CLOCK).until()).isEqualTo(NOW_MICROS);
    }

    @Test
    void anUnreadableTimeNamesItsOption() {
        for (String bad : new String[] {"1w", "-5m", "15 m", "1.5h", "m", "yesterday", "2026-10-01", "", "9999999999d"}) {
            assertThatThrownBy(() -> QueryWindow.resolve(bad, null, 100, CLOCK)).as(bad)
                    .isInstanceOf(CliFailure.class)
                    .hasMessageStartingWith("--since: '" + bad + "' is neither an ISO-8601 instant")
                    .extracting(e -> ((CliFailure) e).exitCode()).isEqualTo(CliFailure.USAGE);
        }
        assertThatThrownBy(() -> QueryWindow.resolve("1h", "soon", 100, CLOCK))
                .hasMessageStartingWith("--until: 'soon' is neither");
    }

    @Test
    void sinceAfterUntilIsRefused() {
        assertThatThrownBy(() -> QueryWindow.resolve("10m", "1h", 100, CLOCK))
                .isInstanceOf(CliFailure.class)
                .hasMessageContaining("--since (2026-10-01T17:50:00.123456Z) is after --until (2026-10-01T17:00:00.123456Z)")
                .extracting(e -> ((CliFailure) e).exitCode()).isEqualTo(CliFailure.USAGE);
        // Clamped first: a since in the future is after an until that cannot pass now.
        assertThatThrownBy(() -> QueryWindow.resolve("2026-10-02T00:00:00Z", null, 100, CLOCK))
                .hasMessageContaining("is after --until");
    }

    @Test
    void theLimitIsOneToAThousand() throws Exception {
        assertThat(QueryWindow.resolve("1h", null, 1, CLOCK).limit()).isEqualTo(1);
        assertThat(QueryWindow.resolve("1h", null, 1000, CLOCK).limit()).isEqualTo(1000);
        for (int bad : new int[] {0, -1, 1001}) {
            assertThatThrownBy(() -> QueryWindow.resolve("1h", null, bad, CLOCK)).as("" + bad)
                    .hasMessage("--limit must be between 1 and 1000, not " + bad + ".")
                    .extracting(e -> ((CliFailure) e).exitCode()).isEqualTo(CliFailure.USAGE);
        }
    }

    @Test
    void theTextLinesSayTheWindowAndTheCut() throws Exception {
        QueryWindow.Resolved window = QueryWindow.resolve("2026-10-01T17:00:00Z", "2026-10-01T17:30:00Z", 5, CLOCK);
        assertThat(QueryWindow.windowLine(window)).isEqualTo("window: 2026-10-01T17:00:00Z .. 2026-10-01T17:30:00Z");
        assertThat(QueryWindow.truncatedLine(5)).isEqualTo("… truncated: 5 shown, more in the window");
    }
}
