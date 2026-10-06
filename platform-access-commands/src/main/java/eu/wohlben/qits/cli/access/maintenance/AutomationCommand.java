package eu.wohlben.qits.cli.access.maintenance;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.complete.ReleaseRequestSource;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.projects.ProjectsApi;
import eu.wohlben.qits.cli.tui.api.Completes;
import picocli.CommandLine;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

/**
 * {@code qits maintenance automation}: one release-request automation of one release request. {@code
 * run} is its only command today.
 */
@CommandLine.Command(name = "automation", mixinStandardHelpOptions = true,
        subcommands = {AutomationCommand.RunCommand.class},
        description = "One release-request automation of a release request. `run` re-runs a kind on the "
                + "request's current fold.")
public class AutomationCommand implements Runnable {

    @CommandLine.ParentCommand
    MaintenanceCommand parent;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    @CommandLine.Command(name = "run", mixinStandardHelpOptions = true,
            description = {"Re-run one release-request automation on a release request's current fold.",
                    "It skips carry-over and applicability, so a repository's first screenshot references come "
                            + "from here. The kind's own precondition still refuses the run with a sentence (no "
                            + "`test:browser` script, for example). It prints the job's id; `qits maintenance "
                            + "bump <id>` shows how it went."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits maintenance --project qits --repository qits-landing-app automation run "
                            + "--request 4f2a91c0 --kind estate-pins",
                    "  qits maintenance --project qits --repository qits-landing-app automation run "
                            + "--request 4f2a91c0 --kind estate-pins --work-item qits-112",
                    "",
                    "- `qits maintenance automations --request <id>` names the kinds; `kind` is the first column.",
                    "- 404 means the kind or the repository is not one the platform knows. 409 means one is "
                            + "already running for this (request, kind), the request takes no branch, or bumping "
                            + "is off.",
                    "- --work-item is the commit subject's scope (chore(<work item>): update <label>). Without "
                            + "it, the newest one named on the request's own commits is used."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class RunCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        AutomationCommand parent;

        @Completes(ReleaseRequestSource.class)
        @CommandLine.Option(names = "--request", paramLabel = "<id>", required = true,
                description = "The release request: its id, or enough of its start to name one "
                        + "(`qits release-request list` shows 8 characters).")
        String request;

        @CommandLine.Option(names = "--kind", paramLabel = "<kind>", required = true,
                description = "The automation's kind, as `qits maintenance automations --request <id>` names it.")
        String kind;

        @CommandLine.Option(names = "--work-item", paramLabel = "<id>",
                description = "The work item the commit names, for example qits-112.")
        String workItem;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            MaintenanceCommand maintenance = parent.parent;
            boolean json = maintenance.json();
            String wantedProject = MaintenanceCommand.required(maintenance.project,
                    "Name the project: --project <id, slug or name>.");
            String wantedRepository = MaintenanceCommand.required(maintenance.repository,
                    "Name the repository: --repository <id or name>.");
            String wantedRequest = MaintenanceCommand.required(request, "Name the release request: --request <id>.");
            String wantedKind = MaintenanceCommand.required(kind, "Name the kind: --kind <kind>.");
            ProjectsApi projects = ProjectsApi.connect(context, maintenance.projectsUrl);
            JsonNode foundProject = projects.project(wantedProject);
            JsonNode repo = projects.repository(foundProject, wantedRepository);
            String requestId = MaintenanceCommand.requestId(projects, text(repo, "id"), text(repo, "name"), wantedRequest);

            JsonNode answer = maintenance.maintenance(context).runAutomation(requestId, wantedKind, workItem);
            if (json) {
                ProjectsApi.printJson(context.out(), answer);
                return 0;
            }
            String id = text(answer, "id");
            context.out().println("Requested " + wantedKind + " for release request " + requestId + ": job " + id);
            context.out().println("Follow it with `qits maintenance bump " + id + "`.");
            return 0;
        }
    }
}
