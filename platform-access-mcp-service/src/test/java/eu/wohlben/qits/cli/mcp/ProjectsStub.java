package eu.wohlben.qits.cli.mcp;

import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The projects service, as a route in the application under test: {@code GET /projects/api/projects}
 * answers one project and writes down the Authorization header it was sent, so a test can see the
 * command called with the caller's bearer and nobody else's. Outside /mcp, so no bearer is asked of
 * it here.
 */
@ApplicationScoped
public class ProjectsStub {

    private final List<String> seen = new CopyOnWriteArrayList<>();

    /** Through a method, never the field: a test holds the client proxy, whose own field is empty. */
    List<String> seen() {
        return seen;
    }

    void route(@Observes Router router) {
        router.get("/projects/api/projects").handler(context -> {
            seen.add(String.valueOf(context.request().getHeader("Authorization")));
            context.response().putHeader("Content-Type", "application/json").end("""
                    {"entries":[{"project":{"id":"0a0b0c0d-0000-4000-8000-000000000001","name":"qits platform",
                      "slug":"qits","description":"d","dns":null}}]}
                    """);
        });
    }
}
