package eu.wohlben.qits.cli.access.complete;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.projects.ProjectsApi;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The repositories of the chosen project. The value is the name, which every command accepts. */
@ApplicationScoped
public class RepositorySource extends PlatformSource {

    @Override
    public Set<String> dependsOn() {
        return Set.of("project");
    }

    @Override
    public List<Choice> choices(Map<String, String> chosen) {
        ProjectsApi api = projects();
        JsonNode project = read(() -> api.project(chosen.get("project").strip()));
        List<Choice> choices = new ArrayList<>();
        for (JsonNode repository : ProjectsApi.entries(read(() -> api.repositories(ProjectsApi.text(project, "id"))),
                "repository")) {
            String name = ProjectsApi.text(repository, "name");
            choices.add(new Choice(name, columns(name, ProjectsApi.text(repository, "archetype"))));
        }
        return choices;
    }
}
