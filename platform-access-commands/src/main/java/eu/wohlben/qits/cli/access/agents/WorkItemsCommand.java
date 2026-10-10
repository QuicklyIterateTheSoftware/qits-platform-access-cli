package eu.wohlben.qits.cli.access.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.PlatformUrls;
import eu.wohlben.qits.cli.access.projects.ProjectsApi;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Claude Code's status line: the work items the session spoke of most recently, each with its
 * status square and block glyph, as links to the landing app.
 * <p>
 * Claude runs it often and waits for it, so it never waits for the network: the states come from a
 * short-lived cache ({@link WorkStates}) that this same command refreshes with {@code --refresh},
 * started detached for the ids that are stale.
 */
@CommandLine.Command(name = "work-items", mixinStandardHelpOptions = true,
        description = {
                "Claude Code's status line: the work items this session spoke of most recently, newest first, "
                        + "each with its status square and block glyph and linked to the landing app (OSC 8). Claude "
                        + "runs it; a person does not.",
                "It reads Claude's status line JSON on stdin and the end of the transcript it names, where ids "
                        + "count from Claude's text and from tool results, by the same rules as `qits agents claude "
                        + "hook work-links`. It prints one line, or nothing when there is no id. The squares are the "
                        + "session names' palette; a red mark in front means blocked, and an interrobang that the "
                        + "item's agent is waiting on a person.",
                "Each id's status is cached for 60 seconds in $XDG_CACHE_HOME/qits/work-states.json (default "
                        + "~/.cache/qits/). What is cached shows at once, an id with nothing cached shows without "
                        + "a mark, and one background refresh asks the projects service for the stale ids with this "
                        + "home's credential. Any error prints the ids without marks, or nothing; the exit is 0."},
        footerHeading = HelpText.EXAMPLES,
        footer = {
                "  \"statusLine\": {\"type\": \"command\", \"command\": \"qits agents claude statusline work-items\", "
                        + "\"refreshInterval\": 30}",
                "",
                "The example is the entry in Claude's settings.json (~/.claude/settings.json).",
                "",
                "- Your own status line script can run it too: pass the JSON it was given on stdin and print the "
                        + "line it prints.",
                "- The landing app and the project slugs are found as `qits agents claude hook work-links` finds "
                        + "them, with the same --landing-url, QITS_LANDING_URL and QITS_LINK_PROJECTS."},
        exitCodeListHeading = HelpText.EXIT_CODES,
        exitCodeList = {"0:Always, also when nothing was printed."})
public class WorkItemsCommand extends PlatformCommand {

    private static final ObjectMapper JSON = new ObjectMapper();

    @CommandLine.Option(names = "--landing-url", paramLabel = "<url>",
            description = "The landing app's base URL. Default: QITS_LANDING_URL, else the platform's bare domain.")
    String landingUrl;

    @CommandLine.Option(names = "--limit", paramLabel = "<n>", defaultValue = "5",
            description = "How many ids to show. Default: ${DEFAULT-VALUE}.")
    int limit;

    /** Asks the projects service for these ids and writes their states. What the status line starts. */
    @CommandLine.Option(names = "--refresh", hidden = true, arity = "1..*", paramLabel = "<id>")
    List<String> refresh;

    @Override
    protected int execute(CliContext context) throws CliFailure, InterruptedException {
        if (refresh != null && !refresh.isEmpty()) {
            return refresh(context);
        }
        if (context.stdinIsTerminal()) {
            context.err().println("Reads Claude's status line JSON on stdin. Claude runs this; see --help.");
            return 0;
        }
        try {
            String line = line(context);
            if (line != null) {
                context.out().println(line);
            }
        } catch (Exception | LinkageError | StackOverflowError any) {
            // Never break the status line: Claude shows nothing from it this time.
        }
        return 0;
    }

    /** The line to show, or null for none. */
    String line(CliContext context) throws Exception {
        JsonNode event = JSON.readTree(context.in().readAllBytes());
        String transcript = event == null ? "" : event.path("transcript_path").asText("");
        if (transcript.isBlank()) {
            return null;
        }
        String landing;
        try {
            landing = PlatformUrls.landing(landingUrl, context.env(), WorkLinksCommand.idpUrlWithoutNetwork(context));
        } catch (Exception noAddress) {
            landing = null;
        }
        WorkLinks links = new WorkLinks(WorkLinksCommand.slugs(context), landing, WorkLinks.Style.OSC8);
        List<WorkLinks.WorkId> newest = TranscriptIds.newest(Path.of(transcript), links, Math.max(1, limit));
        if (newest.isEmpty()) {
            return null;
        }
        Map<String, WorkStates.State> states = states(context, newest.stream().map(WorkLinks.WorkId::id).toList());
        String shownLanding = landing;
        return newest.stream().map(id -> {
            WorkStates.State state = states.get(id.id());
            String marker = state == null ? "" : StatusMarker.of(state.status(), state.blocked(), state.blockSource());
            String shown = shownLanding == null ? id.id() : links.link(id.slug(), id.id());
            return marker.isEmpty() ? shown : marker + " " + shown;
        }).collect(Collectors.joining("  "));
    }

    /** What is cached, after starting one refresh for the stale ids. Nothing on any error. */
    private static Map<String, WorkStates.State> states(CliContext context, List<String> ids) {
        try {
            WorkStates cache = WorkStates.fromEnvironment(context.env(), context.clock());
            Map<String, WorkStates.State> states = cache.read(ids);
            List<String> stale = cache.claimStale(ids);
            if (!stale.isEmpty()) {
                List<String> args = new ArrayList<>(List.of("agents", "claude", "statusline", "work-items", "--refresh"));
                args.addAll(stale);
                Detached.start(args);
            }
            return states;
        } catch (IOException | RuntimeException unreadable) {
            return Map.of();
        }
    }

    private int refresh(CliContext context) throws CliFailure, InterruptedException {
        ProjectsApi api = ProjectsApi.connect(context, null);
        Map<String, WorkStates.State> answers = new HashMap<>();
        for (String id : refresh) {
            try {
                JsonNode item = api.work(id);
                answers.put(id, new WorkStates.State(ProjectsApi.text(item, "status"), item.path("blocked").asBoolean(false),
                        item.hasNonNull("blockSource") ? item.get("blockSource").asText() : null));
            } catch (CliFailure oneFailed) {
                // The others still count; this one is asked again once its mark is stale.
            }
        }
        try {
            WorkStates.fromEnvironment(context.env(), context.clock()).write(answers);
        } catch (IOException cannot) {
            throw new CliFailure("Cannot write the work-states cache: " + cannot.getMessage(), CliFailure.FAILED);
        }
        return 0;
    }
}
