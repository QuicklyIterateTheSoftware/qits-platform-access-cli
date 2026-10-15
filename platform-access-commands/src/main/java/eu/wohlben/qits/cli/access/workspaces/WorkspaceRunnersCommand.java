package eu.wohlben.qits.cli.access.workspaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.Table;
import eu.wohlben.qits.cli.access.workspaces.WorkspacesCommand.Json;
import eu.wohlben.qits.cli.tui.api.Effect;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;
import static eu.wohlben.qits.cli.access.workspaces.WorkspacesCommand.cell;
import static eu.wohlben.qits.cli.access.workspaces.WorkspacesCommand.entries;

/**
 * {@code qits workspaces runners}: the landing app's Runners page, one command per action
 * (qits-1261). Register and Rotate token are left out on purpose: their answer is a one-time
 * registration token, and this CLI prints no token but the two its conventions name.
 */
@CommandLine.Command(name = "runners", mixinStandardHelpOptions = true,
        subcommands = {WorkspaceRunnersCommand.ListCommand.class, WorkspaceRunnersCommand.HealthCommand.class,
                WorkspaceRunnersCommand.UpdateCommand.class, WorkspaceRunnersCommand.GreenlightCommand.class,
                WorkspaceRunnersCommand.HealthcheckCommand.class, WorkspaceRunnersCommand.LoginCheckCommand.class,
                WorkspaceRunnersCommand.DeleteCommand.class},
        description = {"The workspace runners, the nodes slots are placed on: list them with their connection, slots, "
                + "health and node login, read a runner's newest health check, update its slots, description and "
                + "memory caps, lift a quarantine, ask for a health check or a login check, and delete one. The "
                + "Runners page.",
                "A runner is named by its id (`qits workspaces runners list -o json` shows it)."},
        footerHeading = "%nNotes:%n",
        footer = {"- Reading, and asking for a health check, are open to qits:agent. update, greenlight and delete need "
                + "qits:admin, qits:admin-agent or qits:system; login-check needs qits:admin or qits:admin-agent. "
                + "qits:agent is refused those (HTTP 403) by design.",
                "- Registering a runner and rotating its registration token are the landing app's only: their answer "
                        + "is a one-time token, and qits never prints a token."})
public class WorkspaceRunnersCommand implements Runnable {

    static final String ADMIN_WRITE = "It needs qits:admin, qits:admin-agent or qits:system, which a person's token "
            + "carries. qits:agent is refused (HTTP 403) by design.";

    @CommandLine.ParentCommand
    WorkspacesCommand parent;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    static String runnerId(String given) throws CliFailure {
        String id = given == null ? "" : given.strip();
        if (id.isEmpty()) {
            throw new CliFailure("Name the runner: its id, as `qits workspaces runners list -o json` shows it.",
                    CliFailure.USAGE);
        }
        return id;
    }

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = {"Every runner: connected or not, version, slots (running, owned, queued), quarantine, the "
                    + "last health check and the node's agent login.",
                    "GET /workspaces/api/runners. " + Doors.READ},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces runners list", "  qits workspaces runners list -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class ListCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkspaceRunnersCommand runners;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            WorkspacesCommand root = runners.parent;
            boolean json = root.json();
            JsonNode answer = root.api(context).runners();
            if (json) {
                Json.print(context.out(), answer);
                return 0;
            }
            List<JsonNode> list = entries(answer, "runners", null);
            if (list.isEmpty()) {
                context.out().println("No runners.");
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode runner : list) {
                JsonNode health = runner.path("health");
                String ok = health.isObject() && health.has("ok") ? (health.path("ok").asBoolean() ? "ok" : "failed")
                        : runner.path("lastHealthCheckOk").isBoolean()
                        ? (runner.path("lastHealthCheckOk").asBoolean() ? "ok" : "failed") : "-";
                rows.add(List.of(cell(runner, "id", 40), cell(runner, "name", 30),
                        runner.path("connected").asBoolean(false) ? "yes" : "no", cell(runner, "version", 20),
                        text(runner, "running") + "/" + text(runner, "slots"),
                        runner.path("quarantined").asBoolean(false) ? "yes" : "no", ok,
                        Table.time(text(runner, "lastSeenAt"))));
            }
            Table.print(context.out(), "", List.of("ID", "NAME", "CONNECTED", "VERSION", "RUNNING/SLOTS",
                    "QUARANTINED", "HEALTH", "LAST SEEN"), rows);
            return 0;
        }
    }

    @CommandLine.Command(name = "health", mixinStandardHelpOptions = true,
            description = {"A runner's newest health check, every check with its data: Node details on a runner. A "
                    + "runner never checked says so.",
                    "GET /workspaces/api/runners/{id}/health (HTTP 204: never checked). " + Doors.READ},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces runners health 00000000-0000-4000-8000-000000000020"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class HealthCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkspaceRunnersCommand runners;

        @CommandLine.Parameters(paramLabel = "<runner>", description = "The runner's id.")
        String runner;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            WorkspacesCommand root = runners.parent;
            boolean json = root.json();
            String id = runnerId(runner);
            JsonNode answer = root.api(context).runnerHealth(id);
            if (json) {
                Json.print(context.out(), answer);
                return 0;
            }
            if (answer == null || answer.isNull()) {
                context.out().println("Runner " + SafeText.line(id) + " has no health check yet.");
                return 0;
            }
            for (JsonNode check : answer.path("checks")) {
                context.out().println(cell(check, "name", 60) + ": " + (check.path("ok").asBoolean(false) ? "ok"
                        : "failed"));
                Iterator<Map.Entry<String, JsonNode>> data = check.path("data").fields();
                while (data.hasNext()) {
                    Map.Entry<String, JsonNode> entry = data.next();
                    String value = entry.getValue().isValueNode() ? entry.getValue().asText()
                            : entry.getValue().toString();
                    context.out().println("  " + SafeText.line(entry.getKey()) + ": " + Table.cell(value, 200));
                }
            }
            return 0;
        }
    }

    @CommandLine.Command(name = "update", mixinStandardHelpOptions = true,
            description = {"Change a runner's slots, description or memory caps: Save on a runner. Only what is given "
                    + "is sent; a memory cap reaches the next workspace launched on it, never a running one.",
                    "PATCH /workspaces/api/runners/{id} with {slots?, description?, workspaceMemoryLimit?, "
                            + "workspaceMemorySwapLimit?}. " + ADMIN_WRITE},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces runners update 00000000-0000-4000-8000-000000000020 --slots 2 "
                    + "--description 'The build machine under the desk'",
                    "  qits workspaces runners update 00000000-0000-4000-8000-000000000020 --memory 8g --memory-swap 8g",
                    "",
                    "- --slots 0 drains the runner. A blank --description, --memory or --memory-swap clears it back "
                            + "to the default. --memory-swap -1 is unlimited swap."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class UpdateCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkspaceRunnersCommand runners;

        @CommandLine.Parameters(paramLabel = "<runner>", description = "The runner's id.")
        String runner;

        @CommandLine.Option(names = "--slots", paramLabel = "<n>",
                description = "How many workspaces it may run at once.")
        Integer slots;

        @CommandLine.Option(names = "--description", paramLabel = "<text>", description = "Free text.")
        String description;

        @CommandLine.Option(names = "--memory", paramLabel = "<size>",
                description = "The --memory its workspace containers get, a docker size such as 12g (at least 6m).")
        String memory;

        @CommandLine.Option(names = "--memory-swap", paramLabel = "<size>",
                description = "Their --memory-swap: memory plus swap, at least --memory; or -1 for unlimited swap.")
        String memorySwap;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            WorkspacesCommand root = runners.parent;
            boolean json = root.json();
            String id = runnerId(runner);
            ObjectNode change = JsonNodeFactory.instance.objectNode();
            if (slots != null) {
                if (slots < 0) {
                    throw new CliFailure("--slots must be 0 or more.", CliFailure.USAGE);
                }
                change.put("slots", slots);
            }
            if (description != null) {
                change.put("description", description);
            }
            if (memory != null) {
                change.put("workspaceMemoryLimit", memory.strip());
            }
            if (memorySwap != null) {
                change.put("workspaceMemorySwapLimit", memorySwap.strip());
            }
            if (change.isEmpty()) {
                throw new CliFailure("Nothing to change: give --slots, --description, --memory or --memory-swap.",
                        CliFailure.USAGE);
            }
            JsonNode answer = root.api(context).patchRunner(id, change);
            if (json) {
                Json.print(context.out(), answer);
                return 0;
            }
            context.out().println("Runner " + SafeText.line(id) + ": " + text(answer, "slots") + " slots, memory "
                    + cell(answer, "workspaceMemoryLimit", 20) + ", swap " + cell(answer, "workspaceMemorySwapLimit", 20)
                    + ", " + cell(answer, "description", 100) + ".");
            return 0;
        }
    }

    /** greenlight, healthcheck and login-check: one POST each, no body, as the runner's buttons send it. */
    abstract static class RunnerVerb extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkspaceRunnersCommand runners;

        @CommandLine.Parameters(paramLabel = "<runner>", description = "The runner's id.")
        String runner;

        abstract String verb();

        abstract String done(String id, JsonNode answer);

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            WorkspacesCommand root = runners.parent;
            boolean json = root.json();
            String id = runnerId(runner);
            JsonNode answer = root.api(context).runnerVerb(id, verb());
            if (json) {
                if (answer != null && !answer.isNull()) {
                    Json.print(context.out(), answer);
                }
                return 0;
            }
            context.out().println(done(SafeText.line(id), answer));
            return 0;
        }
    }

    @CommandLine.Command(name = "greenlight", mixinStandardHelpOptions = true,
            description = {"Lift a runner's quarantine: Greenlight on a quarantined runner. The service quarantines a "
                    + "runner itself, after a failed health check.",
                    "POST /workspaces/api/runners/{id}/greenlight, no body. " + ADMIN_WRITE},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces runners greenlight 00000000-0000-4000-8000-000000000020"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class GreenlightCommand extends RunnerVerb {
        @Override
        String verb() {
            return "greenlight";
        }

        @Override
        String done(String id, JsonNode answer) {
            return "Runner " + id + (answer != null && answer.path("quarantined").asBoolean(false)
                    ? " is still quarantined." : " is out of quarantine.");
        }
    }

    @CommandLine.Command(name = "healthcheck", mixinStandardHelpOptions = true,
            description = {"Queue a health check on a connected runner's node: Run health check. The result arrives "
                    + "with a later `qits workspaces runners health`.",
                    "POST /workspaces/api/runners/{id}/healthcheck, no body (HTTP 202). Any credential may ask, "
                            + "qits:agent included."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces runners healthcheck 00000000-0000-4000-8000-000000000020",
                    "",
                    "- An offline runner is refused: HTTP 409 RUNNER_UNAVAILABLE."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class HealthcheckCommand extends RunnerVerb {
        @Override
        String verb() {
            return "healthcheck";
        }

        @Override
        String done(String id, JsonNode answer) {
            return "Queued a health check on runner " + id + ".";
        }
    }

    @CommandLine.Command(name = "login-check", mixinStandardHelpOptions = true,
            description = {"Ask a connected runner to probe its node's agent login again: Re-check on a runner. The "
                    + "result shows in a later `qits workspaces runners list`.",
                    "POST /workspaces/api/runners/{id}/login-check, no body (HTTP 202). It needs qits:admin or "
                            + "qits:admin-agent, which a person's token carries. qits:agent is refused (HTTP 403) by "
                            + "design."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces runners login-check 00000000-0000-4000-8000-000000000020",
                    "",
                    "- An offline runner is refused: HTTP 409 RUNNER_UNAVAILABLE."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class LoginCheckCommand extends RunnerVerb {
        @Override
        String verb() {
            return "login-check";
        }

        @Override
        String done(String id, JsonNode answer) {
            return "Asked runner " + id + " to check its node's agent login.";
        }
    }

    @CommandLine.Command(name = "delete", mixinStandardHelpOptions = true,
            description = {"Decommission a runner: Delete on a runner.",
                    "DELETE /workspaces/api/runners/{id}. " + ADMIN_WRITE},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces runners delete 00000000-0000-4000-8000-000000000020",
                    "",
                    "- A runner that still owns ACTIVE workspaces is refused (HTTP 409 RUNNER_OWNS_WORKSPACES); the "
                            + "message names their slots."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class DeleteCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkspaceRunnersCommand runners;

        @CommandLine.Parameters(paramLabel = "<runner>", description = "The runner's id.")
        String runner;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            WorkspacesCommand root = runners.parent;
            boolean json = root.json();
            String id = runnerId(runner);
            root.api(context).deleteRunner(id);
            if (!json) {
                context.out().println("Deleted runner " + SafeText.line(id) + ".");
            }
            return 0;
        }
    }
}
