package eu.wohlben.qits.cli.access.report;

import eu.wohlben.qits.cli.access.ci.CiCommand;
import picocli.CommandLine;

/**
 * {@code qits ci report}: release reports, the structured results a release request's QA run produces
 * beside its verdict. {@code submit} is the CI step's side, {@code show} everybody else's.
 */
@CommandLine.Command(name = "report", mixinStandardHelpOptions = true,
        subcommands = {SubmitCommand.class, ShowCommand.class},
        description = {"Release reports: what a release request's QA run found, beside its verdict. show lists a "
                        + "run's reports with their highlights, and prints one kind's whole report. submit is what "
                        + "every QA step runs after its script: it collects the reports from the step's files and "
                        + "uploads them.",
                "Kinds today: test-results (every test run, and each failing test with its class, name, file and "
                        + "message), coverage (line coverage of the whole tree, its change against the baseline, "
                        + "and the coverage of the lines the change touched) and contracts (the pacts the repository "
                        + "holds as consumer or verifies as provider, and the provider states it declares, with the "
                        + "pacts, interactions and states added or removed since the baseline). A kind or a run with "
                        + "nothing to report shows nothing; a report never fails or holds a release."},
        footerHeading = "%nNotes:%n",
        footer = {"- Each kind and its highlights are computed by the CLI that submitted them; qits-ci keeps them as "
                + "they came, keyed by run, step and kind.",
                "- A report compares with its baseline: the same kind in the QA run of the release request that "
                        + "produced the repository's newest released version. A first release has none."})
public class ReportCommand implements Runnable {

    @CommandLine.ParentCommand
    CiCommand ci;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    /** The {@code ci} group, whose inherited options the commands here read. */
    CiCommand ci() {
        return ci;
    }

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }
}
