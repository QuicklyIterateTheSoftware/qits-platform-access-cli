package eu.wohlben.qits.cli.tui;

import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.session.Session;
import eu.wohlben.qits.cli.access.session.TokenClaims;
import eu.wohlben.qits.cli.tui.complete.Completions;
import eu.wohlben.qits.cli.tui.model.CommandNode;
import eu.wohlben.qits.cli.tui.run.CommandRunner;
import eu.wohlben.qits.cli.tui.screen.Frame;
import eu.wohlben.qits.cli.tui.screen.Glyphs;
import eu.wohlben.qits.cli.tui.screen.Key;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.Display;
import org.jline.utils.InfoCmp;
import picocli.CommandLine;

import java.io.IOException;
import java.net.URI;
import java.util.Optional;

/**
 * {@code qits tui}: every command of this CLI, picked instead of remembered.
 * <p>
 * It knows no command. The tree comes from picocli's own model — the same model that makes {@code
 * --help} and {@code SKILL.md} — so a command added to the CLI appears here with nothing written in
 * this package.
 * <p>
 * The terminal is JLine's {@code exec} provider, asked for by name. The other providers call libc
 * through JNI or the foreign function API and neither survives a native image; {@code exec} shells
 * {@code /bin/stty}, which a binary that already shells {@code git} can do. The attributes it
 * changes are restored in a shutdown hook, so a crash never leaves the terminal mute.
 */
@CommandLine.Command(name = "tui", mixinStandardHelpOptions = true,
        description = {"Pick a command instead of remembering it: an interactive screen over every qits command.",
                "The upper half is the picker: the commands, then the options of the one chosen, with the "
                        + "values the platform can offer. The command being built is shown between the halves, so "
                        + "the screen also teaches the command line. The lower half is the output of what was run."},
        footerHeading = HelpText.EXAMPLES,
        footer = {
                "  qits tui",
                "",
                "- Keys: arrows or k/j move, Enter chooses, Esc goes back, / filters, Ctrl-R runs, Ctrl-C stops a "
                        + "run, Ctrl-P shows this session's history, q quits.",
                "- Needs an interactive terminal of at least 80x24. In a pipe or a CI step it says so and exits 2.",
                "- It runs a command by starting this same binary again, so a run behaves exactly as it does when "
                        + "typed."},
        exitCodeListHeading = HelpText.EXIT_CODES,
        exitCodeList = {HelpText.DONE, "1:Unused.",
                "2:There is no interactive terminal, or it is smaller than 80x24."})
public class TuiCommand extends PlatformCommand {

    static final String NO_TERMINAL = "qits tui needs an interactive terminal.";

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    protected int execute(CliContext context) throws CliFailure, InterruptedException {
        if ("0".equals(context.env().get(CommandRunner.TUI_ENV)) || "dumb".equals(context.env().get("TERM"))) {
            context.err().println(NO_TERMINAL);
            return CliFailure.USAGE;
        }
        CommandNode root = CommandNode.of(spec.root());
        String header = header(context);
        try (Terminal terminal = TerminalBuilder.builder().provider("exec").system(true).dumb(false).build()) {
            int width = terminal.getWidth();
            int height = terminal.getHeight();
            if (width <= 0 || height <= 0) {
                context.err().println(NO_TERMINAL);
                return CliFailure.USAGE;
            }
            if (!Frame.fits(width, height)) {
                context.err().println(Frame.tooSmall(width, height));
                return CliFailure.USAGE;
            }
            loop(terminal, new TuiApp(root, header, new CommandRunner(), completions()));
            return 0;
        } catch (IOException | RuntimeException noTerminal) {
            // JLine refuses a system terminal in a pipe, a CI step or a `docker build` with an
            // IllegalStateException, not an IOException. Both mean the same thing here, and neither
            // may become a stack trace on a person's screen.
            context.err().println(NO_TERMINAL + " (" + noTerminal.getMessage() + ")");
            return CliFailure.USAGE;
        }
    }

    /**
     * The completion sources, found the way every other bean in this program is found. A container
     * that cannot hand one over is not a reason to refuse the screen: the options it would have
     * filled are typed instead.
     */
    private Completions completions() {
        return new Completions(type -> jakarta.enterprise.inject.spi.CDI.current().select(type).get());
    }

    /**
     * Raw mode and the alternate screen for as long as the loop runs. Both are undone twice over:
     * in the finally below for the ordinary end, and in a shutdown hook for the end that is not
     * ordinary — a terminal left in raw mode echoes nothing and looks broken.
     */
    private void loop(Terminal terminal, TuiApp app) throws IOException {
        Attributes saved = terminal.enterRawMode();
        Thread restore = new Thread(() -> restore(terminal, saved), "qits-tui-restore");
        Runtime.getRuntime().addShutdownHook(restore);
        terminal.puts(InfoCmp.Capability.enter_ca_mode);
        terminal.puts(InfoCmp.Capability.cursor_invisible);
        terminal.flush();
        Frame frame = new Frame(Glyphs.forEncoding(terminal.encoding()));
        Display display = new Display(terminal, true);
        try {
            int width = 0;
            int height = 0;
            while (app.running()) {
                if (terminal.getWidth() != width || terminal.getHeight() != height) {
                    width = terminal.getWidth();
                    height = terminal.getHeight();
                    display.clear();
                    display.resize(height, width);
                }
                display.update(frame.render(app.view(), width, height), -1);
                app.key(Key.read(terminal.reader()));
            }
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(restore);
            } catch (IllegalStateException alreadyShuttingDown) {
                // The hook is running; it does the same work.
            }
            restore(terminal, saved);
        }
    }

    private static void restore(Terminal terminal, Attributes saved) {
        try {
            terminal.setAttributes(saved);
            terminal.puts(InfoCmp.Capability.cursor_normal);
            terminal.puts(InfoCmp.Capability.exit_ca_mode);
            terminal.flush();
        } catch (RuntimeException leaveItAlone) {
            // Nothing better to do while the process is ending.
        }
    }

    /** {@code qits tui · dev.wohlben.eu · signed in as jan}, or what there is of it. */
    String header(CliContext context) {
        Optional<Session> session = readSession(context);
        String where = session.map(s -> domain(s.idpUrl())).orElse("no session");
        String who = session.flatMap(s -> TokenClaims.of(s.accessToken()).who())
                .map(name -> "signed in as " + name)
                .orElse("not signed in");
        return "qits tui \u00b7 " + where + " \u00b7 " + who;
    }

    /** Browsing the tree needs no session, so an unreadable one is nothing to fail over. */
    private Optional<Session> readSession(CliContext context) {
        try {
            return context.sessionFile().read();
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /** {@code https://idp.dev.wohlben.eu/idp} is {@code dev.wohlben.eu}. */
    static String domain(String idpUrl) {
        try {
            String host = URI.create(idpUrl == null ? "" : idpUrl.strip()).getHost();
            if (host == null) {
                return "no session";
            }
            return host.startsWith("idp.") ? host.substring("idp.".length()) : host;
        } catch (IllegalArgumentException notAUrl) {
            return "no session";
        }
    }
}
