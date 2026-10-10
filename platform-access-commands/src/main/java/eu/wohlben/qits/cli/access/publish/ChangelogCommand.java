package eu.wohlben.qits.cli.access.publish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cli.access.changelog.CommitSubjects;
import eu.wohlben.qits.cli.access.ci.CiApi;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.projects.ProjectsApi;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * {@code qits artifacts publish changelog}: the release's {@code CHANGELOG.md}, written here from
 * what the platform already knows and published as the docs site {@code @changelog/<repository>} at
 * the release's version.
 * <p>
 * Every release's publish step runs it. The release is the step's {@code QITS_EVENT_PAYLOAD} (the
 * SCMRelease that started the run); the commits are the release request's, from qits-projects; the
 * tickets are the ones their subjects name ({@link CommitSubjects}), titled by qits-projects; the
 * highlights are the gate run's reports, from qits-ci. The document's last line lists the tickets
 * that resolved, and a dependant's bump message reads it back ({@code qits changelog bump-message}).
 * The second line's time is the payload's {@code occurredAt} when it carries one, else qits-ci's own
 * {@code QITS_EVENT_OCCURRED_AT}; neither is required, and a value that will not parse is dropped
 * rather than failing the command, because the time is cosmetic.
 * <p>
 * Kept forever for now (owner, 2026-10-04): changelogs should probably be deleted during GC along
 * with the releases they belong to; not implemented, see qits-893.
 */
@CommandLine.Command(name = "changelog",
        // Not mixinStandardHelpOptions: --version is the release's version (see AGENTS.md).
        description = {"Write this release's CHANGELOG.md and publish it as the docs site @changelog/<repository> "
                        + "at --version. Run in a release's publish step.",
                "The release comes from QITS_EVENT_PAYLOAD (its repository, repositoryName, releaseRequestId and "
                        + "occurredAt); the commits from the release request in qits-projects, oldest first and "
                        + "without the request's own merge commits; the tickets from the commits' subjects "
                        + "(`feat(qits-9, qits-10): ...`), titled by qits-projects; and the report highlights from "
                        + "the gate run's reports in qits-ci (QITS_CI_RUN_ID), when it has any.",
                "The second line's time is the payload's occurredAt, or QITS_EVENT_OCCURRED_AT when the payload "
                        + "has none; both are optional, and a value that does not parse as an ISO-8601 time is "
                        + "dropped rather than refused, so the line is left without a time instead.",
                "The last line is always `associated tickets:` followed by every ticket that exists, as "
                        + "`qits-9`; `qits changelog bump-message` reads it back.",
                "The bundle is a .tar.gz with one entry, CHANGELOG.md, published like `docs submit` with the "
                        + "given --meta and release.request.id=<request>. An occupied version is skipped, as "
                        + "there."},
        footerHeading = "%nExamples:%n",
        footer = {"  qits artifacts publish changelog --version \"$QITS_VERSION\" --meta "
                + "git.commit.hash=\"$QITS_CI_SHA\" --meta git.repository.name=\"$QITS_CI_REPO_NAME\"",
                "",
                "- qits-projects is https://projects.qits.$QITS_DOMAIN and qits-ci https://ci.qits.$QITS_DOMAIN; "
                        + "a ticket links to https://qits.$QITS_DOMAIN/projects/<slug>/work/detail/<id>.",
                "- A ticket qits-projects does not know is listed as (no such work item), without a link, and "
                        + "left out of the last line.",
                "- Changelogs are kept for good for now; nothing deletes one with its release."},
        exitCodeListHeading = "%nExit codes:%n",
        exitCodeList = {
                "0:Published, or already published (not verified, as for docs submit).",
                "1:Refused: bad arguments, QITS_EVENT_PAYLOAD or QITS_CI_RUN_ID missing or incomplete, the release "
                        + "request unknown, or a 4xx from the store.",
                "2:Could not ask: a service unreachable, an I/O failure, or a 5xx."})
public class ChangelogCommand extends AbstractPublishCommand {

    static final String PAYLOAD = "QITS_EVENT_PAYLOAD";
    static final String RUN_ID = "QITS_CI_RUN_ID";
    static final String OCCURRED_AT = "QITS_EVENT_OCCURRED_AT";
    static final String FILE = "CHANGELOG.md";

    private static final ObjectMapper JSON = new ObjectMapper();

    @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
    boolean help;

    @CommandLine.Option(names = "--version", paramLabel = "<version>", description = "The release's version.")
    List<String> version = new ArrayList<>();

    @CommandLine.Option(names = "--meta", paramLabel = "<key=value>",
            description = "A metadata header, sent as X-Artifacts-Meta-<key>. Repeatable.")
    List<String> meta = new ArrayList<>();

    @CommandLine.Unmatched
    List<String> unmatched = new ArrayList<>();

    /**
     * qits-projects' and qits-ci's origins when the suite says so; null, as in every real run, composes
     * them from {@code QITS_DOMAIN}. Fields and not variables, for the reason {@link #hosts} is one.
     */
    String projectsOrigin;
    String ciOrigin;

    /** The release, as the payload says. */
    record Release(String repository, String repositoryName, String releaseRequestId, Instant occurredAt) {
    }

    @Override
    protected int run(Env env, Console console) {
        PublishArgs.noExtras(unmatched);
        String version = PublishArgs.requiredOnce(this.version, "--version");
        Release release = release(env.get(PAYLOAD), env.get(OCCURRED_AT));
        String runId = env.get(RUN_ID);
        if (runId == null) {
            throw CliException.policy(RUN_ID + " is not set: qits-ci sets it in every step");
        }
        CliContext context = context();
        Map<String, String> variables = context.env();
        PlatformClient client = new PlatformClient(PublishCredential.forCiStep(variables, context.err(), context.clock()));
        ProjectsApi projects = new ProjectsApi(client,
                projectsOrigin != null ? projectsOrigin : Store.publicOrigin("projects", variables));
        CiApi ci = new CiApi(client, ciOrigin != null ? ciOrigin : Store.publicOrigin("ci", variables));

        List<Changelog.Commit> commits = new ArrayList<>();
        List<String> subjects = new ArrayList<>();
        for (JsonNode commit : commits(projects, release)) {
            String subject = CommitSubjects.subject(text(commit, "message"));
            subjects.add(subject);
            String shortHash = text(commit, "shortHash");
            if (shortHash == null) {
                String hash = text(commit, "hash");
                shortHash = hash == null ? "" : hash.substring(0, Math.min(7, hash.length()));
            }
            commits.add(new Changelog.Commit(shortHash, subject, text(commit, "author")));
        }
        String root = Store.projectRoot(variables);
        List<Changelog.Ticket> tickets = new ArrayList<>();
        for (String id : CommitSubjects.ids(subjects)) {
            tickets.add(ticket(projects, id, root));
        }
        List<Changelog.Highlight> highlights = highlights(ci, runId);

        String markdown = Changelog.render(
                new Changelog.Release(release.repositoryName(), version, release.releaseRequestId(), release.occurredAt()),
                commits, tickets, highlights);
        List<String> meta = new ArrayList<>(this.meta);
        meta.add("release.request.id=" + release.releaseRequestId());
        Path bundle = ContractDocs.temporary(Archives.writeTarGz(
                List.of(new Archives.Entry(FILE, markdown.getBytes(StandardCharsets.UTF_8)))));
        try {
            return new Publisher(http(), store(env), console)
                    .docsSubmit("@changelog/" + release.repositoryName(), version, bundle, meta);
        } finally {
            ContractDocs.delete(bundle);
        }
    }

    /**
     * The SCMRelease the step was started for: the three ids required, the time optional. The time
     * is cosmetic (it only ever reaches the changelog's second line), so neither source of it can
     * fail the command: the payload's {@code occurredAt} wins when it parses, else {@code
     * occurredAtEnv} (qits-ci's {@code QITS_EVENT_OCCURRED_AT}) when that parses, else the line
     * leaves the time out.
     */
    static Release release(String payload, String occurredAtEnv) {
        if (payload == null) {
            throw CliException.policy(PAYLOAD + " is not set: qits-ci sets it in every release step");
        }
        JsonNode event;
        try {
            event = JSON.readTree(payload);
        } catch (java.io.IOException notJson) {
            throw CliException.policy(PAYLOAD + " is not JSON");
        }
        if (event == null || !event.isObject()) {
            throw CliException.policy(PAYLOAD + " is not a JSON object");
        }
        List<String> missing = new ArrayList<>();
        for (String field : List.of("repository", "repositoryName", "releaseRequestId")) {
            if (text(event, field) == null) {
                missing.add(field);
            }
        }
        if (!missing.isEmpty()) {
            throw CliException.policy(PAYLOAD + " has no " + String.join(", no ", missing));
        }
        Instant occurredAt = parsedInstant(text(event, "occurredAt"));
        if (occurredAt == null) {
            occurredAt = parsedInstant(occurredAtEnv);
        }
        return new Release(text(event, "repository"), text(event, "repositoryName"), text(event, "releaseRequestId"),
                occurredAt);
    }

    /** An ISO-8601 instant, or null when the value is absent or does not parse as one. */
    private static Instant parsedInstant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException notATime) {
            return null;
        }
    }

    /** The request's commits, oldest first, without its own merge commits. */
    private static List<JsonNode> commits(ProjectsApi projects, Release release) {
        JsonNode answer;
        try {
            answer = projects.releaseRequestCommits(release.repository(), release.releaseRequestId());
        } catch (CliFailure failed) {
            throw translated("the release request's commits", failed);
        } catch (InterruptedException interrupted) {
            throw interrupted(interrupted);
        }
        List<JsonNode> commits = new ArrayList<>();
        for (JsonNode commit : answer.path("commits")) {
            if (!commit.path("fold").asBoolean(false)) {
                commits.add(0, commit);
            }
        }
        return commits;
    }

    /** The ticket, titled; one the service does not know is listed without a title or a link. */
    private static Changelog.Ticket ticket(ProjectsApi projects, String id, String root) {
        try {
            JsonNode item = projects.work(id);
            String title = text(item, "title");
            return new Changelog.Ticket(id, title == null ? "" : title,
                    root + "/projects/" + CommitSubjects.slug(id) + "/work/detail/" + id);
        } catch (CliFailure failed) {
            if (failed.status() == 404) {
                return new Changelog.Ticket(id, null, null);
            }
            // Anything else is a changelog that would read wrong for good: the question is asked again.
            throw CliException.transport("cannot read work item " + id + ": " + failed.getMessage());
        } catch (InterruptedException interrupted) {
            throw interrupted(interrupted);
        }
    }

    /**
     * The gate run's highlights, its reports ordered by step and kind. None at all when qits-ci has no
     * gate reports for the run, or no door for them yet (404): the section is then left out.
     */
    private static List<Changelog.Highlight> highlights(CiApi ci, String runId) {
        JsonNode answer;
        try {
            answer = ci.gateReports(runId);
        } catch (CliFailure failed) {
            if (failed.status() == 404) {
                return List.of();
            }
            throw translated("the gate run's reports", failed);
        } catch (InterruptedException interrupted) {
            throw interrupted(interrupted);
        }
        List<JsonNode> reports = new ArrayList<>();
        answer.path("reports").forEach(reports::add);
        reports.sort(Comparator.comparingInt((JsonNode r) -> r.path("stepIndex").asInt(Integer.MAX_VALUE))
                .thenComparing(r -> r.path("kind").asText("")));
        List<Changelog.Highlight> highlights = new ArrayList<>();
        for (JsonNode report : reports) {
            for (JsonNode highlight : report.path("highlights")) {
                highlights.add(new Changelog.Highlight(report.path("kind").asText(""),
                        highlight.path("severity").asText(""), highlight.path("text").asText("")));
            }
        }
        return highlights;
    }

    /**
     * A platform call's failure as this client's exit codes: a 4xx (a 404 included) or an unusable
     * credential or address is a refusal, 1; anything else (unreachable, a 5xx, an answer that is
     * not JSON) could not be answered, 2.
     */
    private static CliException translated(String what, CliFailure failed) {
        int status = failed.status();
        boolean refused = (status >= 400 && status < 500)
                || (status == 0 && !failed.retryable() && failed.exitCode() == CliFailure.USAGE);
        String message = "cannot read " + what + ": " + failed.getMessage();
        return refused ? CliException.policy(message) : CliException.transport(message);
    }

    private static CliException interrupted(InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return CliException.transport("interrupted", interrupted);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }
}
