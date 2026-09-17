package eu.wohlben.qits.cli.access.complete;

import eu.wohlben.qits.cli.access.ci.CiApi;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.platform.PlatformUrls;
import eu.wohlben.qits.cli.access.projects.ProjectsApi;
import eu.wohlben.qits.cli.tui.api.CompletionSource;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What the platform-filled dropdowns share: the session, the two APIs, and one way of failing.
 * <p>
 * A source reads through the very session the commands read through — {@code qits login} on a
 * workstation, the workspace credential inside the platform — so a dropdown can never show more
 * than the command it is filling in would be allowed to see.
 * <p>
 * {@link CompletionSource#choices} throws nothing checked, so the CLI's own {@link CliFailure} is
 * carried across as its message alone. That message is written for a person and holds no token,
 * which is exactly what the screen puts on its one dim line when a source cannot answer.
 */
public abstract class PlatformSource implements CompletionSource {

    private CliContext context;

    /** A test gives its own world; the binary reads the real one. */
    public void useContext(CliContext context) {
        this.context = context;
    }

    protected CliContext context() {
        if (context == null) {
            context = CliContext.system();
        }
        return context;
    }

    protected ProjectsApi projects() {
        return read(() -> ProjectsApi.connect(context(), null));
    }

    protected CiApi ci() {
        return read(() -> new CiApi(new PlatformClient(context().credential()),
                PlatformUrls.ci(null, context().env(), context().idpUrl())));
    }

    /** The repository the two chosen values name, with the project already looked up. */
    protected JsonNode repository(ProjectsApi api, String project, String repository) {
        return read(() -> api.repository(api.project(project.strip()), repository.strip()));
    }

    protected interface Call<T> {

        T get() throws CliFailure, InterruptedException;
    }

    protected static <T> T read(Call<T> call) {
        try {
            return call.get();
        } catch (CliFailure failure) {
            throw new IllegalStateException(failure.getMessage(), failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        }
    }

    /** Two columns that line up in a list, without a table: the id first, then what it is. */
    protected static String columns(String first, String second) {
        StringBuilder label = new StringBuilder(first);
        while (label.length() < 10) {
            label.append(' ');
        }
        return label + " " + second;
    }

    /** The first eight characters of an id, which is what the CLI's own tables show. */
    protected static String shortId(String id) {
        return id.length() <= 8 ? id : id.substring(0, 8);
    }
}
