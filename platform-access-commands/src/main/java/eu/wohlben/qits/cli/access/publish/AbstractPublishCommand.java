package eu.wohlben.qits.cli.access.publish;

import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;

/**
 * What every {@code qits artifacts publish} command shares: an environment, a console, the credential
 * its requests carry, and the translation from the ported publish policy's own refusal to the exit
 * code and message qits-publish always gave.
 * <p>
 * <b>This command must never touch the person's session.</b> It runs inside a CI step container
 * with no person signed in, so it reads only {@link CliContext#env()}, {@link CliContext#out()},
 * {@link CliContext#err()} and {@link CliContext#clock()} — never {@link CliContext#sessionFile()}
 * or {@link CliContext#tokens()}. {@code qits login} and {@code qits git-login} do not apply here.
 * <p>
 * <b>It does present a machine credential, and that is not the same thing.</b> Only a CI run may
 * publish to qits-artifacts now, so every request goes out with a bearer — from a token command,
 * from a token, or minted from the commissioned client pair the step container carries. All three
 * are environment and memory only; none of them is a person's session, and nothing here reads or
 * writes a file under the person's config folder. {@link PublishCredential} is where that is
 * decided, once.
 */
abstract class AbstractPublishCommand extends PlatformCommand {

    private PublishCredential credential;

    @Override
    protected final int execute(CliContext context) throws CliFailure {
        Env env = new Env(context.env());
        Console console = new Console(context.out(), context.err());
        credential = new PublishCredential(env, console, context.clock());
        try {
            return run(env, console);
        } catch (CliException e) {
            // The client's own prefix, kept so a step's log reads the same whether it called
            // `qits-publish` or `qits artifacts publish`.
            throw new CliFailure("qits-publish: " + e.getMessage(), e.code());
        }
    }

    /**
     * The wire, credentialled. Every command that talks to the store builds its client from here
     * rather than with {@code new Http(...)}, so there is one answer to "what does a publish
     * present" and no way to get an anonymous client by forgetting an argument.
     */
    protected final Http http() {
        return new Http(credential);
    }

    protected abstract int run(Env env, Console console);
}
