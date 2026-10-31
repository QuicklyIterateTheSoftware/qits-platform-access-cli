package eu.wohlben.qits.cli.access.deployments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.PlatformUrls;
import eu.wohlben.qits.cli.access.platform.StdinValue;
import eu.wohlben.qits.cli.access.platform.Table;
import eu.wohlben.qits.cli.access.projects.ProjectsApi;
import eu.wohlben.qits.cli.tui.api.Effect;
import eu.wohlben.qits.cli.tui.api.Input;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

/**
 * {@code qits deployments settings}: the values qits-deployments hands an application in an
 * environment when it deploys it (epic qits-765, qits-1390). A value is read on stdin, never from a
 * flag, and a secret's value is never printed: the service does not send it back, and {@code set}
 * does not print the one it sent.
 */
@CommandLine.Command(name = "settings", mixinStandardHelpOptions = true,
        subcommands = {SettingsCommand.ListCommand.class, SettingsCommand.SetCommand.class,
                SettingsCommand.DeleteCommand.class, SettingsCommand.PreviewCommand.class},
        description = {"The settings qits-deployments hands an application in an environment: one value per key, "
                + "plain or secret. A change reaches the application on its next deployment; `preview` shows "
                + "what that deployment would change.",
                "A secret's value is never shown: the service does not send it back, and the table prints "
                        + "******** in its place."},
        footerHeading = "%nNotes:%n",
        footer = {"- --output and --deployments-url may come before or after the command.",
                "- Reading (list, preview) takes qits:admin, qits:admin-agent, qits:desk-admin, qits:system or "
                        + "qits:agent. Writing (set, delete) takes qits:admin or qits:system."})
public class SettingsCommand implements Runnable {

    /** What a secret's value is shown as, wherever a value would be. */
    static final String MASK = "********";

    @CommandLine.Option(names = "--deployments-url", paramLabel = "<url>", scope = CommandLine.ScopeType.INHERIT,
            description = "The deployments service's base URL, without /deployments. Default: QITS_DEPLOYMENTS_URL, "
                    + "else the session's idp address with `idp` swapped for `deployments`.")
    String deploymentsUrl;

    @CommandLine.Option(names = {"-o", "--output"}, paramLabel = "table|json", scope = CommandLine.ScopeType.INHERIT,
            description = "table (the default): aligned columns. json: the service's answer, pretty-printed.")
    String output;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    DeploymentsApi deployments(CliContext context) throws CliFailure, InterruptedException {
        return new DeploymentsApi(new PlatformClient(context.credential()),
                PlatformUrls.deployments(deploymentsUrl, context.env(), context.idpUrl()));
    }

    boolean json() throws CliFailure {
        if (output == null || output.isBlank() || output.equalsIgnoreCase("table")) {
            return false;
        }
        if (output.equalsIgnoreCase("json")) {
            return true;
        }
        throw new CliFailure("--output is table or json, not '" + output + "'.", CliFailure.USAGE);
    }

    static String required(String value, String message) throws CliFailure {
        if (value == null || value.isBlank()) {
            throw new CliFailure(message, CliFailure.USAGE);
        }
        return value.strip();
    }

    /** A setting's value as a person reads it: the mask for a secret, and for a value the service left out. */
    static String shown(JsonNode value, boolean secret) {
        return secret || value == null || value.isNull() || value.isMissingNode() ? MASK : value.asText();
    }

    /** The settings table: APPLICATION, ENVIRONMENT, KEY, VALUE, UPDATED BY. */
    static void printSettings(PrintStream out, List<JsonNode> settings) {
        Table.print(out, "", List.of("APPLICATION", "ENVIRONMENT", "KEY", "VALUE", "UPDATED BY"),
                settings.stream().map(s -> List.of(
                        Table.cell(text(s, "application"), 32),
                        Table.cell(text(s, "environment"), 16),
                        Table.cell(text(s, "key"), 40),
                        Table.cell(shown(s.get("value"), s.path("secret").asBoolean(false)), 60),
                        Table.cell(text(s, "updatedBy"), 24))).toList());
    }

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = {"List an application's settings, of every environment or of the one --environment names.",
                    "A secret's value shows as ********; -o json gives the service's answer, where it is null."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits deployments settings list --application qits-ci",
                    "  qits deployments settings list --application qits-ci --environment dev -o json",
                    "",
                    "- Takes qits:admin, qits:admin-agent, qits:desk-admin, qits:system or qits:agent."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class ListCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        SettingsCommand parent;

        @CommandLine.Option(names = "--application", paramLabel = "<application>", required = true,
                description = "The application, as qits-deployments names it: qits-ci.")
        String application;

        @CommandLine.Option(names = "--environment", paramLabel = "<environment>",
                description = "Only this environment's settings: dev.")
        String environment;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = parent.json();
            String app = required(application, "Name the application: --application <application>.");
            JsonNode answer = parent.deployments(context).settings(app, environment);
            if (json) {
                ProjectsApi.printJson(context.out(), answer);
                return 0;
            }
            List<JsonNode> settings = new ArrayList<>();
            answer.path("settings").forEach(settings::add);
            if (settings.isEmpty()) {
                context.out().println("No settings for " + SafeText.line(app)
                        + (environment == null || environment.isBlank() ? "" : " in " + SafeText.line(environment.strip()))
                        + ".");
                return 0;
            }
            printSettings(context.out(), settings);
            return 0;
        }
    }

    @CommandLine.Command(name = "set", mixinStandardHelpOptions = true,
            description = {"Store one setting of an application in an environment. The value is read on stdin, "
                    + "never from a flag, so it stays out of the shell's history; one trailing line break is "
                    + "taken off.",
                    "--secret stores it as a secret: no answer sends its value back again. It prints the stored "
                            + "row with the value shown as ********, whether secret or not: the command never "
                            + "repeats what it was given."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  echo 'https://hooks.example.org' | qits deployments settings set --application qits-ci "
                    + "--environment dev --key WEBHOOK_URL",
                    "  qits deployments settings set --application qits-ci --environment dev --key API_TOKEN "
                            + "--secret < token.txt",
                    "",
                    "- A terminal on stdin, or nothing on it, is refused with exit 2 and nothing is sent.",
                    "- The new value reaches the application on its next deployment: `qits deployments settings "
                            + "preview` shows what that would change.",
                    "- Takes qits:admin or qits:system."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(input = Input.TEXT)
    public static class SetCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        SettingsCommand parent;

        @CommandLine.Option(names = "--application", paramLabel = "<application>", required = true,
                description = "The application, as qits-deployments names it: qits-ci.")
        String application;

        @CommandLine.Option(names = "--environment", paramLabel = "<environment>", required = true,
                description = "The environment: dev.")
        String environment;

        @CommandLine.Option(names = "--key", paramLabel = "<KEY>", required = true,
                description = "The setting's key: API_TOKEN.")
        String key;

        @CommandLine.Option(names = "--secret",
                description = "Store the value as a secret, never sent back by any answer.")
        boolean secret;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = parent.json();
            String app = required(application, "Name the application: --application <application>.");
            String env = required(environment, "Name the environment: --environment <environment>.");
            String name = required(key, "Name the key: --key <KEY>.");
            String value = StdinValue.read(context, "the setting's value",
                    "`... | qits deployments settings set --application " + app + " --environment " + env
                            + " --key " + name + "`");
            JsonNode stored = parent.deployments(context).putSetting(app, env, name, value, secret);
            if (stored instanceof ObjectNode row && row.has("value")) {
                // Never repeat a value, a plain one included: this command's output lands in transcripts.
                row.putNull("value");
            }
            if (json) {
                ProjectsApi.printJson(context.out(), stored);
                return 0;
            }
            if (stored == null || !stored.isObject()) {
                context.out().println("set " + SafeText.line(name));
                return 0;
            }
            printSettings(context.out(), List.of(stored));
            return 0;
        }
    }

    @CommandLine.Command(name = "delete", mixinStandardHelpOptions = true,
            description = {"Delete one setting of an application in an environment. The application loses it on "
                    + "its next deployment."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits deployments settings delete --application qits-ci --environment dev --key WEBHOOK_URL",
                    "",
                    "- 404 (exit 1) means no such setting is stored.",
                    "- Takes qits:admin or qits:system."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class DeleteCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        SettingsCommand parent;

        @CommandLine.Option(names = "--application", paramLabel = "<application>", required = true,
                description = "The application, as qits-deployments names it: qits-ci.")
        String application;

        @CommandLine.Option(names = "--environment", paramLabel = "<environment>", required = true,
                description = "The environment: dev.")
        String environment;

        @CommandLine.Option(names = "--key", paramLabel = "<KEY>", required = true,
                description = "The setting's key: WEBHOOK_URL.")
        String key;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            String app = required(application, "Name the application: --application <application>.");
            String env = required(environment, "Name the environment: --environment <environment>.");
            String name = required(key, "Name the key: --key <KEY>.");
            parent.deployments(context).deleteSetting(app, env, name);
            context.out().println("deleted");
            return 0;
        }
    }

    @CommandLine.Command(name = "preview", mixinStandardHelpOptions = true,
            description = {"Show what the next deployment of an application in an environment would change in its "
                    + "settings, against what the running deployment was given.",
                    "One line each: `+ KEY=value` added, `- KEY=value` removed, `~ KEY from -> to` changed, "
                            + "`= KEY=value` kept (with --include-extras), a secret's value as ********; then how many "
                            + "are unchanged."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits deployments settings preview --application qits-ci --environment dev",
                    "  qits deployments settings preview --application qits-ci --environment dev --include-extras",
                    "",
                    "- Takes qits:admin, qits:admin-agent, qits:desk-admin, qits:system or qits:agent."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class PreviewCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        SettingsCommand parent;

        @CommandLine.Option(names = "--application", paramLabel = "<application>", required = true,
                description = "The application, as qits-deployments names it: qits-ci.")
        String application;

        @CommandLine.Option(names = "--environment", paramLabel = "<environment>", required = true,
                description = "The environment: dev.")
        String environment;

        @CommandLine.Option(names = "--include-extras",
                description = "Also list the values the running deployment has that no setting holds, which the "
                        + "next deployment keeps (`= KEY=value`).")
        boolean includeExtras;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = parent.json();
            String app = required(application, "Name the application: --application <application>.");
            String env = required(environment, "Name the environment: --environment <environment>.");
            JsonNode preview = parent.deployments(context).previewSettings(app, env, includeExtras);
            if (json) {
                ProjectsApi.printJson(context.out(), preview);
                return 0;
            }
            printPreview(context.out(), preview);
            return 0;
        }
    }

    /**
     * {@code + KEY=value}, {@code - KEY=value}, {@code ~ KEY from -> to}, {@code = KEY=value} for each
     * kept one (only there when asked for), then the unchanged count.
     */
    static void printPreview(PrintStream out, JsonNode preview) {
        for (JsonNode added : preview.path("added")) {
            out.println("+ " + SafeText.line(text(added, "key")) + "="
                    + SafeText.line(shown(added.get("value"), added.path("secret").asBoolean(false))));
        }
        for (JsonNode removed : preview.path("removed")) {
            out.println("- " + SafeText.line(text(removed, "key")) + "="
                    + SafeText.line(shown(removed.get("value"), removed.path("secret").asBoolean(false))));
        }
        for (JsonNode changed : preview.path("changed")) {
            boolean secret = changed.path("secret").asBoolean(false);
            out.println("~ " + SafeText.line(text(changed, "key")) + " "
                    + SafeText.line(shown(changed.get("from"), secret)) + " -> "
                    + SafeText.line(shown(changed.get("to"), secret)));
        }
        for (JsonNode kept : preview.path("kept")) {
            out.println("= " + SafeText.line(text(kept, "key")) + "="
                    + SafeText.line(shown(kept.get("value"), kept.path("secret").asBoolean(false))));
        }
        int unchanged = preview.path("unchanged").asInt(0);
        out.println(unchanged + " unchanged.");
    }
}
