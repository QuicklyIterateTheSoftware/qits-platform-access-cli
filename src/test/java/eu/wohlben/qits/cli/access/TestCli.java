package eu.wohlben.qits.cli.access;

import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/** Runs `qits …` in the test's process, with the context the test gives. */
public final class TestCli {

    private TestCli() {
    }

    public static int execute(CliContext context, String... args) {
        CommandLine cli = new CommandLine(new AccessCli(), new CommandLine.IFactory() {
            @Override
            public <K> K create(Class<K> type) throws Exception {
                K made = CommandLine.defaultFactory().create(type);
                if (made instanceof PlatformCommand command) {
                    command.useContext(context);
                }
                return made;
            }
        });
        cli.setOut(new PrintWriter(context.out(), true, StandardCharsets.UTF_8));
        cli.setErr(new PrintWriter(context.err(), true, StandardCharsets.UTF_8));
        return cli.execute(args);
    }
}
