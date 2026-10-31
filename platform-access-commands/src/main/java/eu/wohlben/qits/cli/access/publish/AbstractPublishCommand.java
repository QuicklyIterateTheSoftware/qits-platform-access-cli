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
 * with no person signed in, so it reads only {@link CliContext#env()}, {@link CliContext#out()} and
 * {@link CliContext#err()} — never {@link CliContext#sessionFile()} or {@link CliContext#tokens()}.
 * {@code qits login} and {@code qits git-login} do not apply here.
 * <p>
 * <b>It does present a machine credential, and that is not the same thing.</b> Only a CI run may
 * publish to qits-artifacts now, so every request goes out with a bearer — from a token command
 * or from a token the step container carries. Both are environment and memory only; neither is a
 * person's session, and nothing here reads or writes a file under the person's config folder.
 * {@link PublishCredential} is where that is decided, once.
 */
abstract class AbstractPublishCommand extends PlatformCommand {

    private PublishCredential credential;

    /**
     * Where the store and the mirror are, when the suite says so; null, as in every real run, means
     * composed from {@code QITS_DOMAIN}. Package-private and set only by the test harness's factory:
     * the seam is a field rather than a variable on purpose, so nothing a step container carries can
     * point a publish anywhere but the platform's public names.
     */
    Store.Hosts hosts;

    @Override
    protected final int execute(CliContext context) throws CliFailure {
        Env env = new Env(context.env());
        Console console = new Console(context.out(), context.err());
        credential = new PublishCredential(env, console);
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

    /** The store this command talks to: the platform's public hosts, from {@code QITS_DOMAIN}. */
    protected final Store store(Env env) {
        if (hosts != null) {
            return new Store(hosts);
        }
        return composed(() -> Store.from(env));
    }

    /** What a public address is composed into, or the refusal naming {@code QITS_DOMAIN} as its exit 2. */
    protected static <T> T composed(Composed<T> address) {
        try {
            return address.get();
        } catch (CliFailure refused) {
            throw new CliException(refused.exitCode(), refused.getMessage());
        }
    }

    /** An address composed from {@code QITS_DOMAIN}, which refuses without one. */
    @FunctionalInterface
    protected interface Composed<T> {
        T get() throws CliFailure;
    }

    protected abstract int run(Env env, Console console);
}
