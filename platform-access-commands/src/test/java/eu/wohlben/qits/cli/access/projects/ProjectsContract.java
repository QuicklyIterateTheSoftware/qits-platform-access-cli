package eu.wohlben.qits.cli.access.projects;

import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.contracts.GoldenMasters;
import eu.wohlben.qits.cli.access.contracts.GoldenMasters.Operation;
import eu.wohlben.qits.cli.access.contracts.GoldenMasters.Trigger;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>What the {@code qits work} commands ask qits-projects, and why</b> — the one table both {@code
 * ProjectsConsumerPactTest} (each row against a pact mock server) and {@code ProjectsPactFileTest}
 * (the committed {@code pacts/qits-platform-access-cli_qits-projects-service.json}) are built from,
 * so the file and the verified behaviour cannot drift apart.
 * <p>
 * <b>One row per (command, call).</b> Each row runs the {@link ProjectsApi} method the command
 * calls, with the state's frozen example as its argument and the recorded request as its body,
 * and asserts what the command then reads from the answer. Every call goes to the {@code /work}
 * family by qualified id (epic qits-965): the CLI never resolves one to a UUID. The rows are only
 * {@code /work} doors; {@code qits projects}, {@code repositories} and {@code release-request}
 * calls are not part of this pact yet, and neither is the OpenAPI document the comment doors read
 * their schema from.
 * <p>
 * <b>Each state is one the provider records</b> in its golden masters, picked for the call: a row
 * whose (state, operation) the index does not hold fails before anything runs, naming both.
 * {@code qits work status} and {@code transition} read the item before they write it, so those
 * commands appear twice, once per call, each in a state that records that call.
 */
final class ProjectsContract {

    static final String JSON_BODY = "application/json";

    /** Every query string the CLI could add is left out: the recordings hold none. */
    @FunctionalInterface
    interface Call {
        void run(ProjectsApi api, Map<String, String> params, Operation recorded) throws Exception;
    }

    /** One (trigger, call). {@code contentType} is what the CLI sends a body as; unused without one. */
    record Case(Trigger trigger, String state, String operationId, String contentType, Call call) {
        String description() {
            return GoldenMasters.description(operationId, trigger);
        }
    }

    // --- the states -----------------------------------------------------------------------------

    static final String WORK_IN_EVERY_STATUS = "a project with work in every status";
    static final String EPIC_IN_DETAIL = "an epic in detail";
    static final String NO_WORK = "a project with no work";
    static final String REGISTRY = "the archetype registry";
    static final String REPORTED_TICKET = "a reported ticket";
    static final String TICKET_WITH_A_COMMENT = "a ticket with a comment";
    static final String IMPLEMENTED_TICKET = "an implemented ticket";

    // --- the calls ------------------------------------------------------------------------------

    /** {@code qits work list}: the table reads every entity's qualified id, archetype and status. */
    private static final Call LIST = (api, params, recorded) -> {
        List<JsonNode> listed = WorkEntities.list(api.projectWork(params.get("projectId"), null, null, null));
        assertThat(listed).hasSize(GoldenMasters.json(recorded.state(), recorded.operationId()).path("entities").size());
        assertThat(listed).allSatisfy(e -> {
            assertThat(ProjectsApi.text(e, "qualifiedId")).isNotBlank();
            assertThat(ProjectsApi.text(e, "archetype")).isNotBlank();
            assertThat(ProjectsApi.text(e, "status")).isNotBlank();
        });
    };

    /** The item read by its qualified id: what details prints, update and status take its archetype from. */
    private static final Call GET = (api, params, recorded) -> {
        JsonNode item = api.work(params.get("qualifiedId"));
        assertThat(ProjectsApi.text(item, "qualifiedId")).isEqualTo(params.get("qualifiedId"));
        assertThat(ProjectsApi.text(item, "archetype")).isNotBlank();
        assertThat(ProjectsApi.text(item, "status")).isNotBlank();
    };

    /** {@code qits work transition} starts from the item as it stands. */
    private static final Call GET_FOR_TRANSITION = (api, params, recorded) -> {
        JsonNode state = WorkEntities.currentState(api.work(params.get("qualifiedId")));
        assertThat(ProjectsApi.text(state, "title")).isNotBlank();
        assertThat(ProjectsApi.text(state, "status")).isNotBlank();
    };

    private static final Call COMMENTS = (api, params, recorded) -> {
        List<JsonNode> thread = ProjectsApi.entries(api.workComments(params.get("qualifiedId")), "comment");
        assertThat(thread).hasSize(GoldenMasters.json(recorded.state(), recorded.operationId()).path("entries").size());
        assertThat(thread).allSatisfy(c -> assertThat(ProjectsApi.text(c, "author")).isNotBlank());
    };

    private static final Call CHILDREN = (api, params, recorded) -> {
        List<JsonNode> children = WorkEntities.children(api.workChildren(params.get("qualifiedId")));
        assertThat(children).isNotEmpty()
                .hasSize(GoldenMasters.json(recorded.state(), recorded.operationId()).path("children").size());
        assertThat(children).allSatisfy(c -> assertThat(ProjectsApi.text(c, "qualifiedId")).isNotBlank());
    };

    private static final Call CREATE = (api, params, recorded) -> {
        JsonNode created = api.createWork(recorded.exampleBody());
        assertThat(ProjectsApi.text(created, "qualifiedId")).isNotBlank();
        assertThat(ProjectsApi.text(created, "archetype")).isEqualTo(recorded.exampleBody().path("archetype").asText());
    };

    /** The schema printed when nothing is put in: WorkPayload reads its properties and required. */
    private static final Call CREATE_SCHEMA = (api, params, recorded) -> {
        JsonNode schema = api.archetypeSchema("TICKET", "create");
        assertThat(schema.path("properties").isObject()).isTrue();
        assertThat(schema.path("required").isArray()).isTrue();
    };

    private static final Call PATCH = (api, params, recorded) -> {
        JsonNode patched = api.patchWork(params.get("qualifiedId"), recorded.exampleBody());
        assertThat(ProjectsApi.text(patched, "qualifiedId")).isEqualTo(params.get("qualifiedId"));
        assertThat(ProjectsApi.text(patched, "title")).isEqualTo(recorded.exampleBody().path("title").asText());
    };

    /** Keyed by the qualified id as sent, and answered keyed the same: the table prints that entry. */
    private static final Call TRANSITION = (api, params, recorded) -> {
        String key = params.get("qualifiedId");
        assertThat(recorded.exampleBody().has(key)).as("the recorded request is keyed by the qualified id").isTrue();
        JsonNode answer = api.transitionWork(recorded.exampleBody());
        assertThat(ProjectsApi.text(answer.path(key), "qualifiedId")).isEqualTo(key);
        assertThat(ProjectsApi.text(answer.path(key), "archetype"))
                .isEqualTo(recorded.exampleBody().path(key).path("archetype").asText());
    };

    /** {@code qits work status} reads the item's archetype's lifecycle and the moves open from a status. */
    private static final Call REGISTRY_READ = (api, params, recorded) -> {
        JsonNode declared = WorkEntities.declared(api.archetypes(), "TICKET");
        assertThat(declared).isNotNull();
        assertThat(declared.path("lifecycle").isArray()).isTrue();
        assertThat(declared.path("lifecycle")).isNotEmpty();
        assertThat(declared.path("transitions").isObject()).isTrue();
    };

    private static final Call STATUS = (api, params, recorded) -> {
        JsonNode moved = api.setWorkStatus(params.get("qualifiedId"), recorded.exampleBody());
        assertThat(ProjectsApi.text(moved, "status")).isEqualTo(recorded.exampleBody().path("target").asText());
    };

    private static final Call COMMENT = (api, params, recorded) -> {
        JsonNode comment = api.addWorkComment(params.get("qualifiedId"), recorded.exampleBody()).path("comment");
        assertThat(ProjectsApi.text(comment, "id")).isNotBlank();
        assertThat(ProjectsApi.text(comment, "author")).isNotBlank();
        assertThat(ProjectsApi.text(comment, "createdAt")).isNotBlank();
    };

    private static final Call EDIT_COMMENT = (api, params, recorded) -> {
        JsonNode comment = api.editWorkComment(params.get("qualifiedId"), params.get("commentId"),
                recorded.exampleBody()).path("comment");
        assertThat(ProjectsApi.text(comment, "id")).isEqualTo(params.get("commentId"));
        assertThat(ProjectsApi.text(comment, "updatedAt")).isNotBlank();
    };

    static final List<Case> CASES = List.of(
            row("qits work list", WORK_IN_EVERY_STATUS, "listProjectWork", LIST),
            row("qits work details", EPIC_IN_DETAIL, "getWork", GET),
            row("qits work details", EPIC_IN_DETAIL, "listWorkComments", COMMENTS),
            row("qits work details", EPIC_IN_DETAIL, "listWorkChildren", CHILDREN),
            row("qits work create", NO_WORK, "createWork", CREATE),
            row("qits work create", REGISTRY, "getWorkArchetypeSchema", CREATE_SCHEMA),
            new Case(Trigger.command("qits work update"), REPORTED_TICKET, "patchWork", ProjectsApi.MERGE_PATCH, PATCH),
            row("qits work update", TICKET_WITH_A_COMMENT, "getWork", GET),
            row("qits work transition", TICKET_WITH_A_COMMENT, "getWork", GET_FOR_TRANSITION),
            row("qits work transition", REPORTED_TICKET, "transitionWork", TRANSITION),
            row("qits work status", IMPLEMENTED_TICKET, "getWork", GET),
            row("qits work status", REGISTRY, "listWorkArchetypes", REGISTRY_READ),
            row("qits work status", REPORTED_TICKET, "setWorkStatus", STATUS),
            row("qits work comment create", TICKET_WITH_A_COMMENT, "addWorkComment", COMMENT),
            new Case(Trigger.command("qits work comment update"), TICKET_WITH_A_COMMENT, "editWorkComment",
                    ProjectsApi.MERGE_PATCH, EDIT_COMMENT));

    private static Case row(String command, String state, String operationId, Call call) {
        return new Case(Trigger.command(command), state, operationId, JSON_BODY, call);
    }

    private ProjectsContract() {
    }

    /** The whole contract as one V4 pact, interactions in table order (the file test sorts). */
    static V4Pact pact() {
        return pact(CASES);
    }

    /** A pact holding only {@code cases} — one mock server per row, see the pact test. */
    static V4Pact pact(List<Case> cases) {
        PactBuilder builder = new PactBuilder(GoldenMasters.CONSUMER, GoldenMasters.PROVIDER, PactSpecVersion.V4);
        for (Case c : cases) {
            GoldenMasters.interaction(builder, c.state(), c.operationId(), c.trigger(), c.contentType());
        }
        return builder.toPact();
    }
}
