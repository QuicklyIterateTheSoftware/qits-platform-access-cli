package eu.wohlben.qits.cli.access.contracts;

import eu.wohlben.qits.cli.access.projects.ProjectsContract;

import java.util.List;

/**
 * <b>Every provider the commands call, with its contract</b> (ticket qits-1149): one {@link CliPact}
 * each. {@code ClientCallsGuardTest} reads this list, so a provider's rows count only once it is
 * here; each also has its own {@link CliPactTest} beside its rows.
 */
public final class Contracts {

    public static final List<CliPact> ALL = List.of(
            ProjectsContract.CONTRACT);

    private Contracts() {
    }
}
