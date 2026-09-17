package eu.wohlben.qits.cli.access.complete;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.projects.ProjectsApi;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The projects this session can see. The value is the slug, which every command accepts. */
@ApplicationScoped
public class ProjectSource extends PlatformSource {

    @Override
    public List<Choice> choices(Map<String, String> chosen) {
        ProjectsApi api = projects();
        List<Choice> choices = new ArrayList<>();
        for (JsonNode project : ProjectsApi.entries(read(api::projects), "project")) {
            String slug = ProjectsApi.projectLabel(project);
            choices.add(new Choice(slug, columns(slug, ProjectsApi.text(project, "name"))));
        }
        return choices;
    }
}
