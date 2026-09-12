package eu.wohlben.qits.cli.access.login;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Tries to open the sign-in page: {@code wslview} on WSL, then {@code xdg-open}.
 * <p>
 * Never in the way of the flow. The address is already on the screen, so a missing opener or one
 * that fails changes nothing. Each opener gets a short time to fail; one that is still running
 * after it is taken to be opening the page, and is not waited for. Not {@code java.awt}: its
 * native-image support pulls in a desktop toolkit to open one URL.
 */
final class Browser {

    private static final long BOUND_SECONDS = 3;

    private Browser() {
    }

    static void open(String url, Map<String, String> env) {
        for (String opener : openers(env)) {
            try {
                Process process = new ProcessBuilder(opener, url)
                        .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                if (!process.waitFor(BOUND_SECONDS, TimeUnit.SECONDS) || process.exitValue() == 0) {
                    return;
                }
            } catch (IOException notInstalled) {
                // Try the next one.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    static List<String> openers(Map<String, String> env) {
        return isWsl(env) ? List.of("wslview", "xdg-open") : List.of("xdg-open");
    }

    private static boolean isWsl(Map<String, String> env) {
        String distro = env.get("WSL_DISTRO_NAME");
        if (distro != null && !distro.isBlank()) {
            return true;
        }
        try {
            return Files.readString(Path.of("/proc/version")).toLowerCase(Locale.ROOT).contains("microsoft");
        } catch (IOException e) {
            return false;
        }
    }
}
