package eu.wohlben.qits.cli.access.projects;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import picocli.CommandLine;

import java.util.List;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

@CommandLine.Command(name = "projects", mixinStandardHelpOptions = true,
        subcommands = ProjectsCommand.ListCommand.class,
        description = "The platform's projects.")
public class ProjectsCommand implements Runnable {

    @CommandLine.Mixin
    ProjectsOptions options;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = "List the projects: slug, name and id.")
    public static class ListCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        ProjectsCommand parent;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.options.output);
            JsonNode answer = ProjectsApi.connect(context, parent.options.projectsUrl).projects();
            if (json) {
                ProjectsApi.printJson(context.out(), answer);
                return 0;
            }
            List<JsonNode> projects = ProjectsApi.entries(answer, "project");
            if (projects.isEmpty()) {
                context.out().println("No projects.");
                return 0;
            }
            Table.print(context.out(), "", List.of("SLUG", "NAME", "ID"), projects.stream()
                    .map(p -> List.of(Table.cell(text(p, "slug"), 40), Table.cell(text(p, "name"), 40),
                            Table.cell(text(p, "id"), 64)))
                    .toList());
            return 0;
        }
    }
}
