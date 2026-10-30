package eu.wohlben.qits.cli.access.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * A secret value read on stdin: what {@code qits deployments settings set} and {@code qits agent-mcp
 * credentials set} store. Never a flag, because a flag's value stays in the shell's history and in
 * every {@code ps} listing for as long as the command runs.
 * <p>
 * A terminal is refused rather than read: a value typed at a prompt is echoed on the screen, and a
 * person who did not know the command waits for something is better told how to give it. So is
 * nothing at all, since storing an empty value is never what was meant. Exactly one trailing line
 * break is taken off, the one {@code echo} and most editors add; anything before it is the value.
 * <p>
 * No message here holds the value, or any part of it.
 */
public final class StdinValue {

    private StdinValue() {
    }

    /**
     * The value on stdin, without one trailing line break. {@code what} names it in a refusal
     * ({@code "the setting's value"}), and {@code example} shows the command with a pipe in front.
     */
    public static String read(CliContext context, String what, String example) throws CliFailure {
        return read(context.stdinIsTerminal(), context.in(), what, example);
    }

    /** {@link #read(CliContext, String, String)}, with whether stdin is a terminal said: a test cannot make it one. */
    static String read(boolean terminal, java.io.InputStream in, String what, String example) throws CliFailure {
        if (terminal) {
            throw new CliFailure("Pipe " + what + " in on stdin, so it never shows on the screen or in the shell's "
                    + "history: " + example + ". Nothing was sent.", CliFailure.USAGE);
        }
        String text;
        try {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CliFailure("Cannot read " + what + " from stdin. Nothing was sent.", CliFailure.USAGE);
        }
        String value = withoutOneLineBreak(text);
        if (value.isEmpty()) {
            throw new CliFailure("Nothing on stdin: pipe " + what + " in, as in " + example + ". Nothing was sent.",
                    CliFailure.USAGE);
        }
        return value;
    }

    /** {@code text} with one trailing {@code \n} (or {@code \r\n}) taken off, and no more. */
    static String withoutOneLineBreak(String text) {
        if (text.endsWith("\r\n")) {
            return text.substring(0, text.length() - 2);
        }
        if (text.endsWith("\n")) {
            return text.substring(0, text.length() - 1);
        }
        return text;
    }
}
