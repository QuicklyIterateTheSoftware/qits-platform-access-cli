package eu.wohlben.qits.cli.access.publish;

import picocli.CommandLine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** {@code qits artifacts publish daemon} — a daemon binary. */
@CommandLine.Command(name = "daemon", mixinStandardHelpOptions = true,
        subcommands = DaemonCommand.SubmitCommand.class,
        description = "A daemon binary, at (name, version).")
public class DaemonCommand implements Runnable {

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
            description = {"Publish a daemon binary at (name, version).",
                    "Daemon versions are immutable: a re-publish always answers 409, even for identical "
                            + "bytes. The stored digest decides whether that is a re-fire of a run that already "
                            + "succeeded, or two builds claiming one version."},
            footerHeading = "%nExamples:%n",
            footer = "  qits artifacts publish daemon submit --name qits-platform-access-cli --version 2026.906.1 "
                    + "--file target/qits",
            exitCodeListHeading = "%nExit codes:%n",
            exitCodeList = {
                    "0:Published, or already published with the same bytes.",
                    "1:Refused: bad arguments, a 4xx, or the coordinate already holds different bytes.",
                    "2:Could not ask: no store configured, an I/O failure, or a 5xx."})
    public static class SubmitCommand extends AbstractPublishCommand {

        @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true,
                description = "Show this help message and exit.")
        boolean help;

        @CommandLine.Option(names = "--name", paramLabel = "<name>", description = "The daemon's name.")
        List<String> name = new ArrayList<>();

        @CommandLine.Option(names = "--version", paramLabel = "<version>", description = "The version.")
        List<String> version = new ArrayList<>();

        @CommandLine.Option(names = "--file", paramLabel = "<bin>", description = "The binary to publish.")
        List<String> file = new ArrayList<>();

        @CommandLine.Unmatched
        List<String> unmatched = new ArrayList<>();

        @Override
        protected int run(Env env, Console console) {
            PublishArgs.noExtras(unmatched);
            String name = PublishArgs.requiredOnce(this.name, "--name");
            String version = PublishArgs.requiredOnce(this.version, "--version");
            String file = PublishArgs.requiredOnce(this.file, "--file");
            Publisher publisher = new Publisher(http(), Store.from(env, console), console);
            return publisher.daemonSubmit(name, version, Path.of(file));
        }
    }
}
