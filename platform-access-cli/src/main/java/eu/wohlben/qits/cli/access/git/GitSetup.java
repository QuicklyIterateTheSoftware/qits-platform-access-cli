package eu.wohlben.qits.cli.access.git;

import eu.wohlben.qits.cli.access.platform.CliFailure;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The Git setup for ONE origin. A person's global helper (on this workstation, Windows Git
 * Credential Manager for GitHub) must stay, so nothing is set for every host. The empty value
 * first clears the helper list for this origin; {@code --replace-all} makes that work however many
 * values are there, so running the setup again changes nothing.
 */
public final class GitSetup {

    private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9_./:=@%+,-]+");

    private GitSetup() {
    }

    /** The two {@code git config} commands, as argument lists. */
    public static List<List<String>> commands(String gitOrigin, String executable) {
        String key = "credential." + gitOrigin + ".helper";
        String program = PLAIN.matcher(executable).matches() ? executable : "\"" + executable.replace("\"", "\\\"") + "\"";
        return List.of(
                List.of("git", "config", "--global", "--replace-all", key, ""),
                List.of("git", "config", "--global", "--add", key, "!" + program + " git-credential"));
    }

    /** One command as a person pastes it into a shell. */
    public static String shellLine(List<String> argv) {
        return argv.stream()
                .map(a -> PLAIN.matcher(a).matches() ? a : "'" + a.replace("'", "'\\''") + "'")
                .collect(Collectors.joining(" "));
    }

    /**
     * Runs the commands. {@code env} is laid over this process's environment, so a test can point
     * {@code GIT_CONFIG_GLOBAL} at a scratch file.
     */
    public static void configure(List<List<String>> commands, Map<String, String> env, PrintStream err)
            throws CliFailure, InterruptedException {
        for (List<String> argv : commands) {
            ProcessBuilder builder = new ProcessBuilder(argv).redirectErrorStream(true);
            builder.environment().putAll(env);
            try {
                Process process = builder.start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                int exit = process.waitFor();
                if (exit != 0) {
                    throw new CliFailure("`" + shellLine(argv) + "` failed (exit code " + exit + ")"
                            + (output.isEmpty() ? "" : ": " + output), CliFailure.FAILED);
                }
            } catch (IOException e) {
                throw new CliFailure("Cannot run git: " + e.getMessage(), CliFailure.FAILED);
            }
        }
    }
}
