package eu.wohlben.qits.cli.access.changelog;

import eu.wohlben.qits.cli.access.contracts.CliRow;
import eu.wohlben.qits.cli.access.contracts.Reached;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.session.Credential;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>What {@code qits changelog bump-message} asks qits-artifacts</b> (ticket qits-1149), rows of
 * {@code publish/ArtifactsContract}: one published changelog, a file of a {@code @changelog} docs
 * bundle, read as text on a raw {@code HttpClient}. Its answer is Markdown, which a pact built from
 * the golden masters does not bind (it binds JSON paths): the row holds the request and the status.
 */
public final class BumpMessageRows {

    public static final Class<?> CLIENT = BumpMessageCommand.class;

    public static final String[] DOORS = {"changelog"};

    private static String changelog(String url, String repository, String version) throws Exception {
        Method changelog = BumpMessageCommand.class.getDeclaredMethod("changelog", String.class, Credential.class,
                String.class, String.class);
        changelog.setAccessible(true);
        try {
            return (String) changelog.invoke(null, url, Reached.CREDENTIAL, repository, version);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception thrown) {
                throw thrown;
            }
            throw (Error) e.getCause();
        }
    }

    public static final List<CliRow> ROWS = List.of(
            CliRow.of("qits changelog bump-message", "a repository with published changelogs", "getDocsFile",
                    "BumpMessageCommand#changelog", (url, recorded) -> assertThat(changelog(url,
                            recorded.params().get("repository"), recorded.params().get("version"))).isNotNull()),
            CliRow.of("qits changelog bump-message", "a repository with no changelog", "getDocsFile",
                            "BumpMessageCommand#changelog", (url, recorded) -> assertThatThrownBy(() -> changelog(url,
                                    recorded.params().get("repository"), recorded.params().get("version")))
                                    .isInstanceOf(CliFailure.class).hasMessageContaining("no changelog"))
                    .request("GET /artifacts/docs/docs/@changelog/{repository}/-/{version}/CHANGELOG.md"));

    private BumpMessageRows() {
    }
}
