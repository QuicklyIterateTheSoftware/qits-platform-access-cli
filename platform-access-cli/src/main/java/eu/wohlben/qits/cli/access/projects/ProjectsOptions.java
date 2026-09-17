package eu.wohlben.qits.cli.access.projects;

import picocli.CommandLine;

/**
 * The options every command on the projects service takes. INHERIT puts them on the subcommands
 * too, so they may come before or after {@code list}; either way the value lands here.
 * <p>
 * No {@code defaultValue}: a subcommand's copy would apply it again after the parent's value was
 * set. Null means the default.
 */
public class ProjectsOptions {

    @CommandLine.Option(names = "--projects-url", paramLabel = "<url>", scope = CommandLine.ScopeType.INHERIT,
            description = "The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, "
                    + "else the session's idp address with `idp` swapped for `projects` "
                    + "(https://idp.dev.wohlben.eu/idp gives https://projects.dev.wohlben.eu).")
    String projectsUrl;

    @CommandLine.Option(names = {"-o", "--output"}, paramLabel = "table|json", scope = CommandLine.ScopeType.INHERIT,
            description = "table (the default): aligned columns. json: the service's answer, pretty-printed.")
    String output;
}
