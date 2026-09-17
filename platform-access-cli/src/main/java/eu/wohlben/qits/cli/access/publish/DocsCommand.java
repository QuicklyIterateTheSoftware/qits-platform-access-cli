package eu.wohlben.qits.cli.access.publish;

import picocli.CommandLine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** {@code qits artifacts publish docs} — a documentation bundle. */
@CommandLine.Command(name = "docs", mixinStandardHelpOptions = true,
        subcommands = DocsCommand.SubmitCommand.class,
        description = "A documentation bundle, at (site, version).")
public class DocsCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    @CommandLine.Command(name = "submit",
            // Not mixinStandardHelpOptions: qits-publish's own --version flag names the artifact
            // version, and the mixin's -V/--version (print the qits binary's own version) would
            // collide with it by name, and picocli then recognises neither. -h/--help alone is safe.
            description = {"Publish a documentation bundle at (site, version).",
                    "The store explodes the archive into per-file blobs and keeps no archive digest, so an "
                            + "occupied version cannot be verified: it is skipped, with a WARN naming the "
                            + "degradation, rather than reported as a plain success."},
            footerHeading = "%nExamples:%n",
            footer = "  qits artifacts publish docs submit --site @apidocs/qits-ci --version 2026.906.1 "
                    + "--archive apidocs.tgz --meta git.commit.hash=deadbeef",
            exitCodeListHeading = "%nExit codes:%n",
            exitCodeList = {
                    "0:Published, or already published (see above: not verified in that case).",
                    "1:Refused: bad arguments, or a 4xx that is not the store's \"already there\".",
                    "2:Could not ask: no store configured, an I/O failure, or a 5xx."})
    public static class SubmitCommand extends AbstractPublishCommand {

        @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true,
                description = "Show this help message and exit.")
        boolean help;

        @CommandLine.Option(names = "--site", paramLabel = "<name>", description = "The docs site's name.")
        List<String> site = new ArrayList<>();

        @CommandLine.Option(names = "--version", paramLabel = "<version>", description = "The version.")
        List<String> version = new ArrayList<>();

        @CommandLine.Option(names = "--archive", paramLabel = "<tgz>",
                description = "The gzipped tar archive to publish.")
        List<String> archive = new ArrayList<>();

        @CommandLine.Option(names = "--meta", paramLabel = "<key=value>",
                description = "A metadata header, sent as X-Artifacts-Meta-<key>. Repeatable.")
        List<String> meta = new ArrayList<>();

        @CommandLine.Unmatched
        List<String> unmatched = new ArrayList<>();

        @Override
        protected int run(Env env, Console console) {
            PublishArgs.noExtras(unmatched);
            String site = PublishArgs.requiredOnce(this.site, "--site");
            String version = PublishArgs.requiredOnce(this.version, "--version");
            String archive = PublishArgs.requiredOnce(this.archive, "--archive");
            Publisher publisher = new Publisher(new Http(), Store.from(env, console), console);
            return publisher.docsSubmit(site, version, Path.of(archive), meta);
        }
    }
}
