package eu.wohlben.qits.cli.access.complete;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.projects.ProjectsApi;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The versions the chosen repository has released, newest first.
 * <p>
 * Read from its released release requests, which is where a version is stamped: a release request
 * that reached RELEASED carries the version it shipped and the summary it shipped under. There is
 * no other door that lists a repository's versions with the session's own identity.
 */
@ApplicationScoped
public class VersionSource extends PlatformSource {

    static final String RELEASED = "RELEASED";

    @Override
    public Set<String> dependsOn() {
        return Set.of("project", "repository");
    }

    @Override
    public List<Choice> choices(Map<String, String> chosen) {
        ProjectsApi api = projects();
        JsonNode repository = repository(api, chosen.get("project"), chosen.get("repository"));
        List<Choice> choices = new ArrayList<>();
        read(() -> api.releaseRequests(ProjectsApi.text(repository, "id"), RELEASED))
                .path("requests").forEach(request -> {
                    String version = ProjectsApi.text(request, "version");
                    if (!version.isEmpty()) {
                        choices.add(new Choice(version, columns(version, ProjectsApi.text(request, "summary"))));
                    }
                });
        return choices;
    }
}
