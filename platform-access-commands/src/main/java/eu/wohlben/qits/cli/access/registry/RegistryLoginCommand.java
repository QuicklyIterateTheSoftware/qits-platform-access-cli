package eu.wohlben.qits.cli.access.registry;

import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.PlatformUrls;
import eu.wohlben.qits.cli.access.session.Session;
import eu.wohlben.qits.cli.access.session.SessionRefresh;
import eu.wohlben.qits.cli.access.session.Times;
import eu.wohlben.qits.cli.session.Mode;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@TuiCommand(interaction = Interaction.LOCAL)
@CommandLine.Command(name = "registry-login", mixinStandardHelpOptions = true,
        description = {
                "Give npm and Maven on this workstation a login for the platform's registries: the access token "
                        + "of `qits login`, in a block of ~/.npmrc and two of ~/.m2/settings.xml that the CLI owns.",
                "--configure sets it up: it asks the platform which npm registries there are, writes the blocks, "
                        + "and records the setup in $XDG_CONFIG_HOME/qits/registries.json. From then on "
                        + "`qits session-daemon` writes each new token into the blocks, and refreshes at least "
                        + "5 minutes before expiry. Without --configure it writes the current token once.",
                "Only the blocks change; the rest of each file stays as it is. Each file is replaced atomically "
                        + "with mode 0600. The Maven blocks are the mirror `qits-maven-host` for the repository "
                        + "`qits-maven`, and servers for both ids with an `Authorization: Bearer` header."},
        footerHeading = HelpText.EXAMPLES,
        footer = {
                "  qits registry-login --configure --remove-stale --dry-run",
                "  qits registry-login --configure --remove-stale",
                "  qits registry-login",
                "",
                "- --dry-run writes nothing. It prints a unified diff per file, with every token shown as <token>, "
                        + "or \"No changes\".",
                "- --remove-stale also drops lines for the old *.dev.localhost registries, and entries outside the "
                        + "blocks that use the same registries or ids. Without it they stay, with a warning.",
                "- A workstation command: inside the platform, or with QITS_TOKEN, the container has its own "
                        + "registry setup.",
                "- To stop: delete $XDG_CONFIG_HOME/qits/registries.json and the blocks."},
        exitCodeListHeading = HelpText.EXIT_CODES,
        exitCodeList = {"0:Done, or nothing to change.",
                "1:A file cannot be read or written, or the session cannot be refreshed.",
                "2:Used wrongly, not signed in, not set up, or not on a workstation."})
public class RegistryLoginCommand extends PlatformCommand {

    @CommandLine.Option(names = "--configure",
            description = "Set up the blocks (again), with the registries the platform lists now.")
    boolean configure;

    @CommandLine.Option(names = "--remove-stale",
            description = "Also drop *.dev.localhost registries and duplicates of the blocks' entries.")
    boolean removeStale;

    @CommandLine.Option(names = "--dry-run",
            description = "Write nothing; print the changes as a redacted unified diff.")
    boolean dryRun;

    @CommandLine.Option(names = "--npmrc", paramLabel = "<file>",
            description = "With --configure: the npm user config. Default: NPM_CONFIG_USERCONFIG, else ~/.npmrc.")
    Path npmrc;

    @CommandLine.Option(names = "--maven-settings", paramLabel = "<file>",
            description = "With --configure: the Maven user settings. Default: ~/.m2/settings.xml.")
    Path mavenSettings;

    @CommandLine.Option(names = "--artifacts-url", paramLabel = "<url>",
            description = "With --configure: qits-artifacts. Default: QITS_ARTIFACTS_URL, else "
                    + "https://registry.qits.<domain of the session's idp>.")
    String artifactsUrl;

    @CommandLine.Option(names = "--mirror-url", paramLabel = "<url>",
            description = "With --configure: qits-mirror. Default: QITS_MIRROR_URL, else "
                    + "https://mirror.qits.<domain of the session's idp>.")
    String mirrorUrl;

    @Override
    protected int execute(CliContext context) throws CliFailure, InterruptedException {
        if (context.mode() != Mode.WORKSTATION) {
            throw new CliFailure("qits registry-login is for a workstation. Inside the platform the container "
                    + "has its own registry setup.", CliFailure.USAGE);
        }
        if (!configure && (npmrc != null || mavenSettings != null || artifactsUrl != null || mirrorUrl != null)) {
            throw new CliFailure("--npmrc, --maven-settings, --artifacts-url and --mirror-url need --configure.",
                    CliFailure.USAGE);
        }
        RegistryLogins logins = new RegistryLogins(context.sessionFile().directory());

        // A one-off write should leave npm and Maven a token with time to run, as the daemon does.
        SessionRefresh.refreshIfDue(context.sessionFile(), RegistryLogins.MARGIN, context.clock(), context.idpFor());
        Session session = context.tokens().session();

        RegistryState state;
        try {
            if (configure) {
                Map<String, String> env = context.env();
                String idp = session.idpUrl();
                RegistryDiscovery.Found found = RegistryDiscovery.find(new PlatformClient(context.credential()),
                        PlatformUrls.artifacts(artifactsUrl, env, idp), PlatformUrls.mirror(mirrorUrl, env, idp),
                        context.err());
                state = new RegistryState(path(npmrc, defaultNpmrc(env)).toString(),
                        path(mavenSettings, home(env).resolve(".m2").resolve("settings.xml")).toString(),
                        found.npm(), found.mavenUrl());
            } else {
                Optional<RegistryState> saved = logins.state();
                if (saved.isEmpty()) {
                    throw new CliFailure("Registry logins are not set up — run `qits registry-login --configure`.",
                            CliFailure.USAGE);
                }
                state = saved.get();
            }

            List<RegistryLogins.FileChange> changes = logins.apply(state, session.accessToken(), removeStale, dryRun);
            changes.stream().flatMap(c -> c.warnings().stream()).distinct().forEach(context.err()::println);
            if (dryRun) {
                String diffs = String.join("", changes.stream().map(RegistryLogins.FileChange::diff).toList());
                context.out().print(diffs.isEmpty() ? "No changes\n" : diffs);
                return 0;
            }
            if (configure) {
                logins.saveState(state);
            }
            for (RegistryLogins.FileChange change : changes) {
                context.out().println((change.changed() ? "Updated " : "Up to date: ") + change.path());
            }
        } catch (IOException e) {
            throw new CliFailure("Cannot update the registry logins: " + e.getMessage(), CliFailure.FAILED);
        }
        context.out().println("npm registries: " + String.join(", ", state.npmRegistries()));
        context.out().println("Maven: " + state.mavenUrl());
        context.out().println("The token is good until " + Times.local(session.accessExpiresAt())
                + ". `qits session-daemon` renews it in the files; without it, run `qits registry-login` again.");
        return 0;
    }

    private static Path path(Path flag, Path fallback) {
        return (flag != null ? flag : fallback).toAbsolutePath().normalize();
    }

    static Path defaultNpmrc(Map<String, String> env) {
        for (String name : List.of("NPM_CONFIG_USERCONFIG", "npm_config_userconfig")) {
            String value = env.get(name);
            if (value != null && !value.isBlank()) {
                return Path.of(value.strip());
            }
        }
        return home(env).resolve(".npmrc");
    }

    static Path home(Map<String, String> env) {
        String home = env.get("HOME");
        return Path.of(home != null && !home.isBlank() ? home : System.getProperty("user.home"));
    }
}
