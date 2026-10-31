package eu.wohlben.qits.cli.access.artifacts;

import eu.wohlben.qits.cli.access.contracts.CliRow;
import eu.wohlben.qits.cli.access.contracts.Reached;
import eu.wohlben.qits.cli.access.platform.PlatformClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>What {@code qits artifacts fetch deployed-pacts} asks qits-artifacts</b> (ticket qits-1765), rows
 * of {@code publish/ArtifactsContract}: one pact package's jar from the hosted maven repository.
 * Its other read, the list of packages, is qits-maintenance's ({@code MaintenanceContract}).
 */
public final class FetchRows {

    /** A jar at {@code eu.wohlben.qits:contract-maven:2100.1.0}, which the provider records the GET of. */
    static final String A_MAVEN_JAR = "a maven jar published at a version";

    public static final Class<?> CLIENT = PactJars.class;

    public static final String[] DOORS = {"download"};

    public static final List<CliRow> ROWS = List.of(
            CliRow.of("qits artifacts fetch deployed-pacts", A_MAVEN_JAR, "getMavenFile", "PactJars#download",
                            (url, recorded) -> {
                                Map<String, String> p = recorded.params();
                                String[] ga = p.get("name").split(":", 2);
                                Path target = Files.createTempFile("pact-jar-", ".jar");
                                target.toFile().deleteOnExit();
                                new PactJars(new PlatformClient(Reached.CREDENTIAL), url + "/artifacts/maven/maven")
                                        .download(ga[0], ga[1], p.get("version"), target);
                                // The recording keeps the status and the length, not the bytes: what the
                                // command makes of a jar is DeployedPactsCommandTest's.
                                assertThat(target).exists();
                            })
                    .request("GET /artifacts/maven/maven/{groupPath}/{artifact}/{version}/{artifact}-{version}.jar"));

    private FetchRows() {
    }
}
