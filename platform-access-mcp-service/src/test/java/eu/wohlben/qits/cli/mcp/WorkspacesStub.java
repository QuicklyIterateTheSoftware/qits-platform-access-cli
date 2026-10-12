package eu.wohlben.qits.cli.mcp;

import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The workspaces service's waits door, as a route in the application under test: a POST writes down
 * the agent its path named and answers an open wait, so a test can see which agent a wait went to.
 */
@ApplicationScoped
public class WorkspacesStub {

    private final List<String> agents = new CopyOnWriteArrayList<>();

    /** Through a method, never the field: a test holds the client proxy, whose own field is empty. */
    List<String> agents() {
        return agents;
    }

    void route(@Observes Router router) {
        router.post("/workspaces/api/agents/:agent/waits").handler(BodyHandler.create()).handler(context -> {
            String agent = context.pathParam("agent");
            agents.add(agent);
            context.response().setStatusCode(201).putHeader("Content-Type", "application/json").end("""
                    {"id":"7c1e2d3f-0000-4000-8000-000000000001","agentId":"%s","state":"OPEN",
                     "selection":{"event":"BuildFailed","when":[]},"createdAt":"2026-10-12T10:00:00Z"}
                    """.formatted(agent));
        });
    }
}
