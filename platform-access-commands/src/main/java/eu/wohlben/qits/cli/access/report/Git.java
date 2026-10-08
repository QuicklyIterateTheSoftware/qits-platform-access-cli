package eu.wohlben.qits.cli.access.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@code git}, run in the step's tree, as the step itself would run it: the step's environment is
 * inherited, so its {@code GIT_CONFIG_GLOBAL} authenticates a fetch to the git host and nothing here
 * knows a credential. Never prompts, and never waits longer than it is given.
 * <p>
 * Public for the kinds in the subpackages ({@code screenshots} reads trees and blobs with it); the
 * baseline's tag still comes only from {@link BaselineTag}.
 */
public final class Git {

    /** What one git command may take, a fetch included. The whole submit has 120 seconds. */
    public static final Duration TIMEOUT = Duration.ofSeconds(60);

    /** What git answered. {@code out} is stdout, {@code err} stderr, both as UTF-8. */
    public record Result(int exit, byte[] out, String err) {

        public boolean ok() {
            return exit == 0;
        }

        public String text() {
            return new String(out, StandardCharsets.UTF_8);
        }
    }

    /** Git did not run, or did not finish in time. */
    public static final class Failure extends Exception {
        Failure(String message) {
            super(message);
        }
    }

    private final Path directory;
    private final String executable;
    private final Duration timeout;

    public Git(Path directory) {
        this(directory, "git", TIMEOUT);
    }

    Git(Path directory, String executable, Duration timeout) {
        this.directory = directory;
        this.executable = executable;
        this.timeout = timeout;
    }

    /** Runs {@code git <arguments>} in the directory; stdout and stderr go to files, so neither can block it. */
    public Result run(String... arguments) throws Failure, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("-c");
        command.add("core.quotePath=false");
        command.addAll(List.of(arguments));
        Path out = null;
        Path err = null;
        Process process = null;
        try {
            out = Files.createTempFile("qits-git-", ".out");
            err = Files.createTempFile("qits-git-", ".err");
            ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
                    .redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
                    .redirectOutput(out.toFile())
                    .redirectError(err.toFile());
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            builder.environment().put("LC_ALL", "C");
            process = builder.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new Failure("`git " + arguments[0] + "` did not finish within " + timeout.toSeconds()
                        + " seconds");
            }
            return new Result(process.exitValue(), Files.readAllBytes(out),
                    Files.readString(err, StandardCharsets.UTF_8).strip());
        } catch (IOException cannot) {
            throw new Failure("git could not be run: " + cannot.getMessage());
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            delete(out);
            delete(err);
        }
    }

    private static java.io.File nullDevice() {
        return new java.io.File(System.getProperty("os.name", "").startsWith("Windows") ? "NUL" : "/dev/null");
    }

    private static void delete(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // A temporary file left behind is no reason to fail a report.
            }
        }
    }

    /**
     * What git said on stderr, as one line for a warning: its first {@code fatal:} or {@code error:}
     * line (the advice after it is the same every time), else its last line.
     */
    public static String said(Result result) {
        String last = null;
        for (String line : result.err().split("\\R")) {
            String text = line.strip();
            if (text.startsWith("fatal:") || text.startsWith("error:")) {
                return text;
            }
            if (!text.isEmpty()) {
                last = text;
            }
        }
        return last != null ? last : "git exited " + result.exit();
    }
}
