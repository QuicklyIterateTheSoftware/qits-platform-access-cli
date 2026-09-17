package eu.wohlben.qits.cli.access.complete;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.projects.ProjectsApi;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The release requests of the chosen repository that still take a branch.
 * <p>
 * RELEASED already means the tag is cut, so it takes no more branches either, even though it is not
 * yet FINALIZED. A request that has released, finalized, been withdrawn or gone obsolete is not
 * something to join or withdraw.
 */
@ApplicationScoped
public class ReleaseRequestSource extends PlatformSource {

    /** The states that take no more branches. */
    static final Set<String> CLOSED = Set.of("RELEASED", "FINALIZED", "WITHDRAWN", "OBSOLETE");

    @Override
    public Set<String> dependsOn() {
        return Set.of("project", "repository");
    }

    @Override
    public List<Choice> choices(Map<String, String> chosen) {
        ProjectsApi api = projects();
        JsonNode repository = repository(api, chosen.get("project"), chosen.get("repository"));
        List<Choice> choices = new ArrayList<>();
        read(() -> api.releaseRequests(ProjectsApi.text(repository, "id"), null)).path("requests").forEach(request -> {
            String state = ProjectsApi.text(request, "state").toUpperCase(Locale.ROOT);
            if (CLOSED.contains(state)) {
                return;
            }
            String id = ProjectsApi.text(request, "id");
            choices.add(new Choice(id, columns(shortId(id), state + "  " + ProjectsApi.text(request, "summary"))));
        });
        return choices;
    }
}
