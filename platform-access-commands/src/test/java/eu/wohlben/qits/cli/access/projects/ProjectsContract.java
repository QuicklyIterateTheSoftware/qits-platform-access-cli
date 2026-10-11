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
 * family by qualified id (epic qits-965): the CLI never resolves one to a UUID. The rows are
 * {@code /work} doors and five more: the release request's commits, which {@code qits artifacts
 * publish changelog} reads in every release step (qits-893), a commit's changes and one file's
 * diff, the project's release requests, which {@code qits release-request list} reads when no
 * repository is named, the repository's requests of every state, where every command that takes
 * a request finds it by its logical id (qits-1158), and an automation run, which {@code qits
 * release-request regenerate} asks for (qits-1182). {@code qits projects}, the other {@code
 * repositories} reads and the other {@code release-request} calls are not part of this pact yet,
 * and neither is the OpenAPI document the payload commands read their schema from. Neither is
 * {@code list --state}: the provider records the project list without a query only. Doors the
 * provider records in no state (branches, commits, commit subjects, the refinement designs) have
 * no row: a row needs a recorded state.
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
    static final String RELEASED_REQUEST = "a released release request";
    static final String REQUESTS_OF_EVERY_STATE = "a repository with a release not merged to main";
    static final String BUG_TICKET_IN_DETAIL = "a bug ticket in detail";
    static final String EPIC_WITH_A_SKETCH = "an epic with a sketch to inline";
    static final String CAMPAIGN_IN_DETAIL = "a campaign in detail";
    static final String CAMPAIGN_TO_EDIT = "a campaign with members to edit";
    static final String AWAITING_REQUEST = "a release request awaiting approval";
    static final String PENDING_REQUESTS = "a project with pending release requests";
    static final String HELD_BY_A_FAILED_AUTOMATION = "a release request held by a failed automation";

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

    /** The changelog titles every ticket a subject names, and reads nothing else of it. */
    private static final Call TITLE = (api, params, recorded) -> {
        JsonNode item = api.work(params.get("qualifiedId"));
        assertThat(ProjectsApi.text(item, "title")).isNotBlank();
    };

    /** The changelog lists the commits that are not the request's own folds, by short hash, subject and author. */
    private static final Call RELEASE_COMMITS = (api, params, recorded) -> {
        JsonNode answer = api.releaseRequestCommits(params.get("repositoryId"), params.get("requestId"));
        JsonNode commits = answer.path("commits");
        assertThat(commits.size())
                .isEqualTo(GoldenMasters.json(recorded.state(), recorded.operationId()).path("commits").size());
        assertThat(commits).allSatisfy(c -> {
            assertThat(c.path("fold").isBoolean()).isTrue();
            assertThat(ProjectsApi.text(c, "shortHash")).isNotBlank();
            assertThat(ProjectsApi.text(c, "message")).isNotBlank();
            assertThat(ProjectsApi.text(c, "author")).isNotBlank();
        });
        // Not "some are not folds": the mock answers every element in the first one's shape, and
        // the recording's first commit is the fold.
    };

    /**
     * Every command that takes a release request finds it among the repository's requests of every
     * state, by its logical id (qits-1158) or its uuid's start, and sends the uuid it found.
     */
    private static final Call FIND_REQUEST = (api, params, recorded) -> {
        JsonNode requests = api.releaseRequests(params.get("repositoryId"), "all").path("requests");
        assertThat(requests).isNotEmpty().allSatisfy(r -> {
            assertThat(ProjectsApi.text(r, "id")).isNotBlank();
            assertThat(ProjectsApi.text(r, "qualifiedId")).isNotBlank();
            assertThat(ProjectsApi.text(r, "state")).isNotBlank();
        });
        JsonNode first = requests.get(0);
        JsonNode found = ReleaseRequestRefs.find(requests, ProjectsApi.text(first, "qualifiedId").toUpperCase(),
                ProjectsApi.text(first, "repoName"), "");
        assertThat(ProjectsApi.text(found, "id")).isEqualTo(ProjectsApi.text(first, "id"));
    };

    /** The literal segment after {@code marker} in the recorded path: a page slug or a commit hash. */
    private static String segmentAfter(Operation recorded, String marker) {
        String rest = recorded.path().substring(recorded.path().indexOf(marker) + marker.length());
        int end = rest.indexOf('/');
        return end < 0 ? rest : rest.substring(0, end);
    }

    private static final Call BLOCK = (api, params, recorded) -> {
        JsonNode block = api.setWorkBlocked(params.get("qualifiedId"), recorded.exampleBody()).path("block");
        assertThat(block.path("blocked").asBoolean()).isTrue();
        assertThat(ProjectsApi.text(block, "blockSource")).isNotBlank();
        assertThat(ProjectsApi.text(block, "blockReason")).isEqualTo(recorded.exampleBody().path("reason").asText());
    };

    private static final Call DELETE = (api, params, recorded) ->
            assertThat(api.deleteWork(params.get("qualifiedId")).path("success").asBoolean()).isTrue();

    /** A page as `dossier get`, `put` and `move` print it. */
    private static void page(JsonNode page) {
        assertThat(ProjectsApi.text(page, "slug")).isNotBlank();
        assertThat(ProjectsApi.text(page, "title")).isNotBlank();
        assertThat(page.path("version").isNumber()).isTrue();
        assertThat(page.path("position").isNumber()).isTrue();
    }

    private static final Call DOSSIER = (api, params, recorded) -> {
        JsonNode pages = api.workDossier(params.get("qualifiedId")).path("pages");
        assertThat(pages.size())
                .isEqualTo(GoldenMasters.json(recorded.state(), recorded.operationId()).path("pages").size());
        pages.forEach(ProjectsContract::page);
    };

    private static final Call DOSSIER_PAGE = (api, params, recorded) -> {
        JsonNode page = api.workDossierPage(params.get("qualifiedId"), segmentAfter(recorded, "/dossier/"));
        page(page);
        assertThat(ProjectsApi.text(page, "body")).isNotBlank();
    };

    private static final Call DOSSIER_CREATE = (api, params, recorded) ->
            page(api.createWorkDossierPage(params.get("qualifiedId"), recorded.exampleBody()));

    private static final Call DOSSIER_PUT = (api, params, recorded) ->
            page(api.putWorkDossierPage(params.get("qualifiedId"), segmentAfter(recorded, "/dossier/"),
                    recorded.exampleBody()));

    private static final Call DOSSIER_MOVE = (api, params, recorded) ->
            page(api.moveWorkDossierPage(params.get("qualifiedId"), segmentAfter(recorded, "/dossier/"),
                    recorded.exampleBody()));

    private static final Call DOSSIER_DELETE = (api, params, recorded) ->
            assertThat(api.deleteWorkDossierPage(params.get("qualifiedId"), segmentAfter(recorded, "/dossier/"))
                    .path("success").asBoolean()).isTrue();

    private static final Call INLINE = (api, params, recorded) ->
            assertThat(ProjectsApi.text(api.inlineWorkDossierAsset(params.get("qualifiedId"), recorded.exampleBody()),
                    "markdown")).isNotBlank();

    /** A member row as `members` prints it. */
    private static void member(JsonNode m) {
        assertThat(ProjectsApi.text(m, "membershipId")).isNotBlank();
        assertThat(m.path("position").isNumber()).isTrue();
        assertThat(ProjectsApi.text(m.path("entity"), "qualifiedId")).isNotBlank();
        assertThat(ProjectsApi.text(m.path("entity"), "status")).isNotBlank();
        assertThat(m.path("groups").isArray()).isTrue();
    }

    private static final Call MEMBERS = (api, params, recorded) -> {
        JsonNode members = api.workMembers(params.get("qualifiedId")).path("members");
        assertThat(members).isNotEmpty();
        members.forEach(ProjectsContract::member);
    };

    private static final Call MEMBER_ADD = (api, params, recorded) ->
            member(api.addWorkMember(params.get("qualifiedId"), recorded.exampleBody()).path("member"));

    private static final Call MEMBER_MOVE = (api, params, recorded) ->
            api.moveWorkMember(params.get("qualifiedId"), params.get("lastMembershipId"), recorded.exampleBody())
                    .path("members").forEach(ProjectsContract::member);

    private static final Call MEMBER_REMOVE = (api, params, recorded) ->
            api.removeWorkMember(params.get("qualifiedId"), params.get("lastMembershipId"))
                    .path("members").forEach(ProjectsContract::member);

    private static final Call MEMBER_CONDITION = (api, params, recorded) -> {
        JsonNode m = api.setWorkMemberCondition(params.get("qualifiedId"), params.get("firstMembershipId"),
                recorded.exampleBody()).path("member");
        member(m);
        assertThat(m.path("groups")).isNotEmpty();
    };

    private static final Call PROGRESS = (api, params, recorded) -> {
        JsonNode progress = api.workProgress(params.get("qualifiedId")).path("progress");
        assertThat(ProjectsApi.text(progress.path("campaign"), "qualifiedId")).isEqualTo(params.get("qualifiedId"));
        assertThat(progress.path("evaluator").path("connected").isBoolean()).isTrue();
        assertThat(progress.path("members")).allSatisfy(m -> {
            assertThat(ProjectsApi.text(m, "state")).isNotBlank();
            assertThat(m.path("waitsFor").isArray()).isTrue();
            assertThat(ProjectsApi.text(m.path("entity"), "qualifiedId")).isNotBlank();
        });
    };

    /** `design` asks the item for its refinement first; an item with none answers null. */
    private static final Call NO_REFINEMENT = (api, params, recorded) ->
            assertThat(api.workRefinement(params.get("qualifiedId")).path("refinement").isNull()).isTrue();

    private static final Call COMMIT_CHANGES = (api, params, recorded) -> {
        JsonNode files = api.commitChanges(params.get("repositoryId"), segmentAfter(recorded, "/commits/"), null)
                .path("files");
        assertThat(files).isNotEmpty().allSatisfy(f -> {
            assertThat(ProjectsApi.text(f, "path")).isNotBlank();
            assertThat(ProjectsApi.text(f, "changeType")).isNotBlank();
        });
    };

    private static final Call COMMIT_DIFF = (api, params, recorded) -> {
        String path = recorded.path().substring(recorded.path().indexOf("?path=") + "?path=".length());
        JsonNode diff = api.commitDiff(params.get("repositoryId"), segmentAfter(recorded, "/commits/"), path, null);
        assertThat(ProjectsApi.text(diff, "path")).isEqualTo(path);
        assertThat(ProjectsApi.text(diff, "diff")).isNotBlank();
    };

    /** {@code qits release-request list} without a repository: the table reads these five of each request. */
    private static final Call PROJECT_RELEASE_REQUESTS = (api, params, recorded) -> {
        JsonNode requests = api.projectReleaseRequests(params.get("projectId"), null).path("requests");
        assertThat(requests.size())
                .isEqualTo(GoldenMasters.json(recorded.state(), recorded.operationId()).path("requests").size());
        assertThat(requests).allSatisfy(r -> {
            assertThat(ProjectsApi.text(r, "id")).isNotBlank();
            assertThat(ProjectsApi.text(r, "repoName")).isNotBlank();
            assertThat(ProjectsApi.text(r, "state")).isNotBlank();
            assertThat(ProjectsApi.text(r, "summary")).isNotBlank();
            assertThat(ProjectsApi.text(r, "updatedAt")).isNotBlank();
        });
    };

    /** {@code qits release-request regenerate}: the kind is the recording's path segment; it prints the run's id. */
    private static final Call REGENERATE = (api, params, recorded) -> {
        JsonNode run = api.rerunReleaseRequestAutomation(params.get("repositoryId"), params.get("requestId"),
                segmentAfter(recorded, "/automations/"));
        assertThat(ProjectsApi.text(run, "id")).isNotBlank();
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
                    ProjectsApi.MERGE_PATCH, EDIT_COMMENT),
            row("qits artifacts publish changelog", RELEASED_REQUEST, "listReleaseRequestCommits", RELEASE_COMMITS),
            row("qits artifacts publish changelog", TICKET_WITH_A_COMMENT, "getWork", TITLE),
            row("qits work block", REPORTED_TICKET, "setWorkBlocked", BLOCK),
            row("qits work delete", REPORTED_TICKET, "deleteWork", DELETE),
            row("qits work progress", CAMPAIGN_IN_DETAIL, "getWorkProgress", PROGRESS),
            row("qits work dossier list", BUG_TICKET_IN_DETAIL, "listWorkDossier", DOSSIER),
            row("qits work dossier get", EPIC_IN_DETAIL, "getWorkDossierPage", DOSSIER_PAGE),
            row("qits work dossier put", BUG_TICKET_IN_DETAIL, "createWorkDossierPage", DOSSIER_CREATE),
            row("qits work dossier put", BUG_TICKET_IN_DETAIL, "putWorkDossierPage", DOSSIER_PUT),
            row("qits work dossier move", BUG_TICKET_IN_DETAIL, "moveWorkDossierPage", DOSSIER_MOVE),
            row("qits work dossier delete", BUG_TICKET_IN_DETAIL, "deleteWorkDossierPage", DOSSIER_DELETE),
            row("qits work dossier inline", EPIC_WITH_A_SKETCH, "inlineWorkDossierAsset", INLINE),
            row("qits work members list", CAMPAIGN_TO_EDIT, "listWorkMembers", MEMBERS),
            row("qits work members add", CAMPAIGN_TO_EDIT, "addWorkMember", MEMBER_ADD),
            row("qits work members move", CAMPAIGN_TO_EDIT, "moveWorkMember", MEMBER_MOVE),
            row("qits work members remove", CAMPAIGN_TO_EDIT, "removeWorkMember", MEMBER_REMOVE),
            row("qits work members condition", CAMPAIGN_TO_EDIT, "setWorkMemberCondition", MEMBER_CONDITION),
            row("qits work design list", REPORTED_TICKET, "getWorkRefinement", NO_REFINEMENT),
            row("qits repositories commit-changes", AWAITING_REQUEST, "listCommitChanges", COMMIT_CHANGES),
            row("qits repositories commit-diff", AWAITING_REQUEST, "getCommitFileDiff", COMMIT_DIFF),
            row("qits release-request list", PENDING_REQUESTS, "listProjectReleaseRequests",
                    PROJECT_RELEASE_REQUESTS),
            row("qits release-request join", REQUESTS_OF_EVERY_STATE, "listRepositoryReleaseRequests", FIND_REQUEST),
            row("qits release-request regenerate", HELD_BY_A_FAILED_AUTOMATION, "rerunReleaseRequestAutomation",
                    REGENERATE));

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
