package eu.wohlben.qits.cli.access.publish;

import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;

/**
 * What every {@code qits artifacts publish} command shares: an environment, a console, and the translation
 * from the ported publish policy's own refusal to the exit code and message qits-publish always
 * gave.
 * <p>
 * <b>This command must never touch the person's session.</b> It runs inside a CI step container
 * with no person signed in, so it reads only {@link CliContext#env()}, {@link CliContext#out()} and
 * {@link CliContext#err()} — never {@link CliContext#sessionFile()} or {@link CliContext#tokens()}.
 * {@code qits login} and {@code qits git-login} do not apply here.
 */
abstract class AbstractPublishCommand extends PlatformCommand {

    @Override
    protected final int execute(CliContext context) throws CliFailure {
        Env env = new Env(context.env());
        Console console = new Console(context.out(), context.err());
        try {
            return run(env, console);
        } catch (CliException e) {
            // The client's own prefix, kept so a step's log reads the same whether it called
            // `qits-publish` or `qits artifacts publish`.
            throw new CliFailure("qits-publish: " + e.getMessage(), e.code());
        }
    }

    protected abstract int run(Env env, Console console);
}
