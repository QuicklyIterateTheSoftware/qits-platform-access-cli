package eu.wohlben.qits.cli.access.checkout;

import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.PlatformUrls;
import eu.wohlben.qits.cli.session.Credential;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

@TuiCommand(interaction = Interaction.STREAMING)
@CommandLine.Command(name = "checkout-daemon", mixinStandardHelpOptions = true,
        description = {
                "Hold a local checkout at what a repository released, and keep it there. It follows the releases, "
                        + "never the tips of the branches: the root ends detached at the release tag, and every "
                        + "submodule detached at the gitlink that release recorded. That tree is the estate somebody "
                        + "reviewed and released, which is not the same thing as `latest`.",
                "It brings the checkout to the newest release at the start, then waits for SCMRelease on the live "
                        + "event stream. After every connect it reads the newest release back, so a release cut while "
                        + "it was stopped or reconnecting is not missed. Notes go to stderr, and every release it "
                        + "moves the checkout to is one line on stdout. Stops on SIGINT or SIGTERM."},
        footerHeading = HelpText.EXAMPLES,
        footer = {
                "  qits checkout-daemon --path /workspace",
                "  qits checkout-daemon --path /workspace --once",
                "  qits checkout-daemon --path /srv/qits --repository qits-qits --no-submodules",
                "",
                "- A checkout with local changes is never touched: it is somebody's work. It says so and keeps "
                        + "watching; with --once that is exit code 1. It never stashes, resets or merges.",
                "- Git authentication stays the credential helper's: run `qits git-login` on a workstation, and in a "
                        + "container the injected host (QITS_GIT_AUTH_HOST) answers. No token is ever put in a URL.",
                "- --repository is needed when the origin addresses the repository by its id (/git/<repository id>), "
                        + "which is the git host's internal storage scheme and names nothing."},
        exitCodeListHeading = HelpText.EXIT_CODES,
        exitCodeList = {"0:Stopped by SIGINT or SIGTERM, --once is done, or stdout was closed.",
                "1:The platform refused the stream (401, 403 or another 4xx), or with --once the checkout has local "
                        + "changes or a git command failed.",
                "2:Used wrongly (the origin names no repository and --repository was not given), Git has no "
                        + "credential for the origin's host, not signed in, or the session ended."})
public class CheckoutDaemonCommand extends PlatformCommand {

    @CommandLine.Option(names = "--path", paramLabel = "<dir>", defaultValue = ".",
            description = "The checkout to hold. Default: the directory the command runs in.")
    String path;

    @CommandLine.Option(names = "--repository", paramLabel = "<name>",
            description = "Whose releases to follow. Default: the repository the checkout's origin names.")
    String repository;

    @CommandLine.Option(names = "--once",
            description = "Bring the checkout to the newest release and exit; do not open the stream.")
    boolean once;

    @CommandLine.Option(names = "--submodules", negatable = true, defaultValue = "true",
            description = "Hold the submodules at the gitlinks the release recorded. --no-submodules holds the root "
                    + "alone. Default: ${DEFAULT-VALUE}.")
    boolean submodules;

    @CommandLine.Option(names = "--events-url", paramLabel = "<url>",
            description = "The events service's base URL, without /events. Default: QITS_EVENTS_URL, else the "
                    + "session's idp address with `idp` swapped for `events`.")
    String eventsUrl;

    @Override
    protected int execute(CliContext context) throws CliFailure, InterruptedException {
        Path dir = Path.of(path == null || path.isBlank() ? "." : path.strip());
        Notes notes = new Notes(context.err(), context.clock());
        Checkout.Origin origin = Checkout.origin(dir, context.env(), repository);
        // Both of these refuse now rather than at the first release, which can be days away.
        Checkout.requireCredentialHelper(dir, context.env(), origin, context.mode().inPlatform());
        Credential credential = context.credential();
        credential.bearer();

        Checkout checkout = new Checkout(dir, context.env(), origin, submodules, notes);
        String base = PlatformUrls.events(eventsUrl, context.env(), context.idpUrl());
        URI stream;
        URI latest;
        try {
            stream = URI.create(base + "/events/api/stream?names=" + ReleaseWatcher.EVENT);
            latest = URI.create(base + "/events/api/events?" + query(origin));
        } catch (IllegalArgumentException e) {
            throw new CliFailure("'" + base + "' is not a usable events address.", CliFailure.USAGE);
        }
        ReleaseWatcher watcher = new ReleaseWatcher(new PlatformClient(credential), stream, latest, origin.repoName(),
                origin.projectId(), checkout::hold, context.out(), notes, context.sleeper(), ReleaseWatcher.IDLE_LIMIT);
        if (once) {
            try {
                return watcher.once();
            } catch (CliFailure refused) {
                // The same refusal the watch writes as a note, so one run and the other read alike.
                notes.accept(refused.getMessage());
                return refused.exitCode();
            }
        }
        context.onStop().accept(watcher::stop);
        return watcher.run();
    }

    /**
     * The newest release of this repository. An {@code attr} filter is a {@code key=value} the
     * service lower-cases and matches as the literal fragment {@code "key":"value"} against the
     * payload, so the keys are written lower-case here and only the values are encoded.
     */
    private static String query(Checkout.Origin origin) {
        StringBuilder query = new StringBuilder("name=").append(ReleaseWatcher.EVENT)
                .append("&attr=repositoryname=").append(encoded(origin.repoName()));
        if (origin.projectId() != null) {
            query.append("&attr=projectid=").append(encoded(origin.projectId()));
        }
        return query.append("&order=desc&limit=1").toString();
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
