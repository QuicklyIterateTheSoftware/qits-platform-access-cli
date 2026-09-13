package eu.wohlben.qits.cli.access.publish;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The argv surface itself: what a mistyped invocation does. Ported from qits-artifacts-cli's own
 * {@code CommandSurfaceTest}; most of it holds unchanged, because {@link PublishArgs} reproduces
 * qits-publish's own flag grammar (required-once, refuse-on-repeat, refuse-unknown) by hand rather
 * than trusting picocli's built-in option validation, which is what keeps the exit codes and
 * messages below identical to qits-publish's own.
 * <p>
 * Three cases <b>do</b> differ from qits-publish's own dispatcher, and are marked below: picocli
 * itself, not {@link PublishArgs}, is what answers "no command was named", "that command does not
 * exist" and "that flag was given with nothing after it" — because those are refused before a
 * command's own {@code run(Env, Console)} ever executes, the same way every other command group in
 * this CLI (`qits ticket`, `qits help`, `qits` itself) already lets picocli answer them, at picocli's
 * own exit code (2) rather than qits-publish's (1).
 */
class CommandSurfaceTest {

    private final Harness cli = new Harness().with("QITS_ARTIFACTS_URL", "http://127.0.0.1:1");

    @Test
    void noCommandIsRefusedByPicocliTheWayEveryOtherCommandGroupHereIs() {
        Harness.Run run = cli.run();
        assertEquals(2, run.code());
        assertTrue(run.errContains("Name a command"), run.err());
    }

    @Test
    void helpIsAskedForOnPurposeSoItIsTheAnswerAndGoesToStdout() {
        Harness.Run run = cli.run("--help");
        assertEquals(0, run.code());
        assertTrue(run.out().contains("Exit codes:"), run.out());
        assertTrue(run.out().contains("QITS_ARTIFACTS_URL"), run.out());
    }

    @Test
    void anUnknownCommandIsRefusedByPicocliBeforeAnyPublishCodeRuns() {
        Harness.Run run = cli.run("upload", "--file", "x");
        assertEquals(2, run.code());
        assertTrue(run.errContains("upload"), run.err());
    }

    @Test
    void anUnknownSubcommandIsRefusedByPicocliBeforeAnyPublishCodeRuns() {
        Harness.Run run = cli.run("npm", "publish");
        assertEquals(2, run.code());
        assertTrue(run.errContains("publish"), run.err());
    }

    @Test
    void aMistypedFlagIsRefusedRatherThanIgnored() {
        Harness.Run run =
                cli.run("sbom", "submit", "--type", "docker", "--name", "x", "--verison", "1", "--file", "f");
        assertEquals(ExitCode.POLICY, run.code());
        assertTrue(run.errContains("--version is required") || run.errContains("unknown option --verison"),
                run.err());
    }

    @Test
    void aMissingRequiredFlagNamesItself() {
        Harness.Run run = cli.run("daemon", "submit", "--name", "x", "--file", "f");
        assertEquals(ExitCode.POLICY, run.code());
        assertTrue(run.errContains("--version is required"), run.err());
    }

    @Test
    void aFlagWithNoValueIsRefusedByPicocliBeforePublishArgsSeesIt() {
        // qits-publish itself answered this with `--name needs a value` at exit 1; picocli's own
        // parser answers it before `run(Env, Console)` is reached, at picocli's usage exit code (2).
        Harness.Run run = cli.run("daemon", "submit", "--name");
        assertEquals(2, run.code());
        assertTrue(run.errContains("--name"), run.err());
    }

    @Test
    void equalsFormIsAcceptedForTheSameFlags() {
        Harness.Run run = cli.run("npm", "plan", "--package=@qits/x");
        assertEquals(ExitCode.POLICY, run.code());
        assertTrue(run.errContains("--version is required"), run.err());
    }

    @Test
    void aRepeatedSingleValueFlagIsRefusedRatherThanSilentlyLastWins() {
        Harness.Run run =
                cli.run("daemon", "submit", "--name", "a", "--name", "b", "--version", "1", "--file", "f");
        assertEquals(ExitCode.POLICY, run.code());
        assertTrue(run.errContains("--name was given more than once"), run.err());
    }

    @Test
    void anUnreachableStoreIsCouldNotAskRatherThanRefused() {
        // 127.0.0.1:1 refuses the connection immediately; that is a transport fact, not a policy one.
        Harness.Run run = cli.run("exists", "daemon", "qits-ci-daemon", "1.2.3");
        assertEquals(ExitCode.TRANSPORT, run.code());
        assertTrue(run.errContains("failed"), run.err());
    }

    @Test
    void helpWorksOnEveryCommandThatHasItsOwnVersionFlag() {
        // Regression: these commands cannot use mixinStandardHelpOptions, because its -V/--version
        // (the binary's own version) collides by name with qits-publish's own --version (the
        // artifact version) — and picocli then recognises neither --version nor --help at all,
        // silently routing --help into @Unmatched instead. See AGENTS.md.
        for (String[] argv : new String[][] {
                {"sbom", "submit", "--help"},
                {"docs", "submit", "--help"},
                {"daemon", "submit", "--help"},
                {"npm", "plan", "--help"},
                {"npm", "dist-tag", "--help"}}) {
            Harness.Run run = cli.run(argv);
            String command = String.join(" ", argv);
            assertEquals(0, run.code(), command + " -> " + run.err());
            assertTrue(run.out().contains("Usage:"), command + " -> " + run.out());
            assertTrue(run.err().isEmpty(), command + " -> " + run.err());
        }
    }

    @Test
    void anExtraPositionalArgumentIsRefused() {
        Harness.Run run = cli.run("sbom", "submit", "--type", "docker", "--name", "x", "--version", "1",
                "--file", "f", "extra");
        assertEquals(ExitCode.POLICY, run.code());
        assertTrue(run.errContains("unexpected argument extra"), run.err());
    }
}
