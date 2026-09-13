package eu.wohlben.qits.cli.access.complete;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.projects.ProjectsApi;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The open tickets of the chosen project. The value is the id; the title is what a person reads. */
@ApplicationScoped
public class TicketSource extends PlatformSource {

    static final String OPEN = "OPEN";

    @Override
    public Set<String> dependsOn() {
        return Set.of("project");
    }

    @Override
    public List<Choice> choices(Map<String, String> chosen) {
        ProjectsApi api = projects();
        JsonNode project = read(() -> api.project(chosen.get("project").strip()));
        List<Choice> choices = new ArrayList<>();
        for (JsonNode ticket : ProjectsApi.entries(
                read(() -> api.tickets(ProjectsApi.text(project, "id"), OPEN)), "ticket")) {
            String id = ProjectsApi.text(ticket, "id");
            choices.add(new Choice(id, columns(shortId(id), ProjectsApi.text(ticket, "title"))));
        }
        return choices;
    }
}
