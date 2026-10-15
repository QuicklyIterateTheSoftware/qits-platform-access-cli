package eu.wohlben.qits.cli.access.agents;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Starts this binary again in the background and does not wait for it. What the hook and the status
 * line use to refresh a cache without making Claude wait for the network, and what starts the
 * automatic {@code qits update --background}.
 */
public final class Detached {

    private Detached() {
    }

    /**
     * Runs {@code qits <args>} in a session of its own (setsid), so Claude ending the caller does not
     * end it, with no stdin, stdout or stderr. A JVM is not the binary (tests, the dev loop), so it
     * starts nothing there. A failure starts nothing; the caller's touched cache tries again later.
     */
    public static void start(List<String> args) {
        try {
            Optional<Path> self = self();
            if (self.isEmpty()) {
                return;
            }
            List<String> command = new ArrayList<>();
            for (String setsid : List.of("/usr/bin/setsid", "/bin/setsid")) {
                if (Files.isExecutable(Path.of(setsid))) {
                    command.add(setsid);
                    break;
                }
            }
            command.add(self.get().toString());
            command.addAll(args);
            new ProcessBuilder(command)
                    .redirectInput(new File("/dev/null"))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (Exception | LinkageError cannot) {
            // Nothing started this time.
        }
    }

    /**
     * The file this process runs from, symlinks resolved ({@code /proc/self/exe}), when it is this
     * binary. Empty in a JVM, whose executable is {@code java}, and where {@code /proc} cannot say.
     */
    public static Optional<Path> self() {
        try {
            Path self = Path.of("/proc/self/exe").toRealPath();
            String name = self.getFileName().toString();
            if (name.equals("java") || name.startsWith("java.")) {
                return Optional.empty();
            }
            return Optional.of(self);
        } catch (Exception | LinkageError cannot) {
            return Optional.empty();
        }
    }
}
