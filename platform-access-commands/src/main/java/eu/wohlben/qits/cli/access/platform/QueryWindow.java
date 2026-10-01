package eu.wohlben.qits.cli.access.platform;

import picocli.CommandLine;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The window a query command reads: {@code --since}, {@code --until} and {@code --limit}, shared by
 * {@code qits events query} and {@code qits observe query} as a picocli mixin, so both read one
 * grammar and say the same thing about it.
 * <p>
 * A time is an ISO-8601 instant or a whole number of seconds, minutes, hours or days back from now.
 * Now is the context's clock, read once, so a test moves time instead of waiting and a window's two
 * ends are measured from the same instant. A query never reaches past now: an {@code --until} after
 * it is taken as now, because what the window asks for has to exist already.
 */
public final class QueryWindow {

    public static final int MAX_LIMIT = 1000;

    private static final Pattern BACK = Pattern.compile("(\\d{1,9})([smhd])");

    /** The window resolved against now: two instants, both inclusive, and how many records at most. */
    public record Resolved(Instant since, Instant until, int limit) {
    }

    @CommandLine.Option(names = "--since", paramLabel = "<time>", defaultValue = "1h",
            description = "Where the window starts, inclusive: an ISO-8601 instant (2026-10-01T18:00:00Z) or a "
                    + "time back from now, a whole number with s, m, h or d (90s, 15m, 2h, 7d). "
                    + "Default: ${DEFAULT-VALUE}.")
    String since;

    @CommandLine.Option(names = "--until", paramLabel = "<time>",
            description = "Where the window ends, inclusive, in the same two forms. A time after now is taken as "
                    + "now. Default: now.")
    String until;

    @CommandLine.Option(names = "--limit", paramLabel = "<n>", defaultValue = "100",
            description = "At most this many, 1 to 1000. When more match, the newest are kept. "
                    + "Default: ${DEFAULT-VALUE}.")
    int limit;

    /** The options as given, resolved against the clock's now. */
    public Resolved resolve(Clock clock) throws CliFailure {
        return resolve(since, until, limit, clock);
    }

    public static Resolved resolve(String since, String until, int limit, Clock clock) throws CliFailure {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new CliFailure("--limit must be between 1 and " + MAX_LIMIT + ", not " + limit + ".",
                    CliFailure.USAGE);
        }
        // Microseconds: the events log keeps no finer time, so a finer now only makes the window's
        // printed ends longer without making it any more exact.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant from = time("--since", since == null ? "1h" : since, now);
        Instant to = until == null || until.isBlank() ? now : time("--until", until, now);
        if (to.isAfter(now)) {
            to = now;
        }
        if (from.isAfter(to)) {
            throw new CliFailure("--since (" + from + ") is after --until (" + to + "): the window holds nothing. "
                    + "Give a --since before the --until.", CliFailure.USAGE);
        }
        return new Resolved(from, to, limit);
    }

    /** One {@code <time>}: an ISO-8601 instant, or {@code <n>s|m|h|d} back from {@code now}. */
    static Instant time(String option, String value, Instant now) throws CliFailure {
        String text = value.strip();
        Matcher back = BACK.matcher(text);
        try {
            if (back.matches()) {
                long amount = Long.parseLong(back.group(1));
                Duration duration = switch (back.group(2)) {
                    case "s" -> Duration.ofSeconds(amount);
                    case "m" -> Duration.ofMinutes(amount);
                    case "h" -> Duration.ofHours(amount);
                    default -> Duration.ofDays(amount);
                };
                return now.minus(duration);
            }
            return Instant.parse(text);
        } catch (DateTimeException | ArithmeticException notATime) {
            // A parse error, or a time so far back that it is outside what an instant can hold.
            throw unreadable(option, value);
        }
    }

    private static CliFailure unreadable(String option, String value) {
        return new CliFailure(option + ": '" + value + "' is neither an ISO-8601 instant (2026-10-01T18:00:00Z) "
                + "nor a time back from now (90s, 15m, 2h, 7d).", CliFailure.USAGE);
    }

    /** {@code text} or {@code json}; null is {@code text}. */
    public static boolean json(String output) throws CliFailure {
        return switch (output == null ? "text" : output) {
            case "text" -> false;
            case "json" -> true;
            default -> throw new CliFailure("--output must be text or json, not '" + output + "'.", CliFailure.USAGE);
        };
    }

    /** The first line of a text answer: the window's resolved ends, so a reader can repeat it exactly. */
    public static String windowLine(Resolved window) {
        return "window: " + window.since() + " .. " + window.until();
    }

    /** The last line of a text answer that was cut. */
    public static String truncatedLine(int shown) {
        return "… truncated: " + shown + " shown, more in the window";
    }
}
