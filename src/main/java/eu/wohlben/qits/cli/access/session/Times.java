package eu.wohlben.qits.cli.access.session;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/** How a time is shown to a person: absolute, in the local zone, to the second. */
public final class Times {

    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private Times() {
    }

    /** For a sentence: {@code 2026-09-12 15:05:00 CEST}. */
    public static String local(Instant instant) {
        return LOCAL.format(instant.atZone(ZoneId.systemDefault()));
    }

    /** For the start of a log line: {@code 2026-09-12T15:05:00+02:00}. */
    public static String stamp(Instant instant) {
        return STAMP.format(instant.truncatedTo(ChronoUnit.SECONDS).atZone(ZoneId.systemDefault()));
    }
}
