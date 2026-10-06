package eu.wohlben.qits.cli.access.report;

import java.util.List;

/**
 * Failsafe's {@code **}{@code /target/failsafe-reports/TEST-*.xml}. An integration test's source is
 * under {@code src/test/java}, or under {@code src/it/java} where a module keeps them apart.
 */
public final class FailsafeXmlParser extends MavenTestReports {

    public FailsafeXmlParser() {
        super("failsafe", "failsafe-reports", List.of("src/test/java", "src/it/java"));
    }
}
