package eu.wohlben.qits.cli.access.projects;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.Table;
import eu.wohlben.qits.cli.access.complete.ProjectSource;
import eu.wohlben.qits.cli.tui.api.Completes;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

@CommandLine.Command(name = "repositories", mixinStandardHelpOptions = true,
        subcommands = {RepositoriesCommand.ListCommand.class, RepositoriesCommand.CreateCommand.class},
        description = {"The repositories of one project: list shows them, create stands a new one up. Release "
                + "requests take a repository's id or name as --repository.",
                "A repository's archetype is read off its name's role suffix, so the name is what says what kind "
                        + "of component it is. There is no flag for the archetype, on purpose."})
public class RepositoriesCommand implements Runnable {

    @CommandLine.Mixin
    ProjectsOptions options;

    @Completes(ProjectSource.class)
    @CommandLine.Option(names = "--project", paramLabel = "<project>", scope = CommandLine.ScopeType.INHERIT,
            description = "The project: its id, slug or name.")
    String project;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    static String required(String value, String message) throws CliFailure {
        if (value == null || value.isBlank()) {
            throw new CliFailure(message, CliFailure.USAGE);
        }
        return value.strip();
    }

    static final String NAME_THE_PROJECT = "Name the project: --project <id, slug or name>.";

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = "List the project's repositories: name, archetype, component and id. --project may come "
                    + "before or after list.",
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits repositories --project qits list", "  qits repositories list --project qits -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class ListCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        RepositoriesCommand parent;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.options.output);
            String wanted = required(parent.project, NAME_THE_PROJECT);
            ProjectsApi api = ProjectsApi.connect(context, parent.options.projectsUrl);
            JsonNode project = api.project(wanted);
            JsonNode answer = api.repositories(text(project, "id"));
            if (json) {
                ProjectsApi.printJson(context.out(), answer);
                return 0;
            }
            List<JsonNode> repositories = ProjectsApi.entries(answer, "repository");
            if (repositories.isEmpty()) {
                context.out().println("Project " + ProjectsApi.projectLabel(project) + " has no repositories.");
                return 0;
            }
            Table.print(context.out(), "", List.of("NAME", "ARCHETYPE", "COMPONENT", "ID"), repositories.stream()
                    .map(r -> List.of(Table.cell(text(r, "name"), 60), Table.cell(text(r, "archetype"), 30),
                            Table.cell(text(r, "component"), 40), Table.cell(text(r, "id"), 64)))
                    .toList());
            return 0;
        }
    }

    /**
     * Stands a repository up: a blank one on the platform's git host, and the entry that mounts it
     * in the project's wrapper.
     *
     * <p><b>There is no --archetype, and there deliberately never will be.</b> The service reads the
     * kind off the name's role suffix, and that derivation is the whole point: a flag would let a
     * caller state a kind the name contradicts, and the archetype is the one field nothing
     * downstream can correct afterwards. So the archetype is printed rather than chosen — it is
     * what the door decided.
     */
    @CommandLine.Command(name = "create", mixinStandardHelpOptions = true,
            description = {"Create a repository in the project: a blank one on the platform's git host, seeded "
                    + "with the repository template, and mounted in the project's wrapper.",
                    "The NAME says what kind of component it is: the service reads the archetype off the name's "
                            + "role suffix (payments-daemon is a DAEMON), and there is no flag to state one, "
                            + "because a flag could contradict the name. Name the repository "
                            + "<component>[-<modifier>]-<role>.",
                    "--component places the wrapper entry at components/<component>/<name>. Without it the "
                            + "wrapper's own layout decides."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits repositories --project qits create qits-docs-app --component qits-docs",
                    "  qits repositories create qits-payments-service --project qits --component qits-payments",
                    "  qits repositories --project qits create qits-docs-app -o json",
                    "",
                    "- Roles: -service, -frontend, -app, -daemon, -oci, -cli, -javalib, -jslib. A name that "
                            + "carries none of them is refused by the service, because a guessed kind is the one "
                            + "thing nothing downstream could correct.",
                    "- Creating a repository needs the role qits:admin or qits:agent.",
                    "- The command prints what the service answered: the name, the archetype it derived, the "
                            + "component, the id, and the wrapper path the entry was written at."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The repository exists and the wrapper names it.",
                    "1:The platform refused (the message names the status and what to do next), or cannot be "
                            + "reached.",
                    HelpText.USAGE})
    public static class CreateCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        RepositoriesCommand parent;

        @CommandLine.Parameters(index = "0", paramLabel = "<name>",
                description = "The repository's name, and with it its kind: it ends in a role suffix, and that "
                        + "suffix is what the archetype is read from. It is also what ../<name>.git resolves to.")
        String name;

        @CommandLine.Option(names = "--component", paramLabel = "<component>",
                description = "The technical component to mount the entry under: components/<component>/<name>. "
                        + "Default: the wrapper's own layout decides.")
        String component;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.options.output);
            String wanted = required(parent.project, NAME_THE_PROJECT);
            String repository = required(name, "Name the repository to create: `qits repositories --project "
                    + "<project> create <name>`.");
            ProjectsApi api = ProjectsApi.connect(context, parent.options.projectsUrl);
            JsonNode project = api.project(wanted);
            JsonNode answer;
            try {
                answer = api.createRepository(text(project, "id"), repository, component);
            } catch (CliFailure refused) {
                throw explain(refused, repository);
            }
            if (json) {
                ProjectsApi.printJson(context.out(), answer);
                return 0;
            }
            print(context, answer);
            return 0;
        }

        /**
         * What the service answered, the archetype first among equals: it is the derivation nobody
         * can correct once the row exists, so a person sees it at the moment it is decided. The
         * wrapper path is the proof the wrapper entry was written; the service leaves it out when
         * there is none, and then so does this.
         */
        private static void print(CliContext context, JsonNode answer) {
            JsonNode repository = answer.path("repository");
            context.out().println("Repository " + text(repository, "name"));
            List<List<String>> rows = new ArrayList<>(List.of(
                    List.of("archetype", Table.cell(text(repository, "archetype"), 30)),
                    List.of("component", Table.cell(text(repository, "component"), 40)),
                    List.of("id", Table.cell(text(repository, "id"), 64))));
            String wrapperPath = text(answer, "wrapperPath");
            if (!wrapperPath.isEmpty()) {
                rows.add(List.of("wrapper entry", Table.cell(wrapperPath, 200)));
            }
            Table.print(context.out(), "  ", null, rows);
        }

        /**
         * The two refusals this command meets in the ordinary course of standing a repository up,
         * each with the step that follows it. Both are the platform's answer, so both keep the
         * refusal's exit code; what they add is the sentence that says what to do next.
         */
        private static CliFailure explain(CliFailure refused, String repository) {
            String said = refused.getMessage() == null ? "" : refused.getMessage();
            if (refused.status() == 400 && said.contains("role suffix")) {
                return new CliFailure("The service cannot tell what kind of component '" + repository
                        + "' is (HTTP 400): " + said + System.lineSeparator()
                        + "Either the name carries no role suffix, or it carries one this service does not know "
                        + "yet — a role the live service has not been released with reads exactly like a missing "
                        + "one. `qits repositories --project <project> list` shows the archetypes it does know.",
                        CliFailure.FAILED);
            }
            if (refused.status() == 403) {
                return new CliFailure(said + System.lineSeparator()
                        + "Creating a repository needs qits:admin or qits:agent. A 403 on an agent credential "
                        + "means the service has not been released with that role on this door yet.",
                        CliFailure.FAILED);
            }
            return refused;
        }
    }
}
