package eu.wohlben.qits.cli.access.update;

import eu.wohlben.qits.cli.access.agents.LinkProjects;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.PlatformUrls;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code qits update}: the automatic update, on demand and in the foreground, saying what it did.
 * The hidden {@code --background} is the automatic run itself, which {@link AutoUpdate} starts
 * detached: it says nothing, and leaves a failure in {@code update.json} for the next command to
 * print.
 * <p>
 * {@code LOCAL}: it replaces the file on the caller's machine, so the MCP service does not serve it.
 */
@TuiCommand(interaction = Interaction.LOCAL)
@CommandLine.Command(name = "update", mixinStandardHelpOptions = true,
        description = {
                "Updates qits on this workstation to the newest released version, and says what it did.",
                "It asks qits-artifacts for the newest version with the session of `qits login`, downloads it "
                        + "next to the running binary, checks its length, its sha256 digest and that it answers "
                        + "`-V` with that version, and then puts it in place of the running binary in one rename. "
                        + "Any failure leaves the installed qits as it was.",
                "qits also does this by itself: a signed-in qits on a workstation checks at most once an hour, "
                        + "in the background, so no command waits for it. A failure there is one line on stderr "
                        + "of the next command. `qits -V` prints the version this binary is.",
                "Only on a workstation: inside the platform, in a workspace with QITS_TOKEN and in the MCP "
                        + "service the image decides which qits there is, and this refuses."},
        footerHeading = HelpText.EXAMPLES,
        footer = {
                "  qits update",
                "  qits update --check",
                "  QITS_AUTO_UPDATE=false qits projects list",
                "",
                "- QITS_AUTO_UPDATE=false (or 0, off, no) turns the automatic update off; `qits update` still works.",
                "- Where the binary's directory is not writable, the new version is downloaded and checked into "
                        + "$XDG_CACHE_HOME/qits/ (default ~/.cache/qits/), and the command to install it "
                        + "(`sudo install -m 755 ...`) is printed.",
                "- The store's address is https://registry.qits.<domain>, from the session's idp address; "
                        + "--artifacts-url or QITS_ARTIFACTS_URL sets it."},
        exitCodeListHeading = HelpText.EXIT_CODES,
        exitCodeList = {"0:Up to date, updated, or (with --check) a newer version reported.",
                "1:Not updated: the store refused or cannot be reached, a download failed its checks, the "
                        + "directory is not writable, or this build cannot update itself. The message says which.",
                "2:Used wrongly, not on a workstation, not signed in, or the session ended (run `qits login`)."})
public class UpdateCommand extends PlatformCommand {

    @CommandLine.Option(names = "--check",
            description = "Only say whether a newer version is released; download nothing.")
    boolean check;

    @CommandLine.Option(names = "--artifacts-url", paramLabel = "<url>",
            description = "qits-artifacts' base URL. Default: QITS_ARTIFACTS_URL, else from the session's idp address.")
    String artifactsUrl;

    /** The automatic run, started detached by {@link AutoUpdate}. Prints nothing. */
    @CommandLine.Option(names = "--background", hidden = true)
    boolean background;

    /** The running binary; a test hands its own. */
    Supplier<Optional<Path>> binary = AutoUpdate::nativeBinary;

    /** The running version; a test hands its own. */
    Supplier<Optional<String>> running = QitsVersion::current;

    /** How a downloaded binary is asked for its version; a test hands its own. */
    SelfUpdate.VersionProbe probe = SelfUpdate::probe;

    @Override
    protected int execute(CliContext context) throws CliFailure, InterruptedException {
        Optional<String> refusal = AutoUpdate.refusal(context.env());
        if (background) {
            return refusal.isPresent() ? 0 : background(context);
        }
        if (refusal.isPresent()) {
            throw new CliFailure(refusal.get(), CliFailure.USAGE);
        }
        SelfUpdate.Outcome outcome = selfUpdate(context).run(check, false);
        (outcome.done() ? context.out() : context.err()).println(outcome.message());
        return outcome.done() ? 0 : CliFailure.FAILED;
    }

    /**
     * The background run: no output, the outcome recorded in {@code update.json}. Only a failure a
     * person can act on is kept, as one line; this build not knowing its version, or not being the
     * binary, is no failure of theirs.
     */
    private int background(CliContext context) {
        Path directory = context.sessionFile().directory();
        String failure;
        try {
            SelfUpdate.Outcome outcome = selfUpdate(context).run(false, true);
            failure = outcome.result() == SelfUpdate.Result.NOT_WRITABLE ? outcome.message() : null;
        } catch (CliFailure failed) {
            failure = "qits could not update itself: " + firstLine(failed.getMessage())
                    + " Run `qits update` to try again.";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (RuntimeException unexpected) {
            failure = "qits could not update itself (" + unexpected.getClass().getSimpleName()
                    + "). Run `qits update` to try again.";
        }
        UpdateState state = UpdateState.read(directory).checked(context.clock().instant());
        (failure == null ? state.cleared() : state.failed(failure)).writeQuietly(directory);
        return 0;
    }

    SelfUpdate selfUpdate(CliContext context) throws CliFailure, InterruptedException {
        String origin = PlatformUrls.artifacts(artifactsUrl, context.env(), context.idpUrl());
        Releases releases = new ArtifactsReleases(new PlatformClient(context.credential()), origin);
        return new SelfUpdate(releases, running.get(), binary.get(),
                LinkProjects.cacheDirectory(context.env()), probe);
    }

    private static String firstLine(String message) {
        String line = message == null ? "" : message.lines().findFirst().orElse("").strip();
        return line.endsWith(".") ? line : line + ".";
    }
}
