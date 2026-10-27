package eu.wohlben.qits.cli.access.targets;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformClient.TextAnswer;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.Table;
import picocli.CommandLine;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;
import static eu.wohlben.qits.cli.access.targets.TargetsCommand.LeasesCommand.leaseId;
import static eu.wohlben.qits.cli.access.targets.TargetsCommand.cell;

/**
 * {@code qits targets docker}: a target's own docker containers and volumes, read or handled
 * without a shell on it, over qits-system-service's {@code /system/api/targets/{id}/docker} and
 * {@code /leases/{id}/logs} (epic qits-1621, dossier "Docker over the runner socket"). The nested
 * group follows {@code workspaces/WorkspaceAgentsCommand.java}'s pattern: bare, it refuses and asks
 * for a command.
 * <p>
 * Served over MCP like the other {@code targets} verbs: no command here is {@code
 * Interaction.LOCAL}, and nothing here prints a secret, so none needs to be kept out of a
 * transcript the way {@code create} and {@code rotate-token} do.
 */
@CommandLine.Command(name = "docker", mixinStandardHelpOptions = true,
        subcommands = {TargetDockerCommand.PsCommand.class, TargetDockerCommand.InspectCommand.class,
                TargetDockerCommand.LogsCommand.class, TargetDockerCommand.ExecCommand.class,
                TargetDockerCommand.StopCommand.class, TargetDockerCommand.RmCommand.class,
                TargetDockerCommand.VolumesCommand.class, TargetDockerCommand.VolumeCommand.class,
                TargetDockerCommand.AuditCommand.class},
        description = {"A target's own docker containers and volumes: list and inspect them, read a container's "
                + "log (or a lease's own, by its id), run a command in one, stop or remove one, and read the "
                + "target's audit trail.",
                "The runner answers only for resources it owns (named qits-system-runner-*, or labelled "
                        + "qits.system.runner=<targetId>); its own process container refuses exec, stop and rm."},
        footerHeading = "%nNotes:%n",
        footer = {"- " + Doors.DOCKER})
public class TargetDockerCommand implements Runnable {

    @CommandLine.ParentCommand
    TargetsCommand targets;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    /** A log or an exec's output: one line at a time, through {@link SafeText#line}; the runner's output is text. */
    static void printLines(PrintStream out, String text) {
        for (String line : (text == null ? "" : text).split("\n", -1)) {
            out.println(SafeText.line(line));
        }
    }

    // --- containers ---

    @CommandLine.Command(name = "ps", mixinStandardHelpOptions = true,
            description = {"The target runner's own containers (docker ps -a).",
                    "GET /system/api/targets/{id}/docker/containers. " + Doors.DOCKER},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits targets docker ps --target localhost", "  qits targets docker ps --target localhost -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class PsCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        TargetDockerCommand docker;

        @CommandLine.Option(names = "--target", paramLabel = "<name|id>", required = true,
                description = "The target's name or id.")
        String target;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = docker.targets.json();
            String id = docker.targets.targetId(context, target);
            JsonNode answer = docker.targets.api(context).containers(id);
            if (json) {
                TargetsCommand.Json.print(context.out(), answer);
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode row : answer) {
                rows.add(List.of(cell(row, "Names", 60), cell(row, "Image", 60), cell(row, "Status", 60)));
            }
            if (rows.isEmpty()) {
                context.out().println("No containers.");
                return 0;
            }
            Table.print(context.out(), "", List.of("NAME", "IMAGE", "STATUS"), rows);
            return 0;
        }
    }

    @CommandLine.Command(name = "inspect", mixinStandardHelpOptions = true,
            description = {"docker inspect of one of the runner's containers, secrets redacted.",
                    "GET /system/api/targets/{id}/docker/containers/{name}. " + Doors.DOCKER},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits targets docker inspect --target localhost qits-system-runner-work-ws-1-a1b2c3d4",
                    "", "- HTTP 404: no such target, or no such container of its runner."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class InspectCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        TargetDockerCommand docker;

        @CommandLine.Option(names = "--target", paramLabel = "<name|id>", required = true,
                description = "The target's name or id.")
        String target;

        @CommandLine.Parameters(index = "0", paramLabel = "<name>", description = "The container's name.")
        String name;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            String id = docker.targets.targetId(context, target);
            JsonNode answer = docker.targets.api(context).container(id, containerName(name));
            TargetsCommand.Json.print(context.out(), answer);
            return 0;
        }
    }

    @CommandLine.Command(name = "logs", mixinStandardHelpOptions = true,
            description = {"A container's log: either --target and its name, or --lease and the lease ends up on "
                    + "its own container.",
                    "GET /system/api/targets/{id}/docker/containers/{name}/logs?tail=&since=, or GET "
                            + "/system/api/leases/{id}/logs?tail=&since= for --lease. text/plain. " + Doors.DOCKER},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits targets docker logs --target localhost qits-system-runner-work-ws-1-a1b2c3d4",
                    "  qits targets docker logs --target localhost qits-system-runner-work-ws-1-a1b2c3d4 --tail 50",
                    "  qits targets docker logs --lease 3f2a9c1e-0000-4000-8000-000000000001",
                    "",
                    "- --tail defaults to 200 and is capped at 5000 by the service; the output is capped at 1 MiB, "
                            + "and a notice on stderr says so when the service cut it.",
                    "- --lease reads the lease's own container, resolved by its target and the "
                            + "qits.system.runner.lease label; HTTP 404: no such lease, it is on no target, or its "
                            + "container is gone. HTTP 409 TARGET_UNAVAILABLE: its target is not connected.",
                    "- -o json has no effect here: the log is always printed as text."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class LogsCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        TargetDockerCommand docker;

        @CommandLine.Option(names = "--target", paramLabel = "<name|id>",
                description = "The target's name or id; needs the container's name too.")
        String target;

        @CommandLine.Option(names = "--lease", paramLabel = "<id>",
                description = "A lease's id: reads its own container's log, on its target.")
        String lease;

        @CommandLine.Parameters(index = "0", arity = "0..1", paramLabel = "<name>",
                description = "The container's name; required with --target, and not given with --lease.")
        String name;

        @CommandLine.Option(names = "--tail", paramLabel = "<n>", description = "The newest lines to read.")
        Integer tail;

        @CommandLine.Option(names = "--since", paramLabel = "<s>", description = "Only lines at or after this time.")
        String since;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean byTarget = target != null && !target.isBlank();
            boolean byLease = lease != null && !lease.isBlank();
            if (byTarget == byLease) {
                throw new CliFailure("Give --target <name|id> <container>, or --lease <id>, not both or neither.",
                        CliFailure.USAGE);
            }
            TextAnswer answer;
            if (byTarget) {
                String id = docker.targets.targetId(context, target);
                answer = docker.targets.api(context).containerLogs(id, containerName(name), tail, since);
            } else {
                if (name != null && !name.isBlank()) {
                    throw new CliFailure("--lease reads its own container; it takes no container name.",
                            CliFailure.USAGE);
                }
                answer = docker.targets.api(context).leaseLogs(leaseId(lease), tail, since);
            }
            printLines(context.out(), answer.body());
            if (answer.truncated()) {
                context.err().println("The log was cut off at 1 MiB; --since or a smaller --tail reads less of it.");
            }
            return 0;
        }
    }

    @CommandLine.Command(name = "exec", mixinStandardHelpOptions = true,
            description = {"docker exec in one of the runner's containers: no TTY, no stdin.",
                    "POST /system/api/targets/{id}/docker/containers/{name}/exec {argv, timeoutSeconds?}. "
                            + Doors.DOCKER},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits targets docker exec --target localhost qits-system-runner-work-ws-1-a1b2c3d4 -- ls -la",
                    "  qits targets docker exec --target localhost qits-system-runner-work-ws-1-a1b2c3d4 --timeout "
                            + "60 -- cat /etc/hostname",
                    "",
                    "- --timeout defaults to 30 and is capped at 120 by the service; stdout and stderr are capped "
                            + "at 1 MiB each, each with its own truncation notice on stderr.",
                    "- The exit code is the command run in the container's, printed and also this command's own "
                            + "exit code when it is 0..125 (a shell's reserved range above that prints but is not "
                            + "repeated as this command's exit, to keep it inside this CLI's own codes).",
                    "- HTTP 403 DOCKER_REFUSED: the runner's own process container."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0-125:The command ran, with this exit code.",
                    "126,127:The command ran; its own exit code is printed, but not repeated here.", HelpText.REFUSED,
                    HelpText.USAGE})
    public static class ExecCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        TargetDockerCommand docker;

        @CommandLine.Option(names = "--target", paramLabel = "<name|id>", required = true,
                description = "The target's name or id.")
        String target;

        @CommandLine.Parameters(index = "0", paramLabel = "<name>", description = "The container's name.")
        String name;

        @CommandLine.Option(names = "--timeout", paramLabel = "<seconds>",
                description = "How long it may run; default 30, at most 120.")
        Integer timeout;

        @CommandLine.Parameters(index = "1..*", paramLabel = "<argv>",
                description = "The command and its arguments, after --; no shell is put in front.")
        List<String> argv;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            if (argv == null || argv.isEmpty()) {
                throw new CliFailure("Name the command to run, after --: `qits targets docker exec --target "
                        + "<t> <name> -- <argv...>`.", CliFailure.USAGE);
            }
            String id = docker.targets.targetId(context, target);
            JsonNode answer = docker.targets.api(context)
                    .execInContainer(id, containerName(name), argv, timeout);
            boolean json = docker.targets.json();
            if (json) {
                TargetsCommand.Json.print(context.out(), answer);
            } else {
                printLines(context.out(), text(answer, "stdout"));
                printLines(context.err(), text(answer, "stderr"));
                if (answer.path("truncated").asBoolean(false)) {
                    context.err().println("stdout or stderr was cut off at 1 MiB.");
                }
            }
            int exitCode = answer.path("exitCode").asInt(-1);
            return exitCode >= 0 && exitCode <= 125 ? exitCode : 0;
        }
    }

    @CommandLine.Command(name = "stop", mixinStandardHelpOptions = true,
            description = {"docker stop -t 10 of one of the runner's containers.",
                    "POST /system/api/targets/{id}/docker/containers/{name}/stop, no body. " + Doors.DOCKER},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits targets docker stop --target localhost qits-system-runner-work-ws-1-a1b2c3d4",
                    "", "- HTTP 403 DOCKER_REFUSED: the runner's own process container."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class StopCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        TargetDockerCommand docker;

        @CommandLine.Option(names = "--target", paramLabel = "<name|id>", required = true,
                description = "The target's name or id.")
        String target;

        @CommandLine.Parameters(index = "0", paramLabel = "<name>", description = "The container's name.")
        String name;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = docker.targets.json();
            String id = docker.targets.targetId(context, target);
            String container = containerName(name);
            JsonNode answer = docker.targets.api(context).stopContainer(id, container);
            if (json) {
                TargetsCommand.Json.print(context.out(), answer);
                return 0;
            }
            context.out().println("Stopped " + SafeText.line(container) + ".");
            return 0;
        }
    }

    @CommandLine.Command(name = "rm", mixinStandardHelpOptions = true,
            description = {"docker rm -f of one of the runner's containers.",
                    "DELETE /system/api/targets/{id}/docker/containers/{name}. " + Doors.DOCKER},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits targets docker rm --target localhost qits-system-runner-work-ws-1-a1b2c3d4",
                    "", "- HTTP 403 DOCKER_REFUSED: the runner's own process container."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class RmCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        TargetDockerCommand docker;

        @CommandLine.Option(names = "--target", paramLabel = "<name|id>", required = true,
                description = "The target's name or id.")
        String target;

        @CommandLine.Parameters(index = "0", paramLabel = "<name>", description = "The container's name.")
        String name;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = docker.targets.json();
            String id = docker.targets.targetId(context, target);
            String container = containerName(name);
            JsonNode answer = docker.targets.api(context).removeContainer(id, container);
            if (json) {
                TargetsCommand.Json.print(context.out(), answer);
                return 0;
            }
            context.out().println("Removed " + SafeText.line(container) + ".");
            return 0;
        }
    }

    // --- volumes ---

    @CommandLine.Command(name = "volumes", mixinStandardHelpOptions = true,
            description = {"The target runner's own volumes (docker volume ls): those carrying its owner label, and those a container it owns mounts.",
                    "GET /system/api/targets/{id}/docker/volumes. " + Doors.DOCKER},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits targets docker volumes --target localhost"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class VolumesCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        TargetDockerCommand docker;

        @CommandLine.Option(names = "--target", paramLabel = "<name|id>", required = true,
                description = "The target's name or id.")
        String target;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = docker.targets.json();
            String id = docker.targets.targetId(context, target);
            JsonNode answer = docker.targets.api(context).volumes(id);
            if (json) {
                TargetsCommand.Json.print(context.out(), answer);
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode row : answer) {
                rows.add(List.of(cell(row, "Name", 80), cell(row, "Driver", 20), cell(row, "Scope", 20)));
            }
            if (rows.isEmpty()) {
                context.out().println("No volumes.");
                return 0;
            }
            Table.print(context.out(), "", List.of("NAME", "DRIVER", "SCOPE"), rows);
            return 0;
        }
    }

    @CommandLine.Command(name = "volume", mixinStandardHelpOptions = true,
            description = {"docker volume inspect of one of the runner's volumes (labelled, or mounted by a container it owns), secrets redacted. No size: docker system df walks every volume.",
                    "GET /system/api/targets/{id}/docker/volumes/{name}. " + Doors.DOCKER},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits targets docker volume --target localhost qits-system-runner-work-ws-1-a1b2c3d4",
                    "", "- HTTP 404: no such target, or no such volume of its runner."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class VolumeCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        TargetDockerCommand docker;

        @CommandLine.Option(names = "--target", paramLabel = "<name|id>", required = true,
                description = "The target's name or id.")
        String target;

        @CommandLine.Parameters(index = "0", paramLabel = "<name>", description = "The volume's name.")
        String name;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            String id = docker.targets.targetId(context, target);
            JsonNode answer = docker.targets.api(context).volume(id, containerName(name));
            TargetsCommand.Json.print(context.out(), answer);
            return 0;
        }
    }

    // --- audit ---

    @CommandLine.Command(name = "audit", mixinStandardHelpOptions = true,
            description = {"The target's docker audit rows, newest first: every call this group made against it, "
                    + "refusals and timeouts included.",
                    "GET /system/api/targets/{id}/docker/audit?limit=. " + Doors.DOCKER},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits targets docker audit --target localhost", "  qits targets docker audit --target "
                    + "localhost --limit 20",
                    "", "- --limit defaults to 100 and is capped at 500 by the service."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class AuditCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        TargetDockerCommand docker;

        @CommandLine.Option(names = "--target", paramLabel = "<name|id>", required = true,
                description = "The target's name or id.")
        String target;

        @CommandLine.Option(names = "--limit", paramLabel = "<n>", description = "At most this many rows.")
        Integer limit;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = docker.targets.json();
            String id = docker.targets.targetId(context, target);
            JsonNode answer = docker.targets.api(context).targetAudit(id, limit);
            if (json) {
                TargetsCommand.Json.print(context.out(), answer);
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode row : answer) {
                rows.add(List.of(Table.time(text(row, "at")), cell(row, "actor", 30), cell(row, "operation", 20),
                        cell(row, "resource", 40), cell(row, "outcome", 14), cell(row, "detail", 80)));
            }
            if (rows.isEmpty()) {
                context.out().println("No audit rows.");
                return 0;
            }
            Table.print(context.out(), "", List.of("AT", "ACTOR", "OPERATION", "RESOURCE", "OUTCOME", "DETAIL"), rows);
            return 0;
        }
    }

    /** The container or volume name as given, trimmed; a usage error when it is blank. */
    private static String containerName(String given) throws CliFailure {
        String name = given == null ? "" : given.strip();
        if (name.isEmpty()) {
            throw new CliFailure("Name the container or volume.", CliFailure.USAGE);
        }
        return name;
    }
}
