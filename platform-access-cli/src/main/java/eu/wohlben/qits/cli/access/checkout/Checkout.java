package eu.wohlben.qits.cli.access.checkout;

import eu.wohlben.qits.cli.access.git.GitOrigin;
import eu.wohlben.qits.cli.access.git.GitSetup;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliFailure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The Git side of {@code qits checkout-daemon}: one local checkout, brought to the commit a release
 * named, root and submodules.
 * <p>
 * <b>It never modifies a checkout that has local changes</b>, and it never stashes or resets:
 * whatever is uncommitted is somebody's work, and a daemon that throws it away is worse than a
 * daemon that stops following.
 * <p>
 * <b>Git authentication is the credential helper's business.</b> Nothing here puts a token in a URL
 * or in an {@code http.extraHeader}: the helper is pinned to one host on purpose, and a machine
 * credential handed to a submodule remote somebody else authored is a way out for it.
 * <p>
 * Git's own output is foreign text, so every piece of it that reaches a person goes through
 * {@link SafeText#line}.
 */
public final class Checkout {

    /**
     * What the checkout's origin says.
     *
     * @param url       the origin as Git holds it
     * @param repoName  which repository this is
     * @param projectId the project it belongs to, when the origin names it
     * @param parent    the origin without its last segment: where the siblings are
     */
    public record Origin(String url, String repoName, String projectId, String parent) {
    }

    private final Path path;
    private final Git git;
    private final Origin origin;
    private final boolean submodules;
    private final Consumer<String> notes;

    public Checkout(Path path, Map<String, String> env, Origin origin, boolean submodules, Consumer<String> notes) {
        this.path = path;
        this.git = new Git(path, env);
        this.origin = origin;
        this.submodules = submodules;
        this.notes = notes;
    }

    /**
     * Which repository this checkout is, read from {@code origin}. The git host addresses a
     * repository two ways, and only one of them names it: {@code /git/<project id>/<repository>}
     * does, {@code /git/<repository id>} is its internal storage scheme and says nothing a person
     * or an event would recognise. {@code --repository} answers for every origin that does not.
     */
    public static Origin origin(Path path, Map<String, String> env, String repositoryFlag)
            throws CliFailure, InterruptedException {
        String url = new Git(path, env).run("remote", "get-url", "origin");
        if (url.isEmpty()) {
            throw new CliFailure("The checkout at " + path + " has no origin to follow.", CliFailure.USAGE);
        }
        String named = repositoryFlag == null || repositoryFlag.isBlank() ? null : repositoryFlag.strip();
        List<String> segments = segments(url);
        int git = segments.lastIndexOf("git");
        String repoName = null;
        String projectId = null;
        if (git >= 0 && segments.size() - git - 1 == 2) {
            projectId = segments.get(git + 1);
            repoName = segments.get(git + 2);
        }
        if (named != null) {
            repoName = named;
        }
        if (repoName == null) {
            throw new CliFailure("Cannot tell which repository the checkout at " + path + " is from its origin "
                    + SafeText.line(url) + ". An origin of the form /git/<project id>/<repository> names it; "
                    + "/git/<repository id> is the git host's internal storage scheme and names nothing. "
                    + "Pass --repository <name>.", CliFailure.USAGE);
        }
        return new Origin(url, repoName, projectId, parentOf(url));
    }

    /**
     * Refuses at the start when Git has no way to authenticate to the origin's host, rather than
     * at the first fetch of the first release, hours later.
     * <p>
     * A local path or a {@code file://} origin has no host and needs no helper, so it is left
     * alone.
     */
    public static void requireCredentialHelper(Path path, Map<String, String> env, Origin origin, boolean inPlatform)
            throws CliFailure, InterruptedException {
        String host;
        try {
            host = GitOrigin.normalize(origin.url());
        } catch (IllegalArgumentException noHost) {
            return;
        }
        if (inPlatform) {
            String injected = env.get("QITS_GIT_AUTH_HOST");
            if (injected != null && !injected.isBlank() && normalized(injected, host).equals(host)) {
                return;
            }
            throw new CliFailure("Git has no credential for " + host + " in this container: QITS_GIT_AUTH_HOST names "
                    + (injected == null || injected.isBlank() ? "no host" : "another host")
                    + ". The checkout cannot be fetched.", CliFailure.USAGE);
        }
        String helper = new Git(path, env).ask("config", "--get-urlmatch", "credential.helper", origin.url());
        if (helper == null || helper.isBlank()) {
            throw new CliFailure("Git has no credential helper for " + host + ", so a fetch would ask for a password. "
                    + "Run `qits git-login` first.", CliFailure.USAGE);
        }
    }

    /**
     * Brings the checkout to this release: the root detached at the release tag, and every
     * submodule detached at the gitlink the release recorded. Answers whether it moved anything.
     */
    public boolean hold(Release release) throws CliFailure, InterruptedException {
        String head = git.run("rev-parse", "HEAD");
        String target = release.sha();
        boolean fetched = false;
        if (target == null) {
            // The event named no commit, so only the tag can say which commit the release is.
            fetch(release.version());
            fetched = true;
            target = git.ask("rev-parse", "--verify", "refs/tags/" + release.version() + "^{commit}");
        }
        if (target != null && target.equals(head)) {
            notes.accept("already at " + safe(release.version()) + " (" + head + ")");
            return false;
        }
        String changes = git.run("status", "--porcelain", "--ignore-submodules=none");
        if (!changes.isEmpty()) {
            throw new CliFailure("The checkout at " + path + " has local changes, so nothing was touched. Commit or "
                    + "remove them and it follows " + safe(release.version()) + " again.", CliFailure.FAILED);
        }
        if (!fetched) {
            fetch(release.version());
        }
        git.run("checkout", "--detach", release.version());
        notes.accept("at " + safe(release.version()) + " (" + git.run("rev-parse", "HEAD") + ")");
        if (submodules) {
            holdSubmodules();
        }
        return true;
    }

    /** The tag alone, never a branch: a release is a tag, and a branch tip moves under a reader. */
    private void fetch(String version) throws CliFailure, InterruptedException {
        git.run("fetch", origin.url(), "refs/tags/" + version + ":refs/tags/" + version);
    }

    /**
     * Every submodule the released tree declares, detached at the commit that tree recorded.
     * <p>
     * A gitlink {@code .gitmodules} declares but the released tree does not carry is skipped: the
     * wrapper adds and removes components, and a release from before a component existed is a
     * release all the same.
     */
    private void holdSubmodules() throws CliFailure, InterruptedException {
        String keys = git.ask("config", "-f", ".gitmodules", "--name-only", "--get-regexp", "^submodule\\..*\\.path$");
        if (keys == null || keys.isBlank()) {
            return;
        }
        for (String key : keys.lines().map(String::strip).filter(line -> !line.isEmpty()).toList()) {
            String name = key.substring("submodule.".length(), key.length() - ".path".length());
            String sub = git.run("config", "-f", ".gitmodules", "--get", key);
            String staged = git.ask("ls-files", "--stage", "--", sub);
            if (staged == null || !staged.startsWith("160000")) {
                notes.accept("the release has no " + safe(sub) + ", although .gitmodules declares it; skipped");
                continue;
            }
            // --checkout is what makes this follow the release. Every entry of the wrapper this is
            // aimed at sets `update = merge`, and `submodule update --init` copies that into this
            // checkout's config: without the flag Git MERGES the recorded commit into whatever
            // branch the submodule sits on, which leaves the submodule at that branch's tip rather
            // than at the commit the release recorded.
            git.run("-c", "submodule." + name + ".url=" + origin.parent() + "/" + name,
                    "submodule", "update", "--init", "--checkout", "--", sub);
        }
    }

    private static String safe(String text) {
        return SafeText.line(text);
    }

    /** {@code https://host/git/p/r} and {@code /srv/git/p/r} both give {@code [git, p, r]}. */
    private static List<String> segments(String url) {
        String rest = url;
        int scheme = rest.indexOf("://");
        if (scheme >= 0) {
            int slash = rest.indexOf('/', scheme + 3);
            rest = slash < 0 ? "" : rest.substring(slash);
        }
        int query = rest.indexOf('?');
        if (query >= 0) {
            rest = rest.substring(0, query);
        }
        return Arrays.stream(rest.split("/")).filter(s -> !s.isEmpty()).toList();
    }

    /** The origin without its last segment: where a sibling repository of the same project is. */
    private static String parentOf(String url) {
        String rest = url;
        while (rest.endsWith("/")) {
            rest = rest.substring(0, rest.length() - 1);
        }
        int slash = rest.lastIndexOf('/');
        return slash <= 0 ? rest : rest.substring(0, slash);
    }

    /** A bare authority is read against the origin's own scheme, the way Git's helper reads it. */
    private static String normalized(String told, String fallback) {
        try {
            String scheme = fallback.substring(0, fallback.indexOf("://"));
            return GitOrigin.normalize(told.contains("://") ? told : scheme + "://" + told.strip());
        } catch (IllegalArgumentException | IndexOutOfBoundsException notAHost) {
            return "";
        }
    }

    /**
     * One {@code git} process in the checkout. The wording of a failure is {@link GitSetup}'s, so
     * every Git error this program prints reads the same.
     */
    static final class Git {

        private final Path path;
        private final Map<String, String> env;

        Git(Path path, Map<String, String> env) {
            this.path = path;
            this.env = env;
        }

        /** The command's output. Anything but exit 0 ends the command. */
        String run(String... args) throws CliFailure, InterruptedException {
            List<String> argv = argv(args);
            Result result = call(argv);
            if (result.exit != 0) {
                String said = SafeText.line((result.out + " " + result.err).strip());
                throw new CliFailure("`" + GitSetup.shellLine(argv) + "` failed (exit code " + result.exit + ")"
                        + (said.isEmpty() ? "" : ": " + said), CliFailure.FAILED);
            }
            return result.out.strip();
        }

        /** The output, or null when Git says no: a tag that is not there, a file that is not. */
        String ask(String... args) throws CliFailure, InterruptedException {
            Result result = call(argv(args));
            return result.exit == 0 ? result.out.strip() : null;
        }

        private List<String> argv(String... args) {
            List<String> argv = new ArrayList<>(List.of("git", "-C", path.toString()));
            argv.addAll(List.of(args));
            return argv;
        }

        private Result call(List<String> argv) throws CliFailure, InterruptedException {
            ProcessBuilder builder = new ProcessBuilder(argv);
            builder.environment().putAll(env);
            try {
                Process process = builder.start();
                // The two streams are kept apart: Git's progress and warnings go to stderr, and a
                // line of those mixed into the output would be read as part of a commit id.
                StringBuilder errors = new StringBuilder();
                Thread drain = Thread.ofPlatform().daemon().name("qits-checkout-git").start(() -> {
                    try {
                        errors.append(new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
                    } catch (IOException gone) {
                        // The process ended; whatever it said is lost, and the exit code still speaks.
                    }
                });
                String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int exit = process.waitFor();
                drain.join();
                return new Result(exit, out, errors.toString());
            } catch (IOException e) {
                throw new CliFailure("Cannot run git: " + e.getMessage(), CliFailure.FAILED);
            }
        }

        private record Result(int exit, String out, String err) {
        }
    }
}
