package eu.wohlben.qits.cli.access.waits;

import eu.wohlben.qits.cli.access.platform.CliFailure;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code --timeout}: {@code 30m}, {@code 2h}, {@code 1h30m}, {@code 1d}, or ISO-8601 ({@code PT2H}).
 * The service takes ISO-8601 only, so this is what turns the short form into it.
 */
final class WaitTimeout {

    private static final Pattern SHORT = Pattern.compile("(\\d+)([dhms])");

    private WaitTimeout() {
    }

    /** The timeout as ISO-8601 ({@code PT2H}); null when none was given. */
    static String iso(String given) throws CliFailure {
        if (given == null || given.isBlank()) {
            return null;
        }
        String text = given.strip();
        Duration duration = text.toUpperCase(Locale.ROOT).startsWith("P") ? iso8601(text) : shortForm(text);
        if (duration.isNegative() || duration.isZero()) {
            throw new CliFailure("--timeout must be longer than nothing, not '" + text + "'.", CliFailure.USAGE);
        }
        return duration.toString();
    }

    private static Duration iso8601(String text) throws CliFailure {
        try {
            return Duration.parse(text);
        } catch (DateTimeParseException notOne) {
            throw unreadable(text);
        }
    }

    private static Duration shortForm(String text) throws CliFailure {
        Matcher m = SHORT.matcher(text.toLowerCase(Locale.ROOT));
        Duration total = Duration.ZERO;
        int at = 0;
        while (m.find() && m.start() == at) {
            long amount;
            try {
                amount = Long.parseLong(m.group(1));
                total = total.plus(switch (m.group(2)) {
                    case "d" -> Duration.ofDays(amount);
                    case "h" -> Duration.ofHours(amount);
                    case "m" -> Duration.ofMinutes(amount);
                    default -> Duration.ofSeconds(amount);
                });
            } catch (NumberFormatException | ArithmeticException tooLarge) {
                throw unreadable(text);
            }
            at = m.end();
        }
        if (at == 0 || at != text.length()) {
            throw unreadable(text);
        }
        return total;
    }

    private static CliFailure unreadable(String text) {
        return new CliFailure("--timeout '" + text + "' is not a duration. Write 30m, 2h, 1h30m, 1d or ISO-8601 (PT2H).",
                CliFailure.USAGE);
    }
}
