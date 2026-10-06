package eu.wohlben.qits.cli.access.report;

import java.util.List;

/** Surefire's {@code **}{@code /target/surefire-reports/TEST-*.xml}; its sources are under {@code src/test/java}. */
public final class SurefireXmlParser extends MavenTestReports {

    public SurefireXmlParser() {
        super("surefire", "surefire-reports", List.of("src/test/java"));
    }
}
