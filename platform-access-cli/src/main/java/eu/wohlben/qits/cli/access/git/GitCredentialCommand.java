package eu.wohlben.qits.cli.access.git;

import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Git's credential helper protocol: Git writes {@code key=value} lines and an empty line to stdin,
 * and reads the answer from stdout.
 * <p>
 * <b>{@code get} is the one place {@code qits} prints a token</b>, because the protocol is exactly
 * that. stderr never carries one.
 */
@CommandLine.Command(name = "git-credential", mixinStandardHelpOptions = true,
        description = {
                "Git's credential helper for the platform's git host. Git runs it; a person does not. "
                        + "`qits git-login` sets it up.",
                "get prints the stored sign-in's access token for Git, refreshing it first when needed. "
                        + "store is ignored. erase drops the cached access token and keeps the sign-in."},
        footerHeading = HelpText.EXAMPLES,
        footer = {
                "  git config --global --get-all credential.https://githost.dev.wohlben.eu.helper",
                "",
                "The example shows the setup. Do not run `get` yourself: it prints a token on stdout, because that is "
                        + "Git's helper protocol."},
        exitCodeListHeading = HelpText.EXIT_CODES,
        exitCodeList = {"0:Done. For a host without a sign-in it prints nothing, and Git asks its other helpers.",
                "1:The sign-in file cannot be read or written.",
                "2:No action named."})
public class GitCredentialCommand extends PlatformCommand {

    @CommandLine.Parameters(index = "0", paramLabel = "<get|store|erase>", description = "What Git asks for.")
    String action;

    @Override
    protected int execute(CliContext context) throws CliFailure, InterruptedException {
        Map<String, String> request;
        try {
            request = readRequest(context.in());
        } catch (IOException e) {
            return 0;
        }
        String origin;
        try {
            origin = GitOrigin.fromGitRequest(request.get("protocol"), request.get("host"));
        } catch (IllegalArgumentException notAnHttpHost) {
            return 0;
        }
        GitAccess access = new GitAccess(GitCredentialFile.fromEnvironment(context.env()), context.clock());
        try {
            switch (action) {
                case "get" -> {
                    Optional<String> token = access.accessToken(origin, context.err());
                    if (token.isPresent()) {
                        context.out().print("username=oauth2\npassword=" + token.get() + "\n\n");
                        context.out().flush();
                    }
                }
                case "erase" -> access.erase(origin, request.get("password"));
                default -> {
                    // `store` and any action Git adds later: only `qits git-login` stores a sign-in.
                }
            }
        } catch (IOException e) {
            throw new CliFailure(e.getMessage(), CliFailure.FAILED);
        }
        return 0;
    }

    static Map<String, String> readRequest(InputStream in) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        BufferedReader input = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        for (String line; (line = input.readLine()) != null && !line.isEmpty(); ) {
            int separator = line.indexOf('=');
            if (separator > 0) {
                result.put(line.substring(0, separator), line.substring(separator + 1));
            }
        }
        return result;
    }
}
