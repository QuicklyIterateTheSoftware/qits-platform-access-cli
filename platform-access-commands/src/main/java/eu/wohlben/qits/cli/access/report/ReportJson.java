package eu.wohlben.qits.cli.access.report;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * How payloads and highlights become JSON and are read back. One mapper, so a payload written here is
 * read back by the same rules. A field a later version added is ignored on the way in: a baseline
 * written by a newer CLI of the same kind version must still read.
 */
public final class ReportJson {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ReportJson() {
    }
}
