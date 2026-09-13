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
 * The open release requests of the chosen repository.
 * <p>
 * Open means what {@code release-request list} means by it: every state but the two that are final.
 * A request that has shipped or been withdrawn is not something to join or withdraw.
 */
@ApplicationScoped
public class ReleaseRequestSource extends PlatformSource {

    /** The states a request never leaves. */
    static final Set<String> SETTLED = Set.of("RELEASED", "WITHDRAWN");

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
            if (SETTLED.contains(state)) {
                return;
            }
            String id = ProjectsApi.text(request, "id");
            choices.add(new Choice(id, columns(shortId(id), state + "  " + ProjectsApi.text(request, "summary"))));
        });
        return choices;
    }
}
