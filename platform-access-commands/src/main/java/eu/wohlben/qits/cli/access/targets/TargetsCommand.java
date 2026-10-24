package eu.wohlben.qits.cli.access.targets;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.PlatformUrls;
import eu.wohlben.qits.cli.access.platform.Table;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

/**
 * {@code qits targets}: execution targets and their leases, qits-system-service's {@code
 * /system/api} (epic qits-1481, dossier "Targets in qits-system: data, doors, allocation"). One
 * command per door; this replaces the old per-slot-type runner groups ({@code qits workspaces
 * runners}, retired by qits-1534).
 * <p>
 * {@code create} and {@code rotate-token} answer a one-time registration token inside an install
 * line (qits-1574). They print that line alone on stdout, once, and never on stderr, in a log or in
 * an error, the way {@code users invite} prints its register token. Both are {@link
 * Interaction#LOCAL}, so the MCP service does not serve them and the line never lands in an agent's
 * transcript.
 */
@CommandLine.Command(name = "targets", mixinStandardHelpOptions = true,
        subcommands = {TargetsCommand.ListCommand.class, TargetsCommand.ShowCommand.class,
                TargetsCommand.CreateCommand.class, TargetsCommand.RotateTokenCommand.class,
                TargetsCommand.UpdateCommand.class, TargetsCommand.GreenlightCommand.class,
                TargetsCommand.HealthcheckCommand.class, TargetsCommand.LoginCheckCommand.class,
                TargetsCommand.DeleteCommand.class, TargetsCommand.LeasesCommand.class,
                TargetsCommand.SignInCommand.class},
        description = {"The execution targets qits-system-runner runs on, and their leases: list them with their "
                + "connection, slots, login and quarantine; read one in full; declare one, or rotate its registration "
                + "token, and print the line that installs its runner; change its slot minimums or description; "
                + "lift a quarantine; ask for a health check or a login check; delete one; list the leases a "
                + "target or a slot type holds; and sign a target in.",
                "A target is named by its name or its id (`qits targets list` shows both)."},
        footerHeading = "%nNotes:%n",
        footer = {"- Reading, and asking for a health check, are open to qits:agent. Declaring, rotating a "
                + "registration token, updating, deleting and asking for a login check need qits:admin, "
                + "qits:admin-agent or (for all but login-check) qits:system; qits:agent is refused (HTTP 403) by "
                + "design. Each command's help names its door.",
                "- create and rotate-token print an install line that carries a one-time token: alone on stdout, "
                        + "shown once, and not served by the qits MCP server.",
                "- --system-url and --output may come before or after the command."})
public class TargetsCommand implements Runnable {

    private static final Pattern UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @CommandLine.Option(names = "--system-url", paramLabel = "<url>", scope = CommandLine.ScopeType.INHERIT,
            description = "qits-system-service's base URL, without /system. Default: QITS_SYSTEM_URL, else derived "
                    + "from the idp address, like the other services.")
    String systemUrl;

    @CommandLine.Option(names = {"-o", "--output"}, paramLabel = "table|json", scope = CommandLine.ScopeType.INHERIT,
            description = "table (the default): for a person. json: the service's answer, pretty-printed, with "
                    + "control characters written as escapes.")
    String output;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    TargetsApi api(CliContext context) throws CliFailure, InterruptedException {
        return new TargetsApi(new PlatformClient(context.credential()),
                PlatformUrls.system(systemUrl, context.env(), context.idpUrl()));
    }

    boolean json() throws CliFailure {
        return Json.wanted(output);
    }

    static boolean isUuid(String value) {
        return value != null && UUID.matcher(value.strip()).matches();
    }

    /** {@code --target}: a uuid as it is, else a name looked up in {@code listTargets}. */
    String targetId(CliContext context, String given) throws CliFailure, InterruptedException {
        String wanted = given == null ? "" : given.strip();
        if (wanted.isEmpty()) {
            throw new CliFailure("Name the target: its name or id (`qits targets list` shows it).", CliFailure.USAGE);
        }
        if (isUuid(wanted)) {
            return wanted;
        }
        for (JsonNode target : api(context).targets()) {
            if (wanted.equals(text(target, "name"))) {
                return text(target, "id");
            }
        }
        throw new CliFailure("No target named '" + wanted + "'.", CliFailure.USAGE);
    }

    /** {@code claude} or {@code kimi}, case-insensitive; default {@code claude}. */
    static String harness(String given) throws CliFailure {
        String value = (given == null || given.isBlank() ? "claude" : given.strip()).toLowerCase(Locale.ROOT);
        if (!value.equals("claude") && !value.equals("kimi")) {
            throw new CliFailure("--harness must be claude or kimi, not '" + given.strip() + "'.", CliFailure.USAGE);
        }
        return value;
    }

    /** {@code -o}: the shared reading of table or json, and the one JSON printer. */
    static final class Json {

        private static final ObjectMapper PLAIN = new ObjectMapper();

        private Json() {
        }

        static boolean wanted(String output) throws CliFailure {
            if (output == null || output.equals("table")) {
                return false;
            }
            if (output.equals("json")) {
                return true;
            }
            throw new CliFailure("--output must be table or json, not '" + output + "'.", CliFailure.USAGE);
        }

        static void print(PrintStream out, JsonNode node) throws CliFailure {
            try {
                out.println(SafeText.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node));
            } catch (JsonProcessingException impossible) {
                throw new CliFailure("cannot print the answer as JSON", CliFailure.FAILED);
            }
        }

        static JsonNode parse(String text) {
            try {
                JsonNode node = PLAIN.readTree(text == null ? "" : text);
                return node == null || node.isMissingNode() ? null : node;
            } catch (IOException notJson) {
                return null;
            }
        }
    }

    /** A cell from a field: one line, at most {@code max} characters, a dash for nothing. */
    static String cell(JsonNode node, String field, int max) {
        return Table.cell(text(node, field), max);
    }

    static String yesNo(JsonNode node, String field) {
        return node.path(field).asBoolean(false) ? "yes" : "no";
    }

    /** {@code ci a/min, ws b/min, desk c/min, total u/slots}, as the list column. */
    static String slots(JsonNode target) {
        JsonNode min = target.path("minimums");
        JsonNode used = target.path("used");
        int ci = used.path("ci").asInt(0);
        int ws = used.path("workspace").asInt(0);
        int desk = used.path("desk").asInt(0);
        return "ci " + ci + "/" + min.path("ci").asInt(0)
                + ", ws " + ws + "/" + min.path("workspace").asInt(0)
                + ", desk " + desk + "/" + min.path("desk").asInt(0)
                + ", total " + (ci + ws + desk) + "/" + target.path("slotsTotal").asInt(0);
    }

    /** {@code claude:<state> kimi:<state>}, as the list column. */
    static String login(JsonNode target) {
        return "claude:" + loginValue(target, "claude") + " kimi:" + loginValue(target, "kimi");
    }

    /** One harness's login value off a target DTO: {@code PRESENT}, {@code ABSENT}, {@code UNKNOWN} or {@code -}. */
    static String loginValue(JsonNode target, String harness) {
        JsonNode value = target.path("login").path(harness);
        return value.isTextual() ? value.asText() : "-";
    }

    /** A target's fields, for a person; the strip `show` prints. */
    static void printTarget(PrintStream out, JsonNode target) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("id", cell(target, "id", 60)));
        rows.add(List.of("name", cell(target, "name", 60)));
        rows.add(List.of("description", cell(target, "description", 200)));
        rows.add(List.of("connected", yesNo(target, "connected")
                + (text(target, "connectedSince").isEmpty() ? "" : " since " + Table.time(text(target, "connectedSince")))));
        rows.add(List.of("runner version", cell(target, "runnerVersion", 60)));
        rows.add(List.of("slots", slots(target)));
        rows.add(List.of("credential volume", cell(target, "credentialVolume", 120)));
        rows.add(List.of("login claude", loginValue(target, "claude")));
        rows.add(List.of("login kimi", loginValue(target, "kimi")));
        if (!text(target, "loginCheckedAt").isEmpty()) {
            rows.add(List.of("login checked", Table.time(text(target.path("login"), "checkedAt"))));
        }
        rows.add(List.of("login command", cell(target, "loginCommand", 200)));
        rows.add(List.of("kimi login command", cell(target, "kimiLoginCommand", 200)));
        rows.add(List.of("quarantined", yesNo(target, "quarantined")
                + (text(target, "quarantineReason").isEmpty() ? "" : ": " + cell(target, "quarantineReason", 160))));
        rows.add(List.of("registered", yesNo(target, "registered")
                + (text(target, "registeredAt").isEmpty() ? "" : " at " + Table.time(text(target, "registeredAt")))));
        rows.add(List.of("last seen", Table.time(text(target, "lastSeenAt"))));
        rows.add(List.of("last health check", text(target, "lastHealthCheckAt").isEmpty() ? "never"
                : Table.time(text(target, "lastHealthCheckAt")) + " ("
                + (target.path("lastHealthCheckOk").asBoolean(false) ? "ok" : "failed") + ")"));
        rows.add(List.of("created", Table.time(text(target, "createdAt"))));
        Table.print(out, "  ", null, rows);
    }

    // --- list ---

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = {"Every target, by name: connected or not, version, slots (ci, workspace and desk, used "
                    + "over their minimum, and the total used over the total), login and quarantine.",
                    "GET /system/api/targets. " + Doors.READ},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits targets list", "  qits targets list -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class ListCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        TargetsCommand targets;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = targets.json();
            JsonNode answer = targets.api(context).targets();
            if (json) {
                Json.print(context.out(), answer);
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode target : answer) {
                rows.add(List.of(cell(target, "name", 30), yesNo(target, "connected"), cell(target, "runnerVersion", 20),
                        slots(target), login(target), yesNo(target, "quarantined")));
            }
            if (rows.isEmpty()) {
                context.out().println("No targets.");
                return 0;
            }
            Table.print(context.out(), "", List.of("NAME", "CONNECTED", "VERSION", "SLOTS", "LOGIN", "QUARANTINED"),
                    rows);
            return 0;
        }
    }

    // --- show ---

    @CommandLine.Command(name = "show", mixinStandardHelpOptions = true,
            description = {"One target in full: its slots and minimums, its login state for claude and kimi, its "
                    + "login command for each (the docker run that signs its credential volume in), its "
                    + "quarantine, and its newest connection.",
                    "GET /system/api/targets/{id}. " + Doors.READ},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits targets show --target localhost", "  qits targets show --target localhost -o json",
                    "", "- loginCommand and kimiLoginCommand are null until a login state has arrived on the "
                            + "target's current connection: `qits targets sign-in` names that."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class ShowCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        TargetsCommand targets;

        @CommandLine.Option(names = "--target", paramLabel = "<name|id>", required = true,
                description = "The target's name or id.")
        String target;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = targets.json();
            String id = targets.targetId(context, target);
            JsonNode answer = targets.api(context).target(id);
            if (json) {
                Json.print(context.out(), answer);
                return 0;
            }
            printTarget(context.out(), answer);
            return 0;
        }
    }

    // --- create and rotate-token ---

    /**
     * The answer of createTarget or rotateTargetRegistrationToken: the install line alone on stdout,
     * and on stderr which target it is for, by name and id. The line carries the registration
     * token, so it is printed nowhere else, and no message here quotes the answer.
     */
    static void printInstallLine(CliContext context, boolean json, JsonNode answer, String what, String given)
            throws CliFailure {
        JsonNode target = answer == null ? JsonNodeFactory.instance.objectNode() : answer.path("target");
        String name = text(target, "name").isEmpty() ? given : text(target, "name");
        String id = text(target, "id");
        String named = SafeText.line(name) + (id.isEmpty() ? "" : " (" + SafeText.line(id) + ")");
        String installLine = answer == null ? "" : text(answer, "installLine");
        if (installLine.isBlank()) {
            throw new CliFailure("The service " + what + " target " + named + ", but its answer carries no install "
                    + "line.", CliFailure.FAILED);
        }
        if (json) {
            Json.print(context.out(), answer);
            return;
        }
        context.out().println(SafeText.line(installLine));
        context.err().println("Install line for target " + named + ": shown only this once. Run it on the "
                + "target's own machine; it installs the runner, which registers with it once. Lost, `qits targets "
                + "rotate-token --target " + SafeText.line(name) + "` makes a new one.");
    }

    @CommandLine.Command(name = "create", mixinStandardHelpOptions = true,
            description = {"Declare a target and print the line that installs its runner, with a one-time "
                    + "registration token in it.",
                    "POST /system/api/targets with {name, description?, minimums?: {ci, workspace, desk}} (HTTP 201 "
                            + "{target, registrationToken, installLine}). " + Doors.WRITE,
                    "THE INSTALL LINE IS SHOWN ONLY THIS ONCE: it is printed alone on stdout and nowhere else. Run "
                            + "it on the target's own machine. Lost, `qits targets rotate-token` makes a new one."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits targets create --name buildbox",
                    "  qits targets create --name buildbox --description 'The build machine under the desk' "
                            + "--min-ci 2 --min-workspace 2 --min-desk 1",
                    "",
                    "- A minimum left out is not sent. HTTP 409 TARGET_NAME_TAKEN: the name is in use. HTTP 503: "
                            + "commissioning is off, or the runner plane is not configured or pins no runner version.",
                    "- -o json prints the service's answer: the target, the registration token and the install line.",
                    "- Not served by the qits MCP server: the token would land in a transcript."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(interaction = Interaction.LOCAL)
    public static class CreateCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        TargetsCommand targets;

        @CommandLine.Option(names = "--name", paramLabel = "<name>", required = true,
                description = "The new target's name.")
        String name;

        @CommandLine.Option(names = "--description", paramLabel = "<text>", description = "Free text.")
        String description;

        @CommandLine.Option(names = "--min-ci", paramLabel = "<n>", description = "Slots reserved for CI.")
        Integer minCi;

        @CommandLine.Option(names = "--min-workspace", paramLabel = "<n>",
                description = "Slots reserved for workspaces.")
        Integer minWorkspace;

        @CommandLine.Option(names = "--min-desk", paramLabel = "<n>", description = "Slots reserved for the desk.")
        Integer minDesk;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = targets.json();
            String wanted = name == null ? "" : name.strip();
            if (wanted.isEmpty()) {
                throw new CliFailure("Name the target: --name <name>.", CliFailure.USAGE);
            }
            ObjectNode body = JsonNodeFactory.instance.objectNode();
            body.put("name", wanted);
            if (description != null) {
                body.put("description", description);
            }
            ObjectNode minimums = JsonNodeFactory.instance.objectNode();
            if (minCi != null) {
                minimums.put("ci", minCi);
            }
            if (minWorkspace != null) {
                minimums.put("workspace", minWorkspace);
            }
            if (minDesk != null) {
                minimums.put("desk", minDesk);
            }
            if (!minimums.isEmpty()) {
                body.set("minimums", minimums);
            }
            JsonNode answer = targets.api(context).createTarget(body);
            printInstallLine(context, json, answer, "declared", wanted);
            return 0;
        }
    }

    @CommandLine.Command(name = "rotate-token", mixinStandardHelpOptions = true,
            description = {"Mint a new registration token for a target whose runner has not registered yet, and "
                    + "print the line that installs its runner with it.",
                    "POST /system/api/targets/{id}/registration-token, no body (HTTP 200 {target, registrationToken, "
                            + "installLine}). " + Doors.WRITE,
                    "THE INSTALL LINE IS SHOWN ONLY THIS ONCE: it is printed alone on stdout and nowhere else. Run "
                            + "it on the target's own machine."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits targets rotate-token --target buildbox",
                    "  qits targets rotate-token --target buildbox -o json",
                    "",
                    "- HTTP 409 ALREADY_REGISTERED: the target's runner has registered and needs no token. HTTP 503: "
                            + "commissioning is off, or the runner plane is not configured or pins no runner version.",
                    "- -o json prints the service's answer: the target, the registration token and the install line.",
                    "- Not served by the qits MCP server: the token would land in a transcript."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(interaction = Interaction.LOCAL)
    public static class RotateTokenCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        TargetsCommand targets;

        @CommandLine.Option(names = "--target", paramLabel = "<name|id>", required = true,
                description = "The target's name or id.")
        String target;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = targets.json();
            String id = targets.targetId(context, target);
            JsonNode answer = targets.api(context).rotateTargetRegistrationToken(id);
            printInstallLine(context, json, answer, "rotated the registration token of", target.strip());
            return 0;
        }
    }

    // --- update ---

    @CommandLine.Command(name = "update", mixinStandardHelpOptions = true,
            description = {"Change a target's description or slot minimums. Only what is given is sent; a "
                    + "minimum left out keeps its value.",
                    "PATCH /system/api/targets/{id} with {description?, minimums?: {ci?, workspace?, desk?}}. "
                            + Doors.WRITE},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits targets update --target localhost --min-ci 2 --min-workspace 2 --min-desk 1",
                    "  qits targets update --target localhost --description 'The build machine under the desk'",
                    "",
                    "- A blank --description clears it. HTTP 409 MINIMUMS_EXCEED_TOTAL: their sum is above the "
                            + "target's slots total."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class UpdateCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        TargetsCommand targets;

        @CommandLine.Option(names = "--target", paramLabel = "<name|id>", required = true,
                description = "The target's name or id.")
        String target;

        @CommandLine.Option(names = "--min-ci", paramLabel = "<n>", description = "Slots reserved for CI.")
        Integer minCi;

        @CommandLine.Option(names = "--min-workspace", paramLabel = "<n>",
                description = "Slots reserved for workspaces.")
        Integer minWorkspace;

        @CommandLine.Option(names = "--min-desk", paramLabel = "<n>", description = "Slots reserved for the desk.")
        Integer minDesk;

        @CommandLine.Option(names = "--description", paramLabel = "<text>", description = "Free text.")
        String description;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = targets.json();
            String id = targets.targetId(context, target);
            ObjectNode change = JsonNodeFactory.instance.objectNode();
            if (description != null) {
                change.put("description", description);
            }
            ObjectNode minimums = JsonNodeFactory.instance.objectNode();
            if (minCi != null) {
                minimums.put("ci", minCi);
            }
            if (minWorkspace != null) {
                minimums.put("workspace", minWorkspace);
            }
            if (minDesk != null) {
                minimums.put("desk", minDesk);
            }
            if (!minimums.isEmpty()) {
                change.set("minimums", minimums);
            }
            if (change.isEmpty()) {
                throw new CliFailure("Nothing to change: give --min-ci, --min-workspace, --min-desk or "
                        + "--description.", CliFailure.USAGE);
            }
            JsonNode answer = targets.api(context).patchTarget(id, change);
            if (json) {
                Json.print(context.out(), answer);
                return 0;
            }
            context.out().println("Target " + SafeText.line(text(answer, "name")) + ": " + slots(answer) + ", "
                    + cell(answer, "description", 100) + ".");
            return 0;
        }
    }

    /** greenlight, healthcheck and login-check: one POST each, no body, as a person's buttons send it. */
    abstract static class TargetVerb extends PlatformCommand {

        @CommandLine.ParentCommand
        TargetsCommand targets;

        @CommandLine.Option(names = "--target", paramLabel = "<name|id>", required = true,
                description = "The target's name or id.")
        String target;

        abstract JsonNode call(TargetsApi api, String id) throws CliFailure, InterruptedException;

        abstract String done(String name, JsonNode answer);

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = targets.json();
            String id = targets.targetId(context, target);
            JsonNode answer = call(targets.api(context), id);
            if (json) {
                if (answer != null && !answer.isNull()) {
                    Json.print(context.out(), answer);
                }
                return 0;
            }
            context.out().println(done(SafeText.line(targetName(target)), answer));
            return 0;
        }

        /** The name as given, for the message; `--target` by id still reads back as the id it was given. */
        private static String targetName(String given) {
            return given == null ? "" : given.strip();
        }
    }

    @CommandLine.Command(name = "greenlight", mixinStandardHelpOptions = true,
            description = {"Lift a target's quarantine. The service quarantines a target itself, after a failed "
                    + "health check, or when it registers.",
                    "POST /system/api/targets/{id}/greenlight, no body. " + Doors.ADMIN_ONLY},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits targets greenlight --target localhost"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class GreenlightCommand extends TargetVerb {
        @Override
        JsonNode call(TargetsApi api, String id) throws CliFailure, InterruptedException {
            return api.greenlightTarget(id);
        }

        @Override
        String done(String name, JsonNode answer) {
            return "Target " + name + (answer != null && answer.path("quarantined").asBoolean(false)
                    ? " is still quarantined." : " is out of quarantine.");
        }
    }

    @CommandLine.Command(name = "healthcheck", mixinStandardHelpOptions = true,
            description = {"Ask a connected target for a health check now. The result arrives with a later "
                    + "`qits targets show`.",
                    "POST /system/api/targets/{id}/healthcheck, no body (HTTP 202 {requestId}). " + Doors.READ},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits targets healthcheck --target localhost",
                    "", "- A disconnected target is refused: HTTP 409 TARGET_UNAVAILABLE."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class HealthcheckCommand extends TargetVerb {
        @Override
        JsonNode call(TargetsApi api, String id) throws CliFailure, InterruptedException {
            return api.requestTargetHealthCheck(id);
        }

        @Override
        String done(String name, JsonNode answer) {
            return "Queued a health check on target " + name + ".";
        }
    }

    @CommandLine.Command(name = "login-check", mixinStandardHelpOptions = true,
            description = {"Ask a connected target to probe its credential volume's sign-in again. The result "
                    + "shows in a later `qits targets show` or `qits targets list`.",
                    "POST /system/api/targets/{id}/login-check, no body (HTTP 202). " + Doors.ADMIN_ONLY},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits targets login-check --target localhost",
                    "", "- A disconnected target is refused: HTTP 409 TARGET_UNAVAILABLE.",
                    "- `qits targets sign-in` sends this itself, every 30 seconds, until the target reports PRESENT."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class LoginCheckCommand extends TargetVerb {
        @Override
        JsonNode call(TargetsApi api, String id) throws CliFailure, InterruptedException {
            return api.requestTargetLoginCheck(id);
        }

        @Override
        String done(String name, JsonNode answer) {
            return "Asked target " + name + " to check its credential volume's sign-in.";
        }
    }

    @CommandLine.Command(name = "delete", mixinStandardHelpOptions = true,
            description = {"Decommission a target: retire its runner and give its credentials back.",
                    "DELETE /system/api/targets/{id}. " + Doors.WRITE},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits targets delete --target localhost",
                    "", "- A target that still holds a lease that has not ended is refused: HTTP 409 TARGET_BUSY."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class DeleteCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        TargetsCommand targets;

        @CommandLine.Option(names = "--target", paramLabel = "<name|id>", required = true,
                description = "The target's name or id.")
        String target;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = targets.json();
            String id = targets.targetId(context, target);
            targets.api(context).deleteTarget(id);
            if (!json) {
                context.out().println("Deleted target " + SafeText.line(target.strip()) + ".");
            }
            return 0;
        }
    }

    // --- leases ---

    @CommandLine.Command(name = "leases", mixinStandardHelpOptions = true,
            description = {"Leases, oldest first: filter by target, slot type (ci, workspace or desk) or state.",
                    "GET /system/api/leases?targetId=&slotType=&state=. " + Doors.LEASES},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits targets leases", "  qits targets leases --target localhost",
                    "  qits targets leases --type workspace --state RUNNING"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class LeasesCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        TargetsCommand targets;

        @CommandLine.Option(names = "--target", paramLabel = "<name|id>", description = "Only this target's leases.")
        String target;

        @CommandLine.Option(names = "--type", paramLabel = "ci|workspace|desk", description = "Only this slot type.")
        String type;

        @CommandLine.Option(names = "--state", paramLabel = "<state>",
                description = "Only this state: QUEUED, GRANTED, RUNNING, EXITED, FAILED, ENDING or ENDED.")
        String state;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = targets.json();
            String targetId = target == null || target.isBlank() ? null : targets.targetId(context, target);
            JsonNode answer = targets.api(context).leases(targetId, type, state);
            if (json) {
                Json.print(context.out(), answer);
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode lease : answer) {
                rows.add(List.of(cell(lease, "id", 36), cell(lease, "slotType", 10),
                        cell(lease, "ownerApp", 16) + "/" + cell(lease, "ownerRef", 30), cell(lease, "state", 10),
                        cell(lease, "detail", 60), Table.time(text(lease, "requestedAt"))));
            }
            if (rows.isEmpty()) {
                context.out().println("No leases.");
                return 0;
            }
            Table.print(context.out(), "", List.of("ID", "TYPE", "OWNER", "STATE", "DETAIL", "REQUESTED"), rows);
            return 0;
        }
    }

    // --- sign-in ---

    @CommandLine.Command(name = "sign-in", mixinStandardHelpOptions = true,
            description = {"Sign a target's credential volume in for a harness: prints the docker command an "
                    + "operator runs on the target's machine, then asks the target to check its sign-in every 30 "
                    + "seconds until it reports PRESENT, or the timeout passes.",
                    "GET /system/api/targets/{id}, then POST .../login-check every 30s (HTTP 202). " + Doors.ADMIN_ONLY
                            + " Already PRESENT: prints the command and exits at once, no login-check sent."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits targets sign-in --target localhost",
                    "  qits targets sign-in --target localhost --harness kimi --timeout 2h",
                    "",
                    "- The command is null until a login state has arrived on the target's current connection: "
                            + "run `qits targets healthcheck` or wait for the runner to connect, then try again.",
                    "- 1: the target is still ABSENT or UNKNOWN after --timeout; the message names the last state.",
                    "- --output has no effect here: this command prints notices, never the service's answer."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The target reports PRESENT (already, or within --timeout).",
                    "1:The platform refused or cannot be reached, or --timeout passed with the target still not "
                            + "signed in (the message names the last state).",
                    HelpText.USAGE})
    public static class SignInCommand extends PlatformCommand {

        static final Duration POLL = Duration.ofSeconds(30);

        @CommandLine.ParentCommand
        TargetsCommand targets;

        @CommandLine.Option(names = "--target", paramLabel = "<name|id>", required = true,
                description = "The target's name or id.")
        String target;

        @CommandLine.Option(names = "--harness", paramLabel = "claude|kimi", defaultValue = "claude",
                description = "The harness to sign in. Default: claude.")
        String harness;

        @CommandLine.Option(names = "--timeout", paramLabel = "<duration>", defaultValue = "10m",
                description = "How long to wait: 30m, 2h, 1h30m, 1d or ISO-8601 (PT2H). Default: 10m.")
        String timeout;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            String chosen = harness(harness);
            Duration budget = SignInTimeout.parse(timeout);
            String id = targets.targetId(context, target);
            TargetsApi api = targets.api(context);
            JsonNode current = api.target(id);
            String name = text(current, "name");
            print(context, current, chosen, name);
            if ("PRESENT".equals(loginValue(current, chosen))) {
                context.out().println(SafeText.line(name) + " is already signed in.");
                return 0;
            }
            Instant deadline = context.clock().instant().plus(budget);
            String state = loginValue(current, chosen);
            while (true) {
                api.requestTargetLoginCheck(id);
                context.sleeper().sleep(POLL);
                current = api.target(id);
                state = loginValue(current, chosen);
                if ("PRESENT".equals(state)) {
                    context.out().println(SafeText.line(name) + " is signed in.");
                    return 0;
                }
                if (!context.clock().instant().isBefore(deadline)) {
                    throw new CliFailure("Target " + SafeText.line(name) + " is still " + state + " after "
                            + timeout.strip() + ". Sign in with the command above, then run `qits targets sign-in "
                            + "--target " + SafeText.line(target.strip()) + "` again.", CliFailure.FAILED);
                }
            }
        }

        private static void print(CliContext context, JsonNode target, String harness, String name) {
            String command = "kimi".equals(harness) ? text(target, "kimiLoginCommand") : text(target, "loginCommand");
            if (command.isEmpty()) {
                context.out().println("Target " + SafeText.line(name) + " has not reported a login command yet: "
                        + "its runner may not be connected. Run this on " + SafeText.line(name) + " once it is, "
                        + "with claude's own sign-in.");
                return;
            }
            context.out().println("Run this on " + SafeText.line(name) + ":");
            context.out().println("  " + SafeText.line(command));
        }
    }
}
