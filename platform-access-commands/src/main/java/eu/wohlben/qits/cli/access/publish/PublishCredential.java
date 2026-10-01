package eu.wohlben.qits.cli.access.publish;

import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.session.AgentCredential;
import eu.wohlben.qits.cli.session.Mode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The bearer a publish presents to qits-artifacts, and the only place that decides where it comes
 * from.
 *
 * <p>The store used to answer an anonymous publish; it does not any more. Only a CI run may write a
 * coordinate, so every request this program makes carries a token, and the four sources below are
 * tried in order — the first that answers wins.
 *
 * <ol>
 *   <li><b>{@code QITS_PUBLISH_TOKEN_COMMAND}</b> — an executable that prints a fresh token on
 *       stdout. Preferred, and <em>re-run for every request</em> rather than once per process: a
 *       long release step can publish an hour after it started, and a token minted at the top of the
 *       step would be expired by then. qits-ci writes {@code /tmp/qits-publish-token} and points
 *       this at it.
 *   <li><b>{@code QITS_PUBLISH_TOKEN}</b> — a token, verbatim. For a step that already holds one and
 *       for driving this client by hand.
 *   <li><b>The commissioned pair</b> — {@code QITS_COMMISSIONED_CLIENT_ID} and
 *       {@code QITS_COMMISSIONED_CLIENT_SECRET}, exchanged at the internal idp through
 *       {@link AgentCredential}, which is the same minter every other {@code qits} command uses.
 *       There is deliberately no second minter here.
 *   <li><b>Nothing</b> — no {@code Authorization} header at all, exactly as this client always
 *       behaved. <b>A publish with no credential is still attempted, never refused here.</b> The
 *       store is the authority on who may write; failing locally would turn its 401 — which names
 *       the real reason — into a client-side error that names a variable instead.
 * </ol>
 *
 * <p><b>Nothing here ever prints a token.</b> A refusal names the command or the idp's own status
 * and {@code error}, and never what came back on stdout, because what came back on stdout is the
 * token.
 */
final class PublishCredential {

    /** An executable that prints a fresh token on stdout. */
    static final String TOKEN_COMMAND = "QITS_PUBLISH_TOKEN_COMMAND";

    /** A token, verbatim. */
    static final String TOKEN = "QITS_PUBLISH_TOKEN";

    /** How long a token command may take. Generous: it may be minting at the idp. */
    private static final Duration MINT_TIMEOUT = Duration.ofSeconds(30);

    private final Env env;
    private final Console console;
    private final Clock clock;

    PublishCredential(Env env, Console console, Clock clock) {
        this.env = env;
        this.console = console;
        this.clock = clock;
    }

    /** The token to present, or empty when this environment holds no credential at all. */
    Optional<String> bearer() {
        String command = env.get(TOKEN_COMMAND);
        if (command != null) {
            String minted = fromCommand(command);
            if (minted != null) {
                return Optional.of(minted);
            }
        }
        String told = env.get(TOKEN);
        if (told != null) {
            return Optional.of(told.strip());
        }
        if (env.get(Mode.CLIENT_ID) != null && env.get(Mode.CLIENT_SECRET) != null) {
            return Optional.of(commissioned());
        }
        return Optional.empty();
    }

    /**
     * Run the token command and read its stdout.
     *
     * <p>No shell: the value is the executable itself, one argument, so a name carrying a space is a
     * path with a space in it rather than two words, and nothing in it can become a second command.
     *
     * <p>A value that names a path nothing can execute returns {@code null} — a WARN and the next
     * source — because a variable pointing at a script a step never wrote is a misconfiguration the
     * commissioned pair may well cover. A command that <em>does</em> run and then fails is the
     * opposite case: something was meant to mint and could not, and going on quietly without a
     * credential would report that as a store refusal much later.
     */
    private String fromCommand(String command) {
        if (command.contains("/") && !Files.isExecutable(Path.of(command))) {
            console.warn(
                    TOKEN_COMMAND
                            + " names "
                            + command
                            + ", which is not an executable file — looking for a credential elsewhere.");
            return null;
        }
        Process process;
        try {
            process =
                    new ProcessBuilder(command)
                            // stderr passes through so a minter that cannot reach the idp says so in the
                            // step's log. stdout is captured and never printed: stdout is the token.
                            .redirectError(ProcessBuilder.Redirect.INHERIT)
                            .start();
        } catch (IOException cannotStart) {
            console.warn(
                    TOKEN_COMMAND
                            + " ("
                            + command
                            + ") could not be started: "
                            + cannotStart.getMessage()
                            + " — looking for a credential elsewhere.");
            return null;
        }
        // Read on a thread of its own so the timeout below is a real one. Reading to the end on this
        // thread would wait for the pipe to close, which a wedged minter never does, and the timeout
        // would then never be reached.
        StringBuilder printed = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (InputStream out = process.getInputStream()) {
                printed.append(new String(out.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException closed) {
                // The process was killed, or its pipe went. The exit status below says what happened.
            }
        }, "qits-publish-token");
        reader.setDaemon(true);
        reader.start();
        int status;
        try {
            if (!process.waitFor(MINT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw CliException.transport(
                        TOKEN_COMMAND + " (" + command + ") did not finish within " + MINT_TIMEOUT.toSeconds()
                                + " seconds");
            }
            status = process.exitValue();
            reader.join(MINT_TIMEOUT.toMillis());
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw CliException.transport(TOKEN_COMMAND + " (" + command + ") was interrupted", interrupted);
        }
        String token = printed.toString().strip();
        if (status != 0) {
            // The status and the command, and nothing of what it wrote: what it wrote is a token.
            throw CliException.transport(
                    TOKEN_COMMAND + " (" + command + ") exited " + status + " without minting a token");
        }
        if (token.isEmpty()) {
            throw CliException.transport(
                    TOKEN_COMMAND + " (" + command + ") exited 0 but printed nothing on stdout");
        }
        return token;
    }

    /**
     * The commissioned pair, through the CLI's one minter. {@link AgentCredential} holds the token
     * for its hour and mints again inside the last minute of it, so a step that publishes several
     * coordinates goes to the idp once.
     */
    private String commissioned() {
        try {
            return AgentCredential.of(env.values(), clock).bearer();
        } catch (CliFailure refused) {
            String message = "cannot mint a publish token from " + Mode.CLIENT_ID + "/"
                    + Mode.CLIENT_SECRET + ": " + refused.getMessage();
            // Retryable is the idp being unreachable — the question could not be put. Anything else
            // is the idp answering no, which asking again will not change.
            throw refused.retryable()
                    ? CliException.transport(message)
                    : CliException.policy(message);
        }
    }
}
