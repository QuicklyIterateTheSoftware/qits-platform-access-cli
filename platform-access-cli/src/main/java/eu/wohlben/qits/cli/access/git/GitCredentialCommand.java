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
 * <p>
 * It has the CLI's two homes like every other command. On a workstation the sign-in of
 * {@code qits git-login} answers, from {@code git.json}. Inside the platform there is no sign-in to
 * read — {@code qits git-login} needs a browser and is refused there — so the container's own
 * credential answers instead, for the injected git host and no other.
 */
@CommandLine.Command(name = "git-credential", mixinStandardHelpOptions = true,
        description = {
                "Git's credential helper for the platform's git host. Git runs it; a person does not.",
                "On a workstation `qits git-login` sets it up: get prints that sign-in's access token, "
                        + "refreshing it first when needed. store is ignored. erase drops the cached access token "
                        + "and keeps the sign-in.",
                "Inside the platform there is no sign-in and none is needed: get answers the injected git host "
                        + "(QITS_GIT_AUTH_HOST) from the container's own credential, and answers no other host. "
                        + "store and erase do nothing there, and no file is written."},
        footerHeading = HelpText.EXAMPLES,
        footer = {
                "  git config --global --get-all credential.https://githost.dev.wohlben.eu.helper",
                "",
                "The example shows the workstation setup. Do not run `get` yourself: it prints a token on stdout, "
                        + "because that is Git's helper protocol.",
                "Inside the platform Git is set up for every host at once, so the injected host is checked before "
                        + "anything is minted: the platform's bearer is never handed to a host someone else named."},
        exitCodeListHeading = HelpText.EXIT_CODES,
        exitCodeList = {"0:Done. For a host without a sign-in, and inside the platform for any host but the injected "
                + "one, it prints nothing and Git asks its other helpers. A credential that cannot be had says why "
                + "on stderr and still exits 0, so Git carries on.",
                "1:The sign-in file cannot be read or written.",
                "2:No action named."})
public class GitCredentialCommand extends PlatformCommand {

    /** The one git host a container may be handed its bearer for. A bare authority, port included. */
    static final String GIT_HOST = "QITS_GIT_AUTH_HOST";

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
        String protocol = request.get("protocol");
        String origin;
        try {
            origin = GitOrigin.fromGitRequest(protocol, request.get("host"));
        } catch (IllegalArgumentException notAnHttpHost) {
            return 0;
        }
        if (context.mode().inPlatform()) {
            return insidePlatform(context, protocol, origin);
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

    /**
     * The container's answer. No store, no lock, nothing on disk: a {@code client_credentials}
     * token is stateless and minted on demand, and the agent's config folder is becoming a git
     * repository — a machine credential committed to a branch is a leak with a history.
     * <p>
     * {@code store} and {@code erase} therefore have nothing to do. They must not even reach
     * {@link GitCredentialFile}, which would leave {@code git.json.lock} behind.
     */
    private int insidePlatform(CliContext context, String protocol, String origin) throws InterruptedException {
        if (!"get".equals(action) || !injectedHost(context.env(), protocol).filter(origin::equals).isPresent()) {
            return 0;
        }
        try {
            context.out().print("username=oauth2\npassword=" + context.credential().bearer() + "\n\n");
            context.out().flush();
        } catch (CliFailure noCredential) {
            // Git's helper protocol is silence, so an idp that is unreachable or refuses the client
            // is not this command's failure: it says why on stderr, prints nothing, and exits 0 so
            // Git is free to ask its other helpers. A refusal never holds the token or the secret.
            context.err().println(noCredential.getMessage());
            context.err().flush();
        }
        return 0;
    }

    /**
     * The one host this credential may be handed to, in the same normal form the request is read
     * into, so a git host configured with an explicit default port still matches Git's bare
     * {@code host} line.
     * <p>
     * <b>It fails closed.</b> The agent image sets a <i>global</i> {@code credential.helper}, so
     * Git runs this for every http remote a checked-out repository names, submodule remotes that
     * somebody else authored included. With no variable to compare against, "any host" would hand
     * the platform's bearer to whoever wrote the remote, so a missing or unreadable value answers
     * nothing at all.
     */
    private static Optional<String> injectedHost(Map<String, String> env, String protocol) {
        String told = env.get(GIT_HOST);
        if (told == null || told.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(GitOrigin.normalize(told.contains("://") ? told : protocol + "://" + told.strip()));
        } catch (IllegalArgumentException notAnHttpHost) {
            return Optional.empty();
        }
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
