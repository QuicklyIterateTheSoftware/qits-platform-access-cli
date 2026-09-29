package eu.wohlben.qits.cli.access.projects;

import eu.wohlben.qits.cli.access.platform.CliFailure;
import picocli.CommandLine;

/**
 * {@code qits work}: any work entity — ticket, epic, feature, task or campaign — named once with
 * {@code --entity}, whatever its kind. Today it holds the comment thread only; the entity's own
 * create, update, transition, list and details are to join it as further subcommands here, beside
 * {@code comment}, in the same form: a JSON payload on stdin, and its schema when nothing is put in
 * ({@link WorkPayload}).
 * <p>
 * No {@code --project}: the service resolves an entity's id or qualified id itself, and an agent is
 * held to its own project there, not here.
 */
@CommandLine.Command(name = "work", mixinStandardHelpOptions = true,
        subcommands = {WorkCommentCommand.class},
        description = {"Any work entity - a ticket, an epic, a feature, a task or a campaign - named with --entity. "
                        + "comment writes to and edits the entity's comment thread.",
                "A write reads its payload, a JSON document, on stdin and sends it to the service as it is. With "
                        + "nothing on stdin (a terminal, or empty) it sends nothing and prints the payload's JSON "
                        + "schema instead, read from the service's own OpenAPI document."},
        footerHeading = "%nNotes:%n",
        footer = {
                "- --entity, --output and --projects-url may come before or after the command.",
                "- --entity is the entity's id or its qualified id (qits-100); the service resolves either. There "
                        + "is no --project.",
                "- Commenting takes qits:admin or qits:agent. An agent writes only in its own project."})
public class WorkCommand implements Runnable {

    static final String NAME_THE_ENTITY = "Name the entity: --entity <id or qualified id, like qits-100>.";

    @CommandLine.Mixin
    ProjectsOptions options;

    @CommandLine.Option(names = "--entity", paramLabel = "<entity>", scope = CommandLine.ScopeType.INHERIT,
            description = "The work entity: its id or its qualified id (qits-100), passed to the service as it is.")
    String entity;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    /** The entity as given, without surrounding blanks; a usage error without one. */
    String entity() throws CliFailure {
        return RepositoriesCommand.required(entity, NAME_THE_ENTITY);
    }
}
