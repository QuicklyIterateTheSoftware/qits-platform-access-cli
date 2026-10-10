package eu.wohlben.qits.cli.access.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.PlatformUrls;
import eu.wohlben.qits.cli.access.projects.ProjectsApi;
import eu.wohlben.qits.cli.access.session.Session;
import picocli.CommandLine;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Claude Code's {@code MessageDisplay} hook: work ids in Claude's messages shown as links.
 * <p>
 * Claude holds each batch of text until the hook returns, and shows the original when it fails. So
 * the hook path never waits for the network and never fails: any error prints nothing and exits 0.
 * The project slugs come from a cache ({@link LinkProjects}) that this same command refreshes with
 * {@code --refresh}, started detached when the cache is stale.
 */
@CommandLine.Command(name = "work-links", mixinStandardHelpOptions = true,
        description = {
                "Claude Code's MessageDisplay hook: shows work ids such as qits-111 in Claude's messages as links "
                        + "to the landing app's work item page. Claude runs it; a person does not.",
                "It reads one batch of the message on stdin and prints the text to show, or nothing when no id was "
                        + "linked. Only the screen changes: the transcript and the model keep the plain id. An id "
                        + "inside a word, a branch (ticket/qits-1152), a longer name (qits-1152-x), a link, inline "
                        + "code or a code block stays as it is. Any error prints nothing and exits 0, so Claude "
                        + "shows the original text.",
                "The project slugs are cached for an hour in $XDG_CACHE_HOME/qits/work-links.json (default "
                        + "~/.cache/qits/). A stale cache is used as it is while a refresh runs in the background, "
                        + "with this home's credential; until the first one is done only `qits` ids are linked."},
        footerHeading = HelpText.EXAMPLES,
        footer = {
                "  {\"hooks\": {\"MessageDisplay\": [{\"hooks\": [{\"type\": \"command\", "
                        + "\"command\": \"qits agents claude hook work-links\", \"timeout\": 5}]}]}}",
                "",
                "The example is the entry in Claude's settings.json (~/.claude/settings.json).",
                "",
                "- The landing app is the platform's bare domain: on a workstation the session's idp address without "
                        + "`idp.`, elsewhere https://qits.<QITS_DOMAIN>. --landing-url or QITS_LANDING_URL sets it.",
                "- QITS_LINK_STYLE=osc8 is the same as --style osc8. QITS_LINK_PROJECTS (comma-separated slugs) "
                        + "replaces the cache."},
        exitCodeListHeading = HelpText.EXIT_CODES,
        exitCodeList = {"0:Always, also when nothing was printed."})
public class WorkLinksCommand extends PlatformCommand {

    private static final ObjectMapper JSON = new ObjectMapper();

    @CommandLine.Option(names = "--landing-url", paramLabel = "<url>",
            description = "The landing app's base URL. Default: QITS_LANDING_URL, else the platform's bare domain.")
    String landingUrl;

    @CommandLine.Option(names = "--style", paramLabel = "<style>",
            description = "markdown ([qits-111](url)) or osc8 (a terminal hyperlink). Default: QITS_LINK_STYLE, "
                    + "else markdown.")
    String style;

    /** Rewrites the cache from the projects service. What the hook starts in the background. */
    @CommandLine.Option(names = "--refresh", hidden = true)
    boolean refresh;

    @Override
    protected int execute(CliContext context) throws CliFailure, InterruptedException {
        if (refresh) {
            return refresh(context);
        }
        if (context.stdinIsTerminal()) {
            // Typed bare, it would wait for an event nobody knows to give.
            context.err().println("Reads one MessageDisplay event on stdin. Claude runs this; see --help.");
            return 0;
        }
        try {
            String shown = show(context);
            if (shown != null) {
                ObjectNode output = JSON.createObjectNode();
                output.putObject("hookSpecificOutput").put("hookEventName", "MessageDisplay")
                        .put("displayContent", shown);
                context.out().println(output.toString());
            }
        } catch (Exception | LinkageError | StackOverflowError any) {
            // Never break the display: Claude shows the original text.
        }
        return 0;
    }

    /** The text to show, or null to leave the batch as it is. */
    String show(CliContext context) throws Exception {
        JsonNode event = JSON.readTree(context.in().readAllBytes());
        String delta = event == null ? "" : event.path("delta").asText("");
        if (delta.isEmpty()) {
            return null;
        }
        Map<String, String> env = context.env();
        String landing = PlatformUrls.landing(landingUrl, env, idpUrlWithoutNetwork(context));
        WorkLinks.Style chosen = WorkLinks.Style.of(style != null ? style : env.get("QITS_LINK_STYLE"));
        WorkLinks links = new WorkLinks(slugs(context), landing, chosen == null ? WorkLinks.Style.MARKDOWN : chosen);

        // A code fence can span batches, so whether one is open is kept per message between calls.
        String messageId = event.path("message_id").asText("").replaceAll("[^\\w-]", "");
        Path state = stateDirectory(env).resolve(messageId.isEmpty() ? "x" : messageId);
        boolean inFence = event.path("index").asInt(0) > 0 && Files.exists(state);
        WorkLinks.Result result = links.link(delta, inFence);
        if (result.inFence() && !event.path("final").asBoolean(false)) {
            Files.createDirectories(state.getParent());
            Files.writeString(state, "");
        } else {
            Files.deleteIfExists(state);
        }
        return result.text().equals(delta) ? null : result.text();
    }

    /**
     * The idp address the landing app is derived from, read without a refresh: a workstation's
     * session file as it is, even when its access token has expired. Null inside the platform.
     */
    private static String idpUrlWithoutNetwork(CliContext context) throws Exception {
        return switch (context.mode()) {
            case WORKSTATION -> context.sessionFile().read().map(Session::idpUrl).orElse(null);
            case EDGE_TOKEN -> context.idpUrl();
            case IN_PLATFORM -> null;
        };
    }

    private static List<String> slugs(CliContext context) {
        String told = context.env().get("QITS_LINK_PROJECTS");
        if (told != null && !told.isBlank()) {
            return Arrays.stream(told.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        }
        return LinkProjects.fromEnvironment(context.env(), context.clock()).read(WorkLinksCommand::startRefresh);
    }

    /** {@code $XDG_RUNTIME_DIR/qits-work-links}, else the cache directory's. */
    static Path stateDirectory(Map<String, String> env) {
        String runtime = env.get("XDG_RUNTIME_DIR");
        Path base = runtime != null && !runtime.isBlank() && Path.of(runtime).isAbsolute()
                ? Path.of(runtime)
                : LinkProjects.cacheDirectory(env);
        return base.resolve("qits-work-links");
    }

    /**
     * Starts this binary with {@code --refresh} and does not wait for it: in a session of its own
     * (setsid), so Claude ending the hook does not end it, with no stdin, stdout or stderr. A JVM is
     * not the binary (tests, the dev loop), so it starts nothing there.
     */
    static void startRefresh() {
        try {
            Path self = Path.of("/proc/self/exe").toRealPath();
            String name = self.getFileName().toString();
            if (name.equals("java") || name.startsWith("java.")) {
                return;
            }
            List<String> command = new ArrayList<>();
            for (String setsid : List.of("/usr/bin/setsid", "/bin/setsid")) {
                if (Files.isExecutable(Path.of(setsid))) {
                    command.add(setsid);
                    break;
                }
            }
            command.addAll(List.of(self.toString(), "agents", "claude", "hook", "work-links", "--refresh"));
            new ProcessBuilder(command)
                    .redirectInput(new File("/dev/null"))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (Exception | LinkageError cannot) {
            // No refresh this time; the touched cache tries again in an hour.
        }
    }

    private static int refresh(CliContext context) throws CliFailure, InterruptedException {
        JsonNode answer = ProjectsApi.connect(context, null).projects();
        List<String> slugs = ProjectsApi.entries(answer, "project").stream()
                .map(p -> ProjectsApi.text(p, "slug")).filter(s -> !s.isBlank()).toList();
        if (slugs.isEmpty()) {
            return 0;
        }
        try {
            LinkProjects.fromEnvironment(context.env(), context.clock()).write(slugs);
        } catch (java.io.IOException cannot) {
            throw new CliFailure("Cannot write the work-links cache: " + cannot.getMessage(), CliFailure.FAILED);
        }
        return 0;
    }
}
