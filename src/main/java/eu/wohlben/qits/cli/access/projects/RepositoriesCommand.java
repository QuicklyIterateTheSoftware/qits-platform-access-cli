package eu.wohlben.qits.cli.access.projects;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import picocli.CommandLine;

import java.util.List;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

@CommandLine.Command(name = "repositories", mixinStandardHelpOptions = true,
        subcommands = RepositoriesCommand.ListCommand.class,
        description = "The repositories of one project.")
public class RepositoriesCommand implements Runnable {

    @CommandLine.Mixin
    ProjectsOptions options;

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
            description = "List the project's repositories: name, archetype, component and id.")
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
}
