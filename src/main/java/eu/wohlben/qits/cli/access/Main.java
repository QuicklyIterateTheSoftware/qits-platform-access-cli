package eu.wohlben.qits.cli.access;

import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The process entry point. Its only job beyond picocli's own is a name check: a binary or a
 * symlink started as {@code qits-publish} behaves as {@code qits artifacts publish}, so a
 * hand-written pipeline that still calls it by that name — qits-artifacts-cli's own binary name,
 * before it folded into {@code qits} — keeps working.
 */
@QuarkusMain
public class Main implements QuarkusApplication {

    /** The name a hand-written pipeline may still call this binary by. */
    static final String PUBLISH_ALIAS = "qits-publish";

    @Inject
    CommandLine commandLine;

    @Override
    public int run(String... args) throws Exception {
        return commandLine.execute(effectiveArgs(args, invokedAs()));
    }

    /**
     * {@code args} unchanged, unless {@code invokedAs} is {@value #PUBLISH_ALIAS} — then {@code
     * artifacts publish} goes in front, so every argument reaches {@code qits artifacts publish} the
     * way it would have reached {@code qits-publish} itself. A function of its input, so the name
     * check itself ({@link #invokedAs()}, which reads {@code /proc}) needs no process trickery to
     * test.
     */
    static String[] effectiveArgs(String[] args, String invokedAs) {
        if (!PUBLISH_ALIAS.equals(invokedAs)) {
            return args;
        }
        String[] withGroup = new String[args.length + 2];
        withGroup[0] = "artifacts";
        withGroup[1] = "publish";
        System.arraycopy(args, 0, withGroup, 2, args.length);
        return withGroup;
    }

    /**
     * The file name this process was started with — the name of the symlink or copy that was run,
     * not the file it resolves to.
     * <p>
     * Read from {@code /proc/self/cmdline} (Linux and WSL, which is all this binary targets):
     * argv[0] there is exactly what was passed to execve, unresolved. {@link ProcessHandle}'s own
     * {@code info().command()} is not that on Linux — it is sourced from {@code /proc/self/exe},
     * which resolves a symlink to its target and would report the target's name instead, defeating
     * a symlink named {@code qits-publish}. Proven against both a symlink and a copy before this
     * shipped; kept as a fallback for the one case {@code /proc} is not there.
     */
    static String invokedAs() {
        try {
            byte[] cmdline = Files.readAllBytes(Path.of("/proc/self/cmdline"));
            int end = 0;
            while (end < cmdline.length && cmdline[end] != 0) {
                end++;
            }
            if (end > 0) {
                return baseName(new String(cmdline, 0, end, StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException notLinuxOrNotReadable) {
            // Fall through to the ProcessHandle guess below.
        }
        return ProcessHandle.current().info().command().map(Main::baseName).orElse("");
    }

    private static String baseName(String path) {
        return Path.of(path).getFileName().toString();
    }
}
