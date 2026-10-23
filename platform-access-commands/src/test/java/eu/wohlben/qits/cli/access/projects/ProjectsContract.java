package eu.wohlben.qits.cli.access.projects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliRow;
import eu.wohlben.qits.cli.access.contracts.Reached;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.session.Credential;
import eu.wohlben.qits.pact.consumer.GoldenMasters.Operation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>What the commands ask qits-projects, and why</b> (epic qits-965, ticket qits-1149): the one
 * table {@code ProjectsConsumerPactTest} runs row by row against a pact mock server and writes to
 * {@code pacts/qits-platform-access-cli_qits-projects-service.json}, so the file and the verified
 * behaviour cannot drift apart.
 * <p>
 * <b>One row per (command, call).</b> Each row runs the {@link ProjectsApi} method the command
 * calls, with the state's frozen example as its argument and the recorded request as its body (pact
 * matches a body exactly, and the PATCH doors' {@code application/merge-patch+json}, which the
 * recordings leave unnamed, goes as a header of the row), and asserts what the command then reads. {@code consumes} lists exactly
 * those reads, as the table form prints them, and the pact binds nothing else: the {@code -o json}
 * form prints the answer as it came and reads no field of it. Every {@code /work} call goes by
 * qualified id (epic qits-965): the CLI never resolves one to a UUID.
 * <p>
 * <b>Every command that names a project or a repository looks it up first</b>, by id, slug or
 * name, so each of those commands has a row for the project list and one for the repository list
 * ({@link #PROJECT_SCOPED}, {@link #REPOSITORY_SCOPED}). The repository lookup is asserted on the
 * entries rather than through {@link ProjectsApi#repository}: the mock answers every entry in the
 * first one's shape, so a lookup by name would find the one name twice.
 * <p>
 * <b>Each state is one the provider records</b>, picked for the call. A command that reads an item
 * before it writes it appears once per call. {@code qits work details}' read of the items an item
 * closes ({@code ?supersededBy=}) binds only what its recording holds: none of those items is blocked.
 * {@code openApi}, the served OpenAPI document the comment and transition payload commands read
 * their schema from, is no operation of the provider: it is named as an exclusion.
 */
public final class ProjectsContract {

    // --- the states -----------------------------------------------------------------------------

    static final String WORK_IN_EVERY_STATUS = "a project with work in every status";
    static final String EPIC_IN_DETAIL = "an epic in detail";
    static final String NO_WORK = "a project with no work";
    static final String REGISTRY = "the archetype registry";
    static final String UPDATE_SCHEMA = "the archetype registry's update schema";
    static final String TRANSITION_SCHEMA = "the archetype registry's transition schema";
    static final String REPORTED_TICKET = "a reported ticket";
    static final String TICKET_WITH_A_COMMENT = "a ticket with a comment";
    static final String IMPLEMENTED_TICKET = "an implemented ticket";
    static final String RELEASED_REQUEST = "a released release request";
    static final String REQUESTS_OF_EVERY_STATE = "a repository with a release not merged to main";
    static final String RELEASED_REQUESTS = "a repository with released release requests";
    static final String BUG_TICKET_IN_DETAIL = "a bug ticket in detail";
    static final String EPIC_WITH_A_SKETCH = "an epic with a sketch to inline";
    static final String CAMPAIGN_IN_DETAIL = "a campaign in detail";
    static final String CAMPAIGN_TO_EDIT = "a campaign with members to edit";
    static final String AWAITING_REQUEST = "a release request awaiting approval";
    static final String PENDING_REQUESTS = "a project with pending release requests";
    static final String HELD_BY_A_FAILED_AUTOMATION = "a release request held by a failed automation";
    static final String TICKET_WITH_A_DESIGN = "a ticket with a design";
    static final String REFINED_TICKET = "a refined ticket";
    static final String READY_FOR_DEV_TICKET = "a ready for dev ticket";
    static final String A_PROJECT = "a project exists";
    static final String ONE_REPOSITORY = "a project with one repository";
    static final String REPOSITORIES_IN_COMPONENTS = "a project with repositories in components";
    static final String THREE_REPOSITORIES = "a project with 3 repositories";
    static final String TO_CREATE_A_REPOSITORY_IN = "a project to create a repository in";
    static final String A_BRANCH_TO_RELEASE = "a repository with a branch to release";
    static final String OPEN_TO_ANOTHER_BRANCH = "a release request open to another branch";
    static final String PROJECT_REQUESTS_OF_EVERY_STATE = "a project with release requests in every state";
    static final String AWAITING_WHILE_ITS_BUILD_RUNS = "a release request awaiting approval while its build runs";
    static final String HELD_BY_AN_UPSTREAM_RELEASE = "a release request held by an upstream release";
    /**
     * The project list filtered by {@code ?supersededBy=}, the item named by its qualified id; its
     * items are not blocked, so no blockSource.
     */
    static final String SUPERSEDED_ITEMS = "an item that other items name as their supersededBy";

    private static final CliPact BASE = CliPact.of("qits-projects-service", "qits-projects");

    // --- what the commands read -----------------------------------------------------------------

    /** Every lookup of a project by id, slug or name. */
    static final List<String> PROJECT = List.of("entries[].project.id", "entries[].project.slug",
            "entries[].project.name");

    /**
     * What {@code qits projects delivery} reads of the project it looked up (epic qits-1440). Pending
     * on the provider: qits-projects records {@code delivery} on its project reads from qits-1490 on,
     * and the golden masters pinned in the root pom (2026.1019.190811) predate it, so {@link #held}
     * binds none of these paths yet and the row binds the lookup alone. Once qits-maintenance bumps
     * the pin past qits-1490's release, the same row binds all five: write the pact again with
     * {@code -Dgolden.update=true} on that bump.
     */
    static final List<String> DELIVERY = List.of("entries[].project.delivery.enabled",
            "entries[].project.delivery.maxRunning", "entries[].project.delivery.startRefined",
            "entries[].project.delivery.approves", "entries[].project.delivery.interval");

    /** Every lookup of a repository by id or name. */
    static final List<String> REPOSITORY = List.of("entries[].repository.id", "entries[].repository.name");

    /** One work item as a row of a list ({@code WorkEntities.printRows}): each only where the service sends it. */
    static final List<String> ROW = List.of("qualifiedId", "id", "archetype", "status", "outcome", "blocked",
            "blockSource", "title", "updatedAt");

    /** One work item in full ({@code WorkEntities.printDetails}), and the project its closes are read in. */
    static final List<String> DETAILS = Stream.concat(ROW.stream(), Stream.of("blockReason", "blockedBy",
            "ticketType", "impetus", "assignee", "parent", "position", "dependsOn", "repositoryId", "supersededBy",
            "implementedAt", "createdBy", "createdAt", "description", "acceptanceCriteria", "projectId")).toList();

    /** What {@code qits work transition} carries over from the item ({@code WorkEntities.currentState}). */
    static final List<String> CARRIED = Stream.concat(WorkEntities.CARRIED.stream(), Stream.of("parent", "position"))
            .toList();

    /**
     * One release request as {@code ReleaseRequestCommand.printRequest} prints it. {@code
     * qualityGates[].checks[].runId} is not here: the recorder froze it under both {@code ids} and
     * {@code strings} (a whole uuid in some elements only), so qits-pact-consumer would match it as
     * a uuid; no row consumes it.
     */
    static final List<String> REQUEST = List.of("id", "qualifiedId", "repoName", "state", "supersededBy",
            "supersededByQualifiedId", "priority", "summary", "requester", "approvalState", "mergedSha", "version",
            "detail", "createdAt", "updatedAt", "qualityGates[].kind", "qualityGates[].position",
            "qualityGates[].state", "qualityGates[].detail", "qualityGates[].checks[].name",
            "qualityGates[].checks[].state", "qualityGates[].checks[].detail", "sources[].kind",
            "sources[].name", "sources[].ref", "sources[].implicit", "sources[].priority", "sources[].addedBy");

    /**
     * {@code qualityGates} itself, and each gate's {@code checks}, must not pin a length (ticket
     * qits-904's follow-up): qits-projects-service is about to grow an {@code upstream-in-flight}
     * gate (qits-913), which would change {@code qualityGates}' length on every state recorded here,
     * and provider verification of a pact pinned at today's length would then fail and deadlock that
     * service's release. {@link CliRow#anyLength} matches these by type with no length instead,
     * templated from every element the recording holds (see {@code GoldenInteraction#anyLength}'s
     * javadoc for the path spelling: the array's own path, with no trailing {@code []}).
     */
    static final List<String> REQUEST_OPEN_ENDED = List.of("qualityGates", "qualityGates[].checks");

    /** Every command that takes a release request finds it by its logical id or its uuid's start. */
    static final List<String> FIND_REQUEST = List.of("requests[].id", "requests[].qualifiedId", "requests[].state");

    // --- the calls ------------------------------------------------------------------------------

    /** A call through {@link ProjectsApi}, over the real {@link PlatformClient}. */
    @FunctionalInterface
    interface Call {
        void run(ProjectsApi api, Map<String, String> params, Operation recorded) throws Exception;
    }

    private static CliRow.Call api(Call call) {
        return (url, recorded) -> call.run(new ProjectsApi(new PlatformClient(Reached.CREDENTIAL), url),
                recorded.params(), recorded);
    }

    /**
     * The same call as the person the state names: approve and decline are a person's decisions,
     * and the provider records them with that person's CLI bearer ({@code authorization}), which
     * the row sends as its {@code Authorization} header.
     */
    private static CliRow.Call asThePerson(Call call) {
        return (url, recorded) -> {
            String bearer = recorded.params().get("authorization").substring("Bearer ".length());
            Credential credential = () -> {
                Reached.CREDENTIAL.bearer();
                return bearer;
            };
            call.run(new ProjectsApi(new PlatformClient(credential), url), recorded.params(), recorded);
        };
    }

    private static JsonNode body(Operation recorded) {
        return CliRow.exampleBody(recorded);
    }

    private static int recorded(Operation op, String array) {
        return BASE.masters().json(op.state(), op.operationId()).path(array).size();
    }

    /** The literal segment after {@code marker} in the recorded path: a page slug or a commit hash. */
    private static String segmentAfter(Operation recorded, String marker) {
        String path = recorded.examplePath();
        String rest = path.substring(path.indexOf(marker) + marker.length());
        int end = rest.indexOf('/');
        return end < 0 ? rest : rest.substring(0, end);
    }

    private static String query(Operation recorded, String name) {
        return recorded.example(recorded.query().get(name).getFirst());
    }

    /** {@code qits projects list} and the agents' work-links cache: slug, name and id of each project. */
    private static final Call PROJECTS = (api, params, recorded) -> {
        List<JsonNode> projects = ProjectsApi.entries(api.projects(), "project");
        assertThat(projects).hasSize(recorded(recorded, "entries"));
        assertThat(projects).allSatisfy(p -> {
            assertThat(ProjectsApi.text(p, "id")).isNotBlank();
            assertThat(ProjectsApi.text(p, "slug")).isNotBlank();
            assertThat(ProjectsApi.text(p, "name")).isNotBlank();
        });
    };

    /** The lookup every project-scoped command makes first: by id, as a person may type it. */
    private static final Call FIND_PROJECT = (api, params, recorded) ->
            assertThat(ProjectsApi.text(api.project(params.get("projectId")), "id")).isEqualTo(params.get("projectId"));

    private static final Call FIND_REPOSITORY = (api, params, recorded) -> {
        List<JsonNode> repositories = ProjectsApi.entries(api.repositories(params.get("projectId")), "repository");
        assertThat(repositories).hasSize(recorded(recorded, "entries")).allSatisfy(r -> {
            assertThat(ProjectsApi.text(r, "id")).isNotBlank();
            assertThat(ProjectsApi.text(r, "name")).isNotBlank();
        });
    };

    /** {@code qits repositories list}: name, archetype, component and id. */
    private static final Call REPOSITORIES = (api, params, recorded) -> {
        List<JsonNode> repositories = ProjectsApi.entries(api.repositories(params.get("projectId")), "repository");
        assertThat(repositories).hasSize(recorded(recorded, "entries")).allSatisfy(r -> {
            assertThat(ProjectsApi.text(r, "name")).isNotBlank();
            assertThat(ProjectsApi.text(r, "archetype")).isNotBlank();
            assertThat(ProjectsApi.text(r, "id")).isNotBlank();
        });
    };

    /** {@code qits tui}'s repository dropdown: each repository's name and archetype. */
    private static final Call REPOSITORY_CHOICES = (api, params, recorded) ->
            assertThat(ProjectsApi.entries(api.repositories(params.get("projectId")), "repository")).isNotEmpty()
                    .allSatisfy(r -> assertThat(ProjectsApi.text(r, "archetype")).isNotBlank());

    /** {@code qits repositories remote-login} without --repository signs in through the wrapper's repository. */
    private static final Call WRAPPER_REPOSITORY = (api, params, recorded) ->
            assertThat(ProjectsApi.text(api.repositories(params.get("projectId")).path("wrapper"), "repositoryId"))
                    .isNotBlank();

    /** After the sign-in, the backups as they now stand: each repository with a backup remote and its last backup. */
    private static final Call BACKUPS = (api, params, recorded) -> {
        List<JsonNode> repositories = ProjectsApi.entries(api.repositories(params.get("projectId")), "repository");
        assertThat(repositories).anySatisfy(r -> {
            assertThat(ProjectsApi.text(r, "backupUrl")).isNotBlank();
            assertThat(ProjectsApi.text(r.path("lastBackup"), "outcome")).isNotBlank();
        });
    };

    private static final Call BACKUP_SYNC = (api, params, recorded) ->
            assertThat(api.backupSyncProject(params.get("projectId")).path("scheduled").isNumber()).isTrue();

    private static final Call CREATE_REPOSITORY = (api, params, recorded) -> {
        JsonNode created = api.createRepository(params.get("projectId"), body(recorded).path("name").asText(),
                body(recorded).path("component").asText());
        assertThat(ProjectsApi.text(created.path("repository"), "name")).isEqualTo(body(recorded).path("name").asText());
        assertThat(ProjectsApi.text(created.path("repository"), "archetype")).isNotBlank();
        assertThat(ProjectsApi.text(created, "wrapperPath")).isNotBlank();
    };

    /** The request as {@code printRequest} prints it: its name, state and sources. */
    private static void request(JsonNode request) {
        assertThat(ReleaseRequestRefs.name(request)).isNotBlank();
        assertThat(ProjectsApi.text(request, "state")).isNotBlank();
        assertThat(request.path("sources")).isNotEmpty();
    }

    private static final Call CREATE_REQUEST = (api, params, recorded) -> request(api.createReleaseRequest(
            params.get("repositoryId"), body(recorded).path("branch").asText(), body(recorded).path("summary").asText(),
            body(recorded).path("priority").asText()).path("request"));

    private static final Call JOIN_REQUEST = (api, params, recorded) -> request(api.joinReleaseRequest(
            params.get("repositoryId"), params.get("requestId"), body(recorded).path("branch").asText(),
            body(recorded).path("priority").asText()).path("request"));

    private static final Call WITHDRAW = (api, params, recorded) -> request(api.withdrawReleaseRequest(
            params.get("repositoryId"), params.get("requestId"), body(recorded).path("reason").asText())
            .path("request"));

    /** {@code qits work list}: the table reads every entity's qualified id, archetype and status. */
    private static final Call LIST = (api, params, recorded) -> {
        List<JsonNode> listed = WorkEntities.list(api.projectWork(params.get("projectId"), null, null, null));
        assertThat(listed).hasSize(recorded(recorded, "entities"));
        assertThat(listed).allSatisfy(e -> {
            assertThat(ProjectsApi.text(e, "qualifiedId")).isNotBlank();
            assertThat(ProjectsApi.text(e, "archetype")).isNotBlank();
            assertThat(ProjectsApi.text(e, "status")).isNotBlank();
        });
    };

    /**
     * {@code qits work details}: the items naming this one as their supersededBy, from its project's
     * list filtered by the item's qualified id (the state's {@code entityId}), as the command sends it.
     * The command filters the answer again by the item's id or qualified id (for services older than
     * qits-248); the state names the item by its qualified id alone, and the answer names it by its
     * UUID, so the item's id is the one the answer's rows carry.
     */
    private static final Call CLOSES = (api, params, recorded) -> {
        String id = BASE.masters().json(recorded.state(), recorded.operationId()).path("entities").path(0)
                .path("supersededBy").asText();
        JsonNode entity = JsonNodeFactory.instance.objectNode().put("id", id)
                .put("qualifiedId", params.get("entityId")).put("projectId", params.get("projectId"));
        List<JsonNode> closes = WorkEntities.superseding(WorkEntities.list(api.projectWork(params.get("projectId"),
                null, null, null, null, params.get("entityId"))), entity);
        assertThat(closes).isNotEmpty().allSatisfy(e -> {
            assertThat(ProjectsApi.text(e, "qualifiedId")).isNotBlank();
            assertThat(ProjectsApi.text(e, "supersededBy")).isNotBlank();
        });
    };

    /** The item read by its qualified id: what details prints, update and status take its archetype from. */
    private static final Call GET = (api, params, recorded) -> {
        JsonNode item = api.work(params.get("qualifiedId"));
        assertThat(ProjectsApi.text(item, "qualifiedId")).isEqualTo(params.get("qualifiedId"));
        assertThat(ProjectsApi.text(item, "archetype")).isNotBlank();
        assertThat(ProjectsApi.text(item, "status")).isNotBlank();
    };

    /** {@code qits work update} with nothing piped in reads the item's archetype for its schema. */
    private static final Call GET_ARCHETYPE = (api, params, recorded) ->
            assertThat(ProjectsApi.text(api.work(params.get("qualifiedId")), "archetype")).isNotBlank();

    /** A work item named by its qualified id, as {@code qits workspaces} and {@code qits wait} key it: its id. */
    private static final Call WORK_ID = (api, params, recorded) -> {
        JsonNode item = api.work(params.get("qualifiedId"));
        assertThat(ProjectsApi.text(item, "id")).isNotBlank();
        assertThat(ProjectsApi.text(item, "qualifiedId")).isEqualTo(params.get("qualifiedId"));
    };

    /** The status line's state of an item: its status, and whether and why it is blocked. */
    private static final Call WORK_STATE = (api, params, recorded) ->
            assertThat(ProjectsApi.text(api.work(params.get("qualifiedId")), "status")).isNotBlank();

    /** {@code qits work transition} starts from the item as it stands. */
    private static final Call GET_FOR_TRANSITION = (api, params, recorded) -> {
        JsonNode state = WorkEntities.currentState(api.work(params.get("qualifiedId")));
        assertThat(ProjectsApi.text(state, "title")).isNotBlank();
        assertThat(ProjectsApi.text(state, "status")).isNotBlank();
    };

    private static final Call COMMENTS = (api, params, recorded) -> {
        List<JsonNode> thread = ProjectsApi.entries(api.workComments(params.get("qualifiedId")), "comment");
        assertThat(thread).hasSize(recorded(recorded, "entries"));
        assertThat(thread).allSatisfy(c -> assertThat(ProjectsApi.text(c, "author")).isNotBlank());
    };

    private static final Call CHILDREN = (api, params, recorded) -> {
        List<JsonNode> children = WorkEntities.children(api.workChildren(params.get("qualifiedId")));
        assertThat(children).isNotEmpty().hasSize(recorded(recorded, "children"));
        assertThat(children).allSatisfy(c -> assertThat(ProjectsApi.text(c, "qualifiedId")).isNotBlank());
    };

    private static final Call CREATE = (api, params, recorded) -> {
        JsonNode created = api.createWork(body(recorded));
        assertThat(ProjectsApi.text(created, "qualifiedId")).isNotBlank();
        assertThat(ProjectsApi.text(created, "archetype")).isEqualTo(body(recorded).path("archetype").asText());
    };

    /** The schema printed when nothing is put in: WorkPayload reads its properties and required. */
    private static Call schema(String door) {
        return (api, params, recorded) -> {
            JsonNode schema = api.archetypeSchema("TICKET", door);
            assertThat(schema.path("properties").isObject()).isTrue();
            assertThat(schema.path("required").isArray()).isTrue();
        };
    }

    private static final Call PATCH = (api, params, recorded) -> {
        JsonNode patched = api.patchWork(params.get("qualifiedId"), body(recorded));
        assertThat(ProjectsApi.text(patched, "qualifiedId")).isEqualTo(params.get("qualifiedId"));
        assertThat(ProjectsApi.text(patched, "title")).isEqualTo(body(recorded).path("title").asText());
    };

    /** Keyed by the qualified id as sent, and answered keyed the same: the table prints that entry. */
    private static final Call TRANSITION = (api, params, recorded) -> {
        String key = params.get("qualifiedId");
        assertThat(body(recorded).has(key)).as("the recorded request is keyed by the qualified id").isTrue();
        JsonNode answer = api.transitionWork(body(recorded));
        assertThat(ProjectsApi.text(answer.path(key), "qualifiedId")).isEqualTo(key);
        assertThat(ProjectsApi.text(answer.path(key), "archetype"))
                .isEqualTo(body(recorded).path(key).path("archetype").asText());
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
        JsonNode moved = api.setWorkStatus(params.get("qualifiedId"), body(recorded));
        assertThat(ProjectsApi.text(moved, "status")).isEqualTo(body(recorded).path("target").asText());
    };

    private static final Call COMMENT = (api, params, recorded) -> {
        JsonNode comment = api.addWorkComment(params.get("qualifiedId"), body(recorded)).path("comment");
        assertThat(ProjectsApi.text(comment, "id")).isNotBlank();
        assertThat(ProjectsApi.text(comment, "author")).isNotBlank();
        assertThat(ProjectsApi.text(comment, "createdAt")).isNotBlank();
    };

    private static final Call EDIT_COMMENT = (api, params, recorded) -> {
        JsonNode comment = api.editWorkComment(params.get("qualifiedId"), params.get("commentId"), body(recorded))
                .path("comment");
        assertThat(ProjectsApi.text(comment, "id")).isEqualTo(params.get("commentId"));
        assertThat(ProjectsApi.text(comment, "updatedAt")).isNotBlank();
    };

    /** The changelog titles every ticket a subject names, and reads nothing else of it. */
    private static final Call TITLE = (api, params, recorded) ->
            assertThat(ProjectsApi.text(api.work(params.get("qualifiedId")), "title")).isNotBlank();

    /** The changelog lists the commits that are not the request's own folds, by short hash, subject and author. */
    private static final Call RELEASE_COMMITS = (api, params, recorded) -> {
        JsonNode commits = api.releaseRequestCommits(params.get("repositoryId"), params.get("requestId")).path("commits");
        assertThat(commits.size()).isEqualTo(recorded(recorded, "commits"));
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
    private static final Call FIND = (api, params, recorded) -> {
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

    /**
     * {@code qits artifacts publish changelog} finds the earlier releases that published no changelog
     * among the repository's requests of every state (qits-1193): it reads each one's id, logical id
     * and the version it cut, and skips a request that cut none.
     */
    private static final Call EARLIER_RELEASES = (api, params, recorded) -> {
        JsonNode requests = api.releaseRequests(params.get("repositoryId"), "all").path("requests");
        assertThat(requests).isNotEmpty().allSatisfy(r -> {
            assertThat(ProjectsApi.text(r, "id")).isNotBlank();
            assertThat(ProjectsApi.text(r, "qualifiedId")).isNotBlank();
            assertThat(r.path("version").isTextual() || r.path("version").isNull()).isTrue();
        });
        assertThat(requests).anySatisfy(r -> assertThat(ProjectsApi.text(r, "version")).isNotBlank());
    };

    /** The repository's open requests, its default list: what {@code list --repository} and the tui show. */
    private static final Call OPEN_REQUESTS = (api, params, recorded) -> {
        JsonNode requests = api.releaseRequests(params.get("repositoryId"), null).path("requests");
        assertThat(requests.size()).isEqualTo(recorded(recorded, "requests"));
        assertThat(requests).allSatisfy(r -> {
            assertThat(ReleaseRequestRefs.label(r)).isNotBlank();
            assertThat(ProjectsApi.text(r, "state")).isNotBlank();
        });
    };

    /** {@code qits tui}'s version dropdown: the released requests' versions. */
    private static final Call RELEASED_VERSIONS = (api, params, recorded) ->
            assertThat(api.releaseRequests(params.get("repositoryId"), "RELEASED").path("requests")).isNotEmpty()
                    .allSatisfy(r -> assertThat(ProjectsApi.text(r, "version")).isNotBlank());

    private static final Call BLOCK = (api, params, recorded) -> {
        JsonNode block = api.setWorkBlocked(params.get("qualifiedId"), body(recorded)).path("block");
        assertThat(block.path("blocked").asBoolean()).isTrue();
        assertThat(ProjectsApi.text(block, "blockSource")).isNotBlank();
        assertThat(ProjectsApi.text(block, "blockReason")).isEqualTo(body(recorded).path("reason").asText());
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
        assertThat(pages.size()).isEqualTo(recorded(recorded, "pages"));
        pages.forEach(ProjectsContract::page);
    };

    private static final Call DOSSIER_PAGE = (api, params, recorded) -> {
        JsonNode page = api.workDossierPage(params.get("qualifiedId"), segmentAfter(recorded, "/dossier/"));
        page(page);
        assertThat(ProjectsApi.text(page, "body")).isNotBlank();
    };

    private static final Call DOSSIER_CREATE = (api, params, recorded) ->
            page(api.createWorkDossierPage(params.get("qualifiedId"), body(recorded)));

    private static final Call DOSSIER_PUT = (api, params, recorded) ->
            page(api.putWorkDossierPage(params.get("qualifiedId"), segmentAfter(recorded, "/dossier/"), body(recorded)));

    private static final Call DOSSIER_MOVE = (api, params, recorded) ->
            page(api.moveWorkDossierPage(params.get("qualifiedId"), segmentAfter(recorded, "/dossier/"), body(recorded)));

    private static final Call DOSSIER_DELETE = (api, params, recorded) ->
            assertThat(api.deleteWorkDossierPage(params.get("qualifiedId"), segmentAfter(recorded, "/dossier/"))
                    .path("success").asBoolean()).isTrue();

    private static final Call INLINE = (api, params, recorded) ->
            assertThat(ProjectsApi.text(api.inlineWorkDossierAsset(params.get("qualifiedId"), body(recorded)),
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
            member(api.addWorkMember(params.get("qualifiedId"), body(recorded)).path("member"));

    private static final Call MEMBER_MOVE = (api, params, recorded) ->
            api.moveWorkMember(params.get("qualifiedId"), params.get("lastMembershipId"), body(recorded))
                    .path("members").forEach(ProjectsContract::member);

    private static final Call MEMBER_REMOVE = (api, params, recorded) ->
            api.removeWorkMember(params.get("qualifiedId"), params.get("lastMembershipId"))
                    .path("members").forEach(ProjectsContract::member);

    private static final Call MEMBER_CONDITION = (api, params, recorded) -> {
        JsonNode m = api.setWorkMemberCondition(params.get("qualifiedId"), params.get("firstMembershipId"),
                body(recorded)).path("member");
        member(m);
        assertThat(m.path("groups")).isNotEmpty();
    };

    /**
     * {@code qits projects delivery}: the project by id, and its delivery settings where the
     * recording carries them. A recording without them is a service older than the loop, whose
     * settings the command prints as the defaults; see {@link #DELIVERY}.
     */
    private static final Call DELIVERY_READ = (api, params, recorded) -> {
        JsonNode project = api.project(params.get("projectId"));
        assertThat(ProjectsApi.text(project, "id")).isEqualTo(params.get("projectId"));
        JsonNode delivery = project.path("delivery");
        if (BASE.held(recorded.state(), recorded.operationId(), DELIVERY).isEmpty()) {
            return;
        }
        assertThat(delivery.path("enabled").isBoolean()).isTrue();
        assertThat(delivery.path("maxRunning").isInt()).isTrue();
        assertThat(delivery.path("startRefined").isBoolean()).isTrue();
        assertThat(delivery.path("approves").isArray()).isTrue();
        assertThat(ProjectsApi.text(delivery, "interval")).isNotBlank();
    };

    /** {@code qits work dispatch}: the recorded mode, no rule; the table prints the mode, phase, branch and assignee. */
    private static final Call DISPATCH = (api, params, recorded) -> {
        JsonNode dispatch = api.dispatchWork(params.get("qualifiedId"), body(recorded).path("mode").asText(),
                null).path("dispatch");
        assertThat(ProjectsApi.text(dispatch, "mode")).isEqualTo(body(recorded).path("mode").asText());
        assertThat(ProjectsApi.text(dispatch, "phase")).isNotBlank();
        assertThat(ProjectsApi.text(dispatch, "branch")).isNotBlank();
        assertThat(ProjectsApi.text(dispatch, "assignee")).isNotBlank();
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

    /** {@code qits work unblock}: the flag as the write left it. */
    private static final Call UNBLOCK = (api, params, recorded) -> {
        JsonNode block = api.setWorkBlocked(params.get("qualifiedId"), body(recorded)).path("block");
        assertThat(block.path("blocked").isBoolean()).isTrue();
        assertThat(block.path("blocked").asBoolean()).isFalse();
        assertThat(ProjectsApi.text(block, "archetype")).isNotBlank();
        assertThat(ProjectsApi.text(block, "status")).isNotBlank();
    };

    /** Every `design` command finds the item's open refinement by its id first. */
    private static final Call OPEN_REFINEMENT = (api, params, recorded) ->
            assertThat(WorkDesignCommand.refinement(api, params.get("qualifiedId"))).isNotBlank();

    /** A design as `design list` prints it: no document. */
    private static void design(JsonNode d) {
        assertThat(ProjectsApi.text(d, "id")).isNotBlank();
        assertThat(ProjectsApi.text(d, "title")).isNotBlank();
        assertThat(d.path("version").isNumber()).isTrue();
        assertThat(d.path("htmlBytes").isNumber()).isTrue();
        assertThat(d.path("inUse").isBoolean()).isTrue();
        assertThat(ProjectsApi.text(d, "updatedAt")).isNotBlank();
    }

    private static final Call DESIGNS = (api, params, recorded) -> {
        JsonNode designs = api.refinementDesigns(params.get("refinementId")).path("designs");
        assertThat(designs.size()).isEqualTo(recorded(recorded, "designs"));
        designs.forEach(ProjectsContract::design);
    };

    /** `design get` prints the whole document, which a rewrite sends back with this version. */
    private static final Call DESIGN_GET = (api, params, recorded) -> {
        JsonNode d = api.refinementDesign(params.get("refinementId"), params.get("designId"));
        design(d);
        assertThat(ProjectsApi.text(d, "html")).isNotBlank();
    };

    private static final Call DESIGN_ADD = (api, params, recorded) -> {
        JsonNode d = api.addRefinementDesign(params.get("refinementId"), body(recorded));
        design(d);
        assertThat(ProjectsApi.text(d, "title")).isEqualTo(body(recorded).path("title").asText());
    };

    private static final Call DESIGN_UPDATE = (api, params, recorded) -> {
        JsonNode d = api.updateRefinementDesign(params.get("refinementId"), params.get("designId"), body(recorded));
        design(d);
        assertThat(ProjectsApi.text(d, "title")).isEqualTo(body(recorded).path("title").asText());
    };

    /** A refinement room as `refinement open` and `show` print it: its id, its item, its label and its status. */
    private static void room(JsonNode room) {
        assertThat(ProjectsApi.text(room, "id")).isNotBlank();
        assertThat(ProjectsApi.text(room, "entityId")).isNotBlank();
        assertThat(ProjectsApi.text(room, "label")).isNotBlank();
        assertThat(ProjectsApi.text(room, "runtimeStatus")).isNotBlank();
    }

    /** `refinement open` opens the room first, and brings up the room whose id that answers. */
    private static final Call OPEN_ROOM = (api, params, recorded) -> {
        JsonNode room = api.openWorkRefinement(params.get("qualifiedId")).path("refinement");
        assertThat(ProjectsApi.text(room, "id")).isNotBlank();
    };

    /** An item that cannot be refined now: the client fails with the status, and nothing is brought up. */
    private static final Call OPEN_REFUSED = (api, params, recorded) ->
            assertThatThrownBy(() -> api.openWorkRefinement(params.get("qualifiedId")))
                    .isInstanceOfSatisfying(CliFailure.class, f -> {
                        assertThat(f.status()).isEqualTo(409);
                        assertThat(f.getMessage()).contains(ProjectsApi.text(
                                BASE.masters().json(recorded.state(), recorded.operationId()), "message"));
                    });

    private static final Call ENSURE_ROOM = (api, params, recorded) -> {
        JsonNode answer = api.ensureRefinement(params.get("refinementId"));
        room(answer.path("refinement"));
        assertThat(ProjectsApi.text(answer, "technicalProcessId")).isNotBlank();
    };

    private static final Call SHOW_ROOM = (api, params, recorded) ->
            room(api.workRefinement(params.get("qualifiedId")).path("refinement"));

    /** `refinement discard` finds the room as `design` does, with its own sentence for an item with none. */
    private static final Call DISCARD_FINDS_THE_ROOM = (api, params, recorded) ->
            assertThat(WorkDesignCommand.refinement(api, params.get("qualifiedId"), "")).isNotBlank();

    /** The table prints a confirmation and reads nothing of the answer: the status is all, so the pact binds no body. */
    private static final Call DISCARD_ROOM = (api, params, recorded) -> api.discardRefinement(params.get("refinementId"));

    /** {@code qits repositories branches}: the table reads each branch's name, parent, ahead, behind and cleanup. */
    private static final Call BRANCHES = (api, params, recorded) -> {
        JsonNode branches = api.branches(params.get("repositoryId")).path("branches");
        assertThat(branches.size()).isEqualTo(recorded(recorded, "branches"));
        assertThat(branches).isNotEmpty().allSatisfy(b -> {
            assertThat(ProjectsApi.text(b, "name")).isNotBlank();
            assertThat(b.path("canCleanup").isBoolean()).isTrue();
        });
    };

    /** {@code qits repositories commits}: hash, date, author, files and subject of each commit. */
    private static final Call COMMITS = (api, params, recorded) -> {
        String branch = query(recorded, "branch");
        JsonNode answer = api.commits(params.get("repositoryId"), branch);
        assertThat(ProjectsApi.text(answer, "branch")).isEqualTo(branch);
        assertThat(answer.path("commits")).isNotEmpty().allSatisfy(c -> {
            assertThat(ProjectsApi.text(c, "shortHash")).isNotBlank();
            assertThat(ProjectsApi.text(c, "date")).isNotBlank();
            assertThat(ProjectsApi.text(c, "author")).isNotBlank();
            assertThat(ProjectsApi.text(c, "message")).isNotBlank();
            assertThat(c.path("files").isArray()).isTrue();
        });
    };

    /** {@code qits repositories commit-subjects}: the branch, the counts, the guard and the commits that do not comply. */
    private static final Call COMMIT_SUBJECTS = (api, params, recorded) -> {
        JsonNode answer = api.commitSubjects(params.get("repositoryId"), null, null);
        assertThat(ProjectsApi.text(answer, "branch")).isNotBlank();
        assertThat(answer.path("guardEnabled").isBoolean()).isTrue();
        for (String count : List.of("total", "complying", "nonComplying", "exempt")) {
            assertThat(answer.path("counts").path(count).isNumber()).as(count).isTrue();
        }
        assertThat(answer.path("nonComplying")).isNotEmpty().allSatisfy(c -> {
            assertThat(ProjectsApi.text(c, "shortHash")).isNotBlank();
            assertThat(ProjectsApi.text(c, "authorName")).isNotBlank();
            assertThat(ProjectsApi.text(c, "subject")).isNotBlank();
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
        String path = query(recorded, "path");
        JsonNode diff = api.commitDiff(params.get("repositoryId"), segmentAfter(recorded, "/commits/"), path, null);
        assertThat(ProjectsApi.text(diff, "path")).isEqualTo(path);
        assertThat(ProjectsApi.text(diff, "diff")).isNotBlank();
    };

    /** {@code qits release-request list} without a repository: the table reads these of each request. */
    private static final Call PROJECT_RELEASE_REQUESTS = (api, params, recorded) -> {
        JsonNode requests = api.projectReleaseRequests(params.get("projectId"), null).path("requests");
        assertThat(requests.size()).isEqualTo(recorded(recorded, "requests"));
        assertThat(requests).allSatisfy(r -> {
            assertThat(ProjectsApi.text(r, "id")).isNotBlank();
            assertThat(ProjectsApi.text(r, "repoName")).isNotBlank();
            assertThat(ProjectsApi.text(r, "state")).isNotBlank();
            assertThat(ProjectsApi.text(r, "summary")).isNotBlank();
            assertThat(ProjectsApi.text(r, "updatedAt")).isNotBlank();
        });
    };

    /**
     * {@code qits release-request approve} and {@code decline} find the request in the project by its
     * logical id, with no repository named: the answer carries the repository and the uuid the
     * decision door takes, and the fold judged when no --sha is given (qits-1287).
     */
    private static final Call FIND_IN_PROJECT = (api, params, recorded) -> {
        JsonNode request = api.projectReleaseRequest(params.get("projectId"), params.get("requestQualifiedId"))
                .path("request");
        assertThat(ProjectsApi.text(request, "qualifiedId")).isEqualTo(params.get("requestQualifiedId"));
        assertThat(ProjectsApi.text(request, "id")).isNotBlank();
        assertThat(ProjectsApi.text(request, "repoId")).isNotBlank();
        assertThat(request.path("mergedSha").isTextual() || request.path("mergedSha").isNull()).isTrue();
    };

    /** The decision as sent: the recorded fold and note. The table prints the request that comes back. */
    private static final Call APPROVE = (api, params, recorded) -> {
        JsonNode request = api.approveReleaseRequest(params.get("repositoryId"), params.get("requestId"),
                body(recorded).path("mergedSha").asText(), body(recorded).path("note").asText()).path("request");
        request(request);
        assertThat(ProjectsApi.text(request, "approvalState")).isEqualTo("APPROVED");
        assertThat(ProjectsApi.text(request, "mergedSha")).isEqualTo(body(recorded).path("mergedSha").asText());
    };

    private static final Call DECLINE = (api, params, recorded) -> {
        JsonNode request = api.declineReleaseRequest(params.get("repositoryId"), params.get("requestId"),
                body(recorded).path("mergedSha").asText(), body(recorded).path("note").asText()).path("request");
        request(request);
        assertThat(ProjectsApi.text(request, "approvalState")).isEqualTo("DECLINED");
        assertThat(ProjectsApi.text(request, "state")).isEqualTo("REJECTED");
        assertThat(ProjectsApi.text(request, "mergedSha")).isEqualTo(body(recorded).path("mergedSha").asText());
    };

    /** The override as sent: the recorded upstream request and note. The table prints the request that comes back. */
    private static final Call OVERRIDE_UPSTREAM = (api, params, recorded) -> request(api.overrideReleaseRequestUpstream(
            params.get("repositoryId"), params.get("requestId"), body(recorded).path("upstreamRequestId").asText(),
            body(recorded).hasNonNull("note") ? body(recorded).path("note").asText() : null)
            .path("request"));

    /** {@code qits release-request regenerate}: the kind is the recording's path segment; it prints the run's id. */
    private static final Call REGENERATE = (api, params, recorded) -> {
        JsonNode run = api.rerunReleaseRequestAutomation(params.get("repositoryId"), params.get("requestId"),
                segmentAfter(recorded, "/automations/"));
        assertThat(ProjectsApi.text(run, "id")).isNotBlank();
    };

    // --- the table ------------------------------------------------------------------------------

    /** Every command that looks a project up by its id, slug or name before it calls anything else. */
    static final List<String> PROJECT_SCOPED = List.of("qits repositories list", "qits repositories create",
            "qits repositories remote-login", "qits repositories branches", "qits repositories commits",
            "qits repositories commit-changes", "qits repositories commit-diff", "qits repositories commit-subjects",
            "qits release-request list", "qits release-request create", "qits release-request join",
            "qits release-request withdraw", "qits release-request regenerate", "qits release-request override",
            "qits release-request approve", "qits release-request decline", "qits ci runs", "qits ci run",
            "qits ci retry", "qits ci report show", "qits maintenance automations", "qits maintenance automation run",
            "qits workspaces list", "qits tui");

    /** Of those, every command that then looks a repository up by its id or name. */
    static final List<String> REPOSITORY_SCOPED = PROJECT_SCOPED.stream()
            .filter(c -> !c.equals("qits repositories list") && !c.equals("qits repositories create")
                    && !c.equals("qits release-request approve") && !c.equals("qits release-request decline"))
            .toList();

    /** Every command that takes a release request and finds it among the repository's requests of every state. */
    static final List<String> REQUEST_SCOPED = List.of("qits release-request join", "qits release-request withdraw",
            "qits release-request regenerate", "qits release-request override", "qits maintenance automations",
            "qits maintenance automation run");

    /** Every command outside {@code qits work} that names a work item by its qualified id and sends its id. */
    static final List<String> WORK_ITEM_SCOPED = List.of("qits workspaces agents list", "qits workspaces agents create",
            "qits workspaces agents prompt", "qits wait work");

    static final List<CliRow> ROWS = rows();

    public static final CliPact CONTRACT = BASE
            .client(ProjectsApi.class)
            .excluded("ProjectsApi#openApi", "the served OpenAPI document (GET /projects/q/openapi), which the payload"
                    + " commands read a request body's schema from: no operation of the provider, so no recording")
            .excluded("ProjectsApi#remoteLoginSocket", "the backup-remote sign-in terminal, a WebSocket")
            .excluded("ProjectsApi#client", "the client the doors are called with, handed to the sign-in socket")
            .excluded("ProjectsApi#repository", "a lookup over repositories(), whose rows assert the entries: the mock"
                    + " answers every entry alike, so a lookup by name would find it twice")
            .rows(ROWS);

    private static List<CliRow> rows() {
        List<CliRow> rows = new ArrayList<>();
        // qits projects, and every lookup by project and repository
        rows.add(row("qits projects list", A_PROJECT, "listProjects", "projects", PROJECTS).consumes(PROJECT));
        rows.add(row("qits agents claude hook work-links", A_PROJECT, "listProjects", "projects", PROJECTS)
                .consumes("entries[].project.slug", "entries[].project.id", "entries[].project.name"));
        rows.add(row("qits projects delivery", A_PROJECT, "listProjects", "project", DELIVERY_READ)
                .consumes(Stream.concat(PROJECT.stream(), held(A_PROJECT, "listProjects", DELIVERY).stream()).toList()));
        PROJECT_SCOPED.forEach(c -> rows.add(row(c, A_PROJECT, "listProjects", "project", FIND_PROJECT)
                .consumes(PROJECT)));
        REPOSITORY_SCOPED.forEach(c -> rows.add(row(c, ONE_REPOSITORY, "listProjectRepositories", "repositories",
                FIND_REPOSITORY).consumes(REPOSITORY)));

        // qits repositories
        rows.add(row("qits repositories list", REPOSITORIES_IN_COMPONENTS, "listProjectRepositories", "repositories",
                REPOSITORIES).consumes(held(REPOSITORIES_IN_COMPONENTS, "listProjectRepositories",
                List.of("entries[].repository.name", "entries[].repository.archetype", "entries[].repository.component",
                        "entries[].repository.id"))));
        rows.add(row("qits repositories create", TO_CREATE_A_REPOSITORY_IN, "createProjectRepository",
                "createRepository", CREATE_REPOSITORY).consumes("repository.name", "repository.archetype",
                "repository.component", "repository.id", "wrapperPath"));
        rows.add(row("qits repositories remote-login", THREE_REPOSITORIES, "listProjectRepositories",
                "repositories", WRAPPER_REPOSITORY).consumes("wrapper.repositoryId"));
        rows.add(row("qits repositories remote-login", REPOSITORIES_IN_COMPONENTS, "backupSyncProject",
                "backupSyncProject", BACKUP_SYNC).consumes("scheduled"));
        rows.add(row("qits repositories remote-login", REPOSITORIES_IN_COMPONENTS, "listProjectRepositories",
                "repositories", BACKUPS).consumes("entries[].repository.name", "entries[].repository.backupUrl",
                "entries[].repository.lastBackup.outcome", "entries[].repository.lastBackup.at",
                "entries[].repository.lastBackup.detail"));
        rows.add(row("qits repositories branches", AWAITING_REQUEST, "listRepositoryBranches", "branches", BRANCHES)
                .consumes("branches[].name", "branches[].parent", "branches[].ahead", "branches[].behind",
                        "branches[].canCleanup"));
        rows.add(row("qits repositories commits", AWAITING_REQUEST, "listBranchCommits", "commits", COMMITS)
                .consumes("branch", "commits[].shortHash", "commits[].date", "commits[].author", "commits[].message",
                        "commits[].files"));
        rows.add(row("qits repositories commit-changes", AWAITING_REQUEST, "listCommitChanges", "commitChanges",
                COMMIT_CHANGES).consumes(held(AWAITING_REQUEST, "listCommitChanges",
                List.of("files[].path", "files[].oldPath", "files[].changeType"))));
        rows.add(row("qits repositories commit-diff", AWAITING_REQUEST, "getCommitFileDiff", "commitDiff",
                COMMIT_DIFF).consumes("path", "changeType", "diff"));
        rows.add(row("qits repositories commit-subjects", AWAITING_REQUEST, "measureCommitSubjects", "commitSubjects",
                COMMIT_SUBJECTS).consumes("branch", "guardEnabled", "counts", "nonComplying[].shortHash",
                "nonComplying[].authorName", "nonComplying[].subject"));

        // qits release-request
        rows.add(row("qits release-request list", PENDING_REQUESTS, "listProjectReleaseRequests",
                "projectReleaseRequests", PROJECT_RELEASE_REQUESTS).consumes(held(PENDING_REQUESTS,
                "listProjectReleaseRequests", List.of("requests[].id", "requests[].qualifiedId", "requests[].repoName",
                        "requests[].state", "requests[].approvalState", "requests[].priority", "requests[].summary",
                        "requests[].version", "requests[].updatedAt"))));
        rows.add(row("qits release-request list", AWAITING_REQUEST, "listRepositoryReleaseRequests", "releaseRequests",
                OPEN_REQUESTS).consumes(held(AWAITING_REQUEST, "listRepositoryReleaseRequests",
                List.of("requests[].id", "requests[].qualifiedId", "requests[].state", "requests[].priority",
                        "requests[].summary", "requests[].version", "requests[].updatedAt"))));
        REQUEST_SCOPED.forEach(c -> rows.add(row(c, REQUESTS_OF_EVERY_STATE, "listRepositoryReleaseRequests",
                "releaseRequests", FIND).consumes(FIND_REQUEST)));
        rows.add(row("qits release-request create", A_BRANCH_TO_RELEASE, "createReleaseRequest",
                "createReleaseRequest", CREATE_REQUEST)
                .consumes(held(A_BRANCH_TO_RELEASE, "createReleaseRequest", prefixed("request.", REQUEST)))
                .anyLength(openEndedRequest()));
        rows.add(row("qits release-request join", OPEN_TO_ANOTHER_BRANCH, "addReleaseRequestSource",
                "joinReleaseRequest", JOIN_REQUEST)
                .consumes(held(OPEN_TO_ANOTHER_BRANCH, "addReleaseRequestSource", prefixed("request.", REQUEST)))
                .anyLength(openEndedRequest()));
        rows.add(row("qits release-request withdraw", AWAITING_REQUEST, "withdrawReleaseRequest",
                "withdrawReleaseRequest", WITHDRAW)
                .consumes(held(AWAITING_REQUEST, "withdrawReleaseRequest", prefixed("request.", REQUEST)))
                .anyLength(openEndedRequest()));
        for (String command : List.of("qits release-request approve", "qits release-request decline")) {
            rows.add(row(command, PROJECT_REQUESTS_OF_EVERY_STATE, "getProjectReleaseRequest", "projectReleaseRequest",
                    FIND_IN_PROJECT).consumes("request.id", "request.qualifiedId", "request.repoId", "request.mergedSha"));
        }
        rows.add(CliRow.of("qits release-request approve", AWAITING_WHILE_ITS_BUILD_RUNS, "approveReleaseRequest",
                "ProjectsApi#approveReleaseRequest", asThePerson(APPROVE))
                .consumes(held(AWAITING_WHILE_ITS_BUILD_RUNS, "approveReleaseRequest", prefixed("request.", REQUEST)))
                .anyLength(openEndedRequest())
                .header("Authorization", "{authorization}"));
        rows.add(CliRow.of("qits release-request decline", AWAITING_REQUEST, "declineReleaseRequest",
                "ProjectsApi#declineReleaseRequest", asThePerson(DECLINE))
                .consumes(held(AWAITING_REQUEST, "declineReleaseRequest", prefixed("request.", REQUEST)))
                .anyLength(openEndedRequest())
                .header("Authorization", "{authorization}"));
        rows.add(row("qits release-request regenerate", HELD_BY_A_FAILED_AUTOMATION, "rerunReleaseRequestAutomation",
                "rerunReleaseRequestAutomation", REGENERATE).consumes("id"));
        rows.add(row("qits release-request regenerate", AWAITING_REQUEST, "listRepositoryReleaseRequests",
                "releaseRequests", OPEN_REQUESTS).consumes(held(AWAITING_REQUEST, "listRepositoryReleaseRequests",
                List.of("requests[].id", "requests[].qualifiedId", "requests[].state"))));
        rows.add(CliRow.of("qits release-request override", HELD_BY_AN_UPSTREAM_RELEASE, "overrideReleaseRequestUpstream",
                "ProjectsApi#overrideReleaseRequestUpstream", asThePerson(OVERRIDE_UPSTREAM))
                .consumes(held(HELD_BY_AN_UPSTREAM_RELEASE, "overrideReleaseRequestUpstream", prefixed("request.", REQUEST)))
                .anyLength(openEndedRequest())
                .header("Authorization", "{authorization}"));

        // qits tui: the dropdowns
        rows.add(row("qits tui", REPOSITORIES_IN_COMPONENTS, "listProjectRepositories", "repositories",
                REPOSITORY_CHOICES).consumes("entries[].repository.name", "entries[].repository.archetype"));
        rows.add(row("qits tui", AWAITING_REQUEST, "listRepositoryReleaseRequests", "releaseRequests", OPEN_REQUESTS)
                .consumes(held(AWAITING_REQUEST, "listRepositoryReleaseRequests", List.of("requests[].id",
                        "requests[].qualifiedId", "requests[].state", "requests[].summary"))));
        rows.add(row("qits tui", RELEASED_REQUESTS, "listRepositoryReleaseRequests", "releaseRequests",
                RELEASED_VERSIONS).consumes("requests[].version", "requests[].summary"));

        // qits work
        rows.add(row("qits work list", WORK_IN_EVERY_STATUS, "listProjectWork", "projectWork", LIST)
                .consumes(held(WORK_IN_EVERY_STATUS, "listProjectWork", prefixed("entities[].", ROW))));
        rows.add(row("qits work details", EPIC_IN_DETAIL, "getWork", "work", GET)
                .consumes(held(EPIC_IN_DETAIL, "getWork", DETAILS)));
        rows.add(row("qits work details", EPIC_IN_DETAIL, "listWorkComments", "workComments", COMMENTS)
                .consumes("entries[].comment.createdAt", "entries[].comment.author", "entries[].comment.body"));
        rows.add(row("qits work details", EPIC_IN_DETAIL, "listWorkChildren", "workChildren", CHILDREN)
                .consumes(held(EPIC_IN_DETAIL, "listWorkChildren", prefixed("children[].", ROW))));
        rows.add(row("qits work details", SUPERSEDED_ITEMS, "listProjectWork", "projectWork", CLOSES)
                .consumes(held(SUPERSEDED_ITEMS, "listProjectWork",
                        prefixed("entities[].", Stream.concat(ROW.stream(), Stream.of("supersededBy")).toList())))
                .request("GET /projects/api/projects/{projectId}/work?supersededBy={entityId}"));
        rows.add(row("qits work create", NO_WORK, "createWork", "createWork", CREATE)
                .consumes(held(NO_WORK, "createWork", ROW)));
        rows.add(row("qits work create", REGISTRY, "getWorkArchetypeSchema", "archetypeSchema", schema("create"))
                .consumes("properties", "required"));
        rows.add(row("qits work update", REPORTED_TICKET, "patchWork", "patchWork", PATCH)
                .consumes(held(REPORTED_TICKET, "patchWork", ROW)).header("Content-Type", ProjectsApi.MERGE_PATCH));
        rows.add(row("qits work update", TICKET_WITH_A_COMMENT, "getWork", "work", GET_ARCHETYPE).consumes("archetype"));
        rows.add(row("qits work update", UPDATE_SCHEMA, "getWorkArchetypeSchema", "archetypeSchema", schema("update"))
                .consumes("properties", "required"));
        rows.add(row("qits work transition", TICKET_WITH_A_COMMENT, "getWork", "work", GET_FOR_TRANSITION)
                .consumes(held(TICKET_WITH_A_COMMENT, "getWork", CARRIED)));
        rows.add(row("qits work transition", TRANSITION_SCHEMA, "getWorkArchetypeSchema", "archetypeSchema",
                schema("transition")).consumes("properties", "required"));
        rows.add(row("qits work transition", REPORTED_TICKET, "transitionWork", "transitionWork", TRANSITION)
                .consumes(held(REPORTED_TICKET, "transitionWork", prefixed("{qualifiedId}.", ROW))));
        rows.add(row("qits work status", IMPLEMENTED_TICKET, "getWork", "work", GET)
                .consumes("archetype", "qualifiedId", "status"));
        rows.add(row("qits work status", REGISTRY, "listWorkArchetypes", "archetypes", REGISTRY_READ)
                .consumes("archetypes[].archetype", "archetypes[].lifecycle", "archetypes[].transitions"));
        rows.add(row("qits work status", REPORTED_TICKET, "setWorkStatus", "setWorkStatus", STATUS)
                .consumes(held(REPORTED_TICKET, "setWorkStatus", ROW)));
        rows.add(row("qits work comment create", TICKET_WITH_A_COMMENT, "addWorkComment", "addWorkComment", COMMENT)
                .consumes("comment.id", "comment.author", "comment.createdAt"));
        rows.add(row("qits work comment update", TICKET_WITH_A_COMMENT, "editWorkComment", "editWorkComment",
                EDIT_COMMENT).consumes("comment.id", "comment.author", "comment.updatedAt")
                .header("Content-Type", ProjectsApi.MERGE_PATCH));
        rows.add(row("qits work block", REPORTED_TICKET, "setWorkBlocked", "setWorkBlocked", BLOCK)
                .consumes("block.blocked", "block.blockSource", "block.blockReason"));
        rows.add(row("qits work unblock", BUG_TICKET_IN_DETAIL, "setWorkBlocked", "setWorkBlocked", UNBLOCK)
                .consumes("block.blocked", "block.archetype", "block.status"));
        rows.add(row("qits work delete", REPORTED_TICKET, "deleteWork", "deleteWork", DELETE).consumes("success"));
        rows.add(row("qits work progress", CAMPAIGN_IN_DETAIL, "getWorkProgress", "workProgress", PROGRESS)
                .consumes("progress.campaign.qualifiedId", "progress.evaluator.connected", "progress.members[].state",
                        "progress.members[].waitsFor", "progress.members[].entity.qualifiedId"));
        rows.add(row("qits work dispatch", READY_FOR_DEV_TICKET, "dispatchWork", "dispatchWork", DISPATCH)
                .consumes("dispatch.mode", "dispatch.phase", "dispatch.branch", "dispatch.assignee"));
        rows.add(row("qits work dossier list", BUG_TICKET_IN_DETAIL, "listWorkDossier", "workDossier", DOSSIER)
                .consumes("pages[].slug", "pages[].title", "pages[].version", "pages[].position"));
        rows.add(row("qits work dossier get", EPIC_IN_DETAIL, "getWorkDossierPage", "workDossierPage", DOSSIER_PAGE)
                .consumes("slug", "title", "version", "position", "body"));
        rows.add(row("qits work dossier put", BUG_TICKET_IN_DETAIL, "createWorkDossierPage", "createWorkDossierPage",
                DOSSIER_CREATE).consumes("slug", "title", "version", "position"));
        rows.add(row("qits work dossier put", BUG_TICKET_IN_DETAIL, "putWorkDossierPage", "putWorkDossierPage",
                DOSSIER_PUT).consumes("slug", "title", "version", "position"));
        rows.add(row("qits work dossier move", BUG_TICKET_IN_DETAIL, "moveWorkDossierPage", "moveWorkDossierPage",
                DOSSIER_MOVE).consumes("slug", "title", "version", "position"));
        rows.add(row("qits work dossier delete", BUG_TICKET_IN_DETAIL, "deleteWorkDossierPage",
                "deleteWorkDossierPage", DOSSIER_DELETE).consumes("success"));
        rows.add(row("qits work dossier inline", EPIC_WITH_A_SKETCH, "inlineWorkDossierAsset",
                "inlineWorkDossierAsset", INLINE).consumes("markdown"));
        List<String> member = List.of("membershipId", "position", "entity.qualifiedId", "entity.status", "groups");
        rows.add(row("qits work members list", CAMPAIGN_TO_EDIT, "listWorkMembers", "workMembers", MEMBERS)
                .consumes(prefixed("members[].", member)));
        rows.add(row("qits work members add", CAMPAIGN_TO_EDIT, "addWorkMember", "addWorkMember", MEMBER_ADD)
                .consumes(prefixed("member.", member)));
        rows.add(row("qits work members move", CAMPAIGN_TO_EDIT, "moveWorkMember", "moveWorkMember", MEMBER_MOVE)
                .consumes(prefixed("members[].", member)));
        rows.add(row("qits work members remove", CAMPAIGN_TO_EDIT, "removeWorkMember", "removeWorkMember",
                MEMBER_REMOVE).consumes(prefixed("members[].", member)));
        rows.add(row("qits work members condition", CAMPAIGN_TO_EDIT, "setWorkMemberCondition",
                "setWorkMemberCondition", MEMBER_CONDITION).consumes(prefixed("member.", member)));
        List<String> design = List.of("id", "title", "version", "htmlBytes", "inUse", "updatedAt");
        rows.add(row("qits work design list", REPORTED_TICKET, "getWorkRefinement", "workRefinement", NO_REFINEMENT)
                .consumes("refinement"));
        for (String command : List.of("qits work design list", "qits work design get", "qits work design put")) {
            rows.add(row(command, TICKET_WITH_A_DESIGN, "getWorkRefinement", "workRefinement", OPEN_REFINEMENT)
                    .consumes("refinement.id"));
        }
        rows.add(row("qits work design list", TICKET_WITH_A_DESIGN, "listRefinementDesigns", "refinementDesigns",
                DESIGNS).consumes(prefixed("designs[].", design)));
        rows.add(row("qits work design get", TICKET_WITH_A_DESIGN, "getRefinementDesign", "refinementDesign",
                DESIGN_GET).consumes(Stream.concat(design.stream(), Stream.of("html")).toList()));
        rows.add(row("qits work design put", TICKET_WITH_A_DESIGN, "addRefinementDesign", "addRefinementDesign",
                DESIGN_ADD).consumes(design));
        rows.add(row("qits work design put", TICKET_WITH_A_DESIGN, "updateRefinementDesign", "updateRefinementDesign",
                DESIGN_UPDATE).consumes(design));

        List<String> room = List.of("refinement.id", "refinement.entityId", "refinement.label",
                "refinement.runtimeStatus");
        rows.add(row("qits work refinement open", TICKET_WITH_A_DESIGN, "startWorkRefinement", "openWorkRefinement",
                OPEN_ROOM).consumes("refinement.id"));
        rows.add(row("qits work refinement open", REFINED_TICKET, "startWorkRefinement", "openWorkRefinement",
                OPEN_REFUSED).consumes("message"));
        rows.add(row("qits work refinement open", TICKET_WITH_A_DESIGN, "ensureRefinementContainer",
                "ensureRefinement", ENSURE_ROOM).consumes(Stream.concat(room.stream(),
                Stream.of("technicalProcessId")).toList()));
        rows.add(row("qits work refinement show", REPORTED_TICKET, "getWorkRefinement", "workRefinement",
                NO_REFINEMENT).consumes("refinement"));
        rows.add(row("qits work refinement show", TICKET_WITH_A_DESIGN, "getWorkRefinement", "workRefinement",
                SHOW_ROOM).consumes(room));
        rows.add(row("qits work refinement discard", TICKET_WITH_A_DESIGN, "getWorkRefinement", "workRefinement",
                DISCARD_FINDS_THE_ROOM).consumes("refinement.id"));
        rows.add(row("qits work refinement discard", TICKET_WITH_A_DESIGN, "discardRefinement", "discardRefinement",
                DISCARD_ROOM));

        // a work item named by its qualified id outside qits work
        WORK_ITEM_SCOPED.forEach(c -> rows.add(row(c, TICKET_WITH_A_COMMENT, "getWork", "work", WORK_ID)
                .consumes("id", "qualifiedId", "archetype")));
        rows.add(row("qits agents claude statusline work-items", TICKET_WITH_A_COMMENT, "getWork", "work", WORK_STATE)
                .consumes(held(TICKET_WITH_A_COMMENT, "getWork", List.of("status", "blocked", "blockSource"))));

        // qits artifacts publish changelog, in every release step
        rows.add(row("qits artifacts publish changelog", RELEASED_REQUEST, "listReleaseRequestCommits",
                "releaseRequestCommits", RELEASE_COMMITS)
                .consumes("commits[].fold", "commits[].shortHash", "commits[].message", "commits[].author"));
        rows.add(row("qits artifacts publish changelog", TICKET_WITH_A_COMMENT, "getWork", "work", TITLE)
                .consumes("title"));
        rows.add(row("qits artifacts publish changelog", REQUESTS_OF_EVERY_STATE, "listRepositoryReleaseRequests",
                "releaseRequests", EARLIER_RELEASES)
                .consumes("requests[].id", "requests[].qualifiedId", "requests[].version"));
        return List.copyOf(rows);
    }

    private static CliRow row(String command, String state, String operationId, String method, Call call) {
        return CliRow.of(command, state, operationId, "ProjectsApi#" + method, api(call));
    }

    private static List<String> prefixed(String prefix, List<String> paths) {
        return paths.stream().map(p -> prefix + p).toList();
    }

    /** The paths the recording holds: the command reads the others only where the service sends them. */
    private static List<String> held(String state, String operationId, List<String> paths) {
        return BASE.held(state, operationId, paths);
    }

    /** {@link #REQUEST_OPEN_ENDED}, prefixed for a row's {@code request.*} body, as {@code anyLength} takes it. */
    private static String[] openEndedRequest() {
        return prefixed("request.", REQUEST_OPEN_ENDED).toArray(String[]::new);
    }

    private ProjectsContract() {
    }
}
