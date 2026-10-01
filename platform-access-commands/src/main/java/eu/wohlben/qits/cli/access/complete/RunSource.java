package eu.wohlben.qits.cli.access.complete;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.projects.ProjectsApi;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The most recent builds of the chosen repository.
 * <p>
 * The label carries what a person picks by — the branch, how it went and when — while the value is
 * the bare id the command takes. Typing in the list matches the label, so "failed" finds the red
 * ones.
 */
@ApplicationScoped
public class RunSource extends PlatformSource {

    /** A screenful and a scroll of them. Older runs are found by id. */
    static final int RECENT = 30;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    @Override
    public Set<String> dependsOn() {
        return Set.of("project", "repository");
    }

    @Override
    public List<Choice> choices(Map<String, String> chosen) {
        ProjectsApi api = projects();
        JsonNode repository = repository(api, chosen.get("project"), chosen.get("repository"));
        List<Choice> choices = new ArrayList<>();
        read(() -> ci().runs(ProjectsApi.text(repository, "id"), RECENT)).path("runs").forEach(run -> {
            String id = ProjectsApi.text(run, "id");
            choices.add(new Choice(id, columns(shortId(id), ProjectsApi.text(run, "branch") + "  "
                    + ProjectsApi.text(run, "status") + "  " + clock(ProjectsApi.text(run, "createdAt")))));
        });
        return choices;
    }

    /** The time of day, or nothing at all: a label is never the place to explain a date. */
    static String clock(String instant) {
        try {
            return CLOCK.format(Instant.parse(instant));
        } catch (RuntimeException notATime) {
            return "";
        }
    }
}
