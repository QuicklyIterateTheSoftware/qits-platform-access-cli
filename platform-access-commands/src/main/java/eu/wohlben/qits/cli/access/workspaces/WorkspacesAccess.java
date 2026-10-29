package eu.wohlben.qits.cli.access.workspaces;

import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;

/**
 * What a command on qits-workspaces takes from its options: the service, the output form, and the
 * projects service's lookups of a work item and a project. {@link WorkspacesCommand} answers it from
 * the options its subcommands inherit; {@link AgentOptions} from the options each {@code qits agents}
 * verb carries itself. The code that both command trees share ({@link AgentViews}) takes this, so it
 * never asks which tree it runs in.
 */
interface WorkspacesAccess {

    WorkspacesApi api(CliContext context) throws CliFailure, InterruptedException;

    boolean json() throws CliFailure;

    /** A work item's id: a uuid as it is, a qualified id (qits-123) looked up in the projects service. */
    String workId(CliContext context, String given) throws CliFailure, InterruptedException;

    /** A project's uuid, from its id, slug or name. */
    String projectId(CliContext context, String given) throws CliFailure, InterruptedException;
}
