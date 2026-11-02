package eu.wohlben.qits.cli.access.checkout;

import eu.wohlben.qits.cli.access.git.GitOrigin;
import eu.wohlben.qits.cli.access.git.GitSetup;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliFailure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    public static void requireCredentialHelper(Path path, Map<String, String> env, Origin origin)
            throws CliFailure, InterruptedException {
        String host;
        try {
            host = GitOrigin.normalize(origin.url());
        } catch (IllegalArgumentException noHost) {
            return;
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
     * <p>
     * "Already at" means the root is at the release <i>and</i> every initialised submodule is at
     * the gitlink the release recorded. A root at the release whose submodules sit elsewhere — a
     * checkout whose submodules followed {@code main} — still has its submodules moved; returning
     * early on the root alone left them where they were.
     * <p>
     * <b>Untracked paths are not local changes, and the guard below excludes them on purpose</b>
     * ({@code --untracked-files=no}). The guard exists to protect work this command could destroy,
     * and an untracked file is not such work: {@code git checkout --detach} never deletes one, and
     * when the target tree would overwrite one it refuses with its own error rather than clobbering
     * it. So the guard asks only about tracked state.
     * <p>
     * Counting {@code ??} entries froze real checkouts permanently, measured live on 2026-09-19:
     * {@code /workspace} in the qits project's agent container reported
     * {@code ?? components/qits-artifacts/qits-artifacts-cli/}, the left-behind working directory of
     * a submodule that main no longer declares. {@code git checkout --detach} does not remove such a
     * directory, so this command's own successful move is what strands it — and from the next
     * release on, {@code hold} refused. In watch mode {@link ReleaseWatcher} catches that failure,
     * logs it and keeps watching, so the child stayed alive while the checkout silently never moved
     * again: the exact silent no-op this command exists to prevent. Every long-lived checkout that
     * outlives a submodule removal would end up there.
     * <p>
     * See {@link #refuseLocalChanges} for what counts, and why the submodules are asked one by one.
     * Nothing here stashes, resets or forces.
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
            if (!submodules || submodulesAtTheirGitlinks()) {
                notes.accept("already at " + safe(release.version()) + " (" + head + ")");
                return false;
            }
            refuseLocalChanges(release);
            holdSubmodules();
            notes.accept("at " + safe(release.version()) + " (" + head + "); moved the submodules to the commits it "
                    + "recorded");
            return true;
        }
        refuseLocalChanges(release);
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

    /**
     * Ends the command, naming every offending path, when moving would destroy or strand
     * somebody's work.
     * <p>
     * At the root it asks about tracked state only, with {@code --ignore-submodules=all}. Asking
     * with {@code none} there, as this did before, reports {@code M} for a submodule that is merely
     * at another commit than its gitlink, and also for one that holds only untracked files
     * ({@code --untracked-files=no} does not reach into submodules). That refused nearly every
     * checkout whose submodules follow {@code main}. Neither {@code dirty} nor {@code untracked}
     * hides a submodule at another commit (git 2.39.5); only {@code all} does, and {@code all} also
     * hides real work in a submodule. So the root is asked with {@code all}, and each submodule is
     * asked on its own:
     * <ul>
     * <li>{@code --ignore-submodules=none} inside it: a modified or staged file, or a nested
     * submodule with changes, is work;</li>
     * <li>whether any branch, remote branch or tag holds its {@code HEAD}: a detached commit
     * nothing holds would be left behind by the move, and Git would only find it again in the
     * reflog. A {@code HEAD} at the gitlink the root records is not asked about: the wrapper's own
     * history holds that commit. This matters in a project agent container, where every submodule
     * sits detached at its gitlink and that commit was often fetched by its id alone, because a
     * release tag reaches {@code main} only when the request is finalized; no ref in the
     * submodule holds it, and refusing there would freeze the checkout for good. The gitlink is
     * read from the index, which equals the root's {@code HEAD} here, since a staged gitlink has
     * already been refused above.</li>
     * </ul>
     * A submodule at another commit that a ref holds is not work — the move puts it back at the
     * gitlink and the commit stays reachable. Every entry of the wrapper sets {@code ignore = all},
     * which Git's status honours, so asking inside each submodule is what keeps that work visible.
     * <p>
     * {@code --ignore-submodules=all} also hides a staged gitlink at the root, which
     * {@code git add -A} produces without a word under {@code ignore = all}. That is a tracked
     * change at the root, so the staged paths are asked for separately.
     */
    private void refuseLocalChanges(Release release) throws CliFailure, InterruptedException {
        List<String> found = new ArrayList<>();
        Set<String> atRoot = new LinkedHashSet<>();
        for (String line : git.run("status", "--porcelain", "--untracked-files=no", "--ignore-submodules=all")
                .lines().filter(entry -> !entry.isBlank()).toList()) {
            // The status letters, then the path. The output is stripped, so the first line may have
            // lost the blank that stands for an unchanged index.
            atRoot.add(line.strip().replaceFirst("^\\S{1,2}\\s+", ""));
        }
        atRoot.addAll(git.run("diff", "--cached", "--name-only", "--ignore-submodules=none").lines()
                .filter(line -> !line.isBlank()).toList());
        for (String changed : atRoot) {
            found.add(safe(changed) + " (a tracked change)");
        }
        if (submodules) {
            for (String sub : declaredSubmodules().values()) {
                if (!initialised(sub)) {
                    continue;
                }
                Git inside = new Git(path.resolve(sub), git.env);
                if (!inside.run("status", "--porcelain", "--untracked-files=no", "--ignore-submodules=none").isEmpty()) {
                    found.add(safe(sub) + " (changes in the submodule)");
                } else if (!inside.run("rev-parse", "HEAD").equals(gitlink(sub))
                        && inside.run("for-each-ref", "--contains", "HEAD", "--count=1",
                        "refs/heads", "refs/remotes", "refs/tags").isEmpty()) {
                    found.add(safe(sub) + " (a commit in the submodule that no branch or tag holds)");
                }
            }
        }
        if (!found.isEmpty()) {
            throw new CliFailure("The checkout at " + path + " has local changes, so nothing was touched: "
                    + String.join("; ", found) + ". Commit or remove them, or put a branch on such a commit, and it "
                    + "follows " + safe(release.version()) + " again.", CliFailure.FAILED);
        }
    }

    /**
     * Whether every submodule the release carries and this checkout has initialised is at the
     * gitlink the release recorded. One that is not initialised is left out: it holds nothing, and
     * {@link #holdSubmodules} would only initialise it, which a root at the release has never asked
     * for.
     */
    private boolean submodulesAtTheirGitlinks() throws CliFailure, InterruptedException {
        for (String sub : declaredSubmodules().values()) {
            String recorded = gitlink(sub);
            if (recorded == null || !initialised(sub)) {
                continue;
            }
            if (!recorded.equals(new Git(path.resolve(sub), git.env).ask("rev-parse", "HEAD"))) {
                return false;
            }
        }
        return true;
    }

    /** The commit the index records for {@code sub}, or null when it carries no gitlink there. */
    private String gitlink(String sub) throws CliFailure, InterruptedException {
        String staged = git.ask("ls-files", "--stage", "--", sub);
        if (staged == null || !staged.startsWith("160000 ")) {
            return null;
        }
        String[] fields = staged.split("\\s+");
        return fields.length > 1 ? fields[1] : null;
    }

    /** A submodule Git has checked out here has a {@code .git}, a file or a directory. */
    private boolean initialised(String sub) {
        return Files.exists(path.resolve(sub).resolve(".git"));
    }

    /** Every submodule {@code .gitmodules} declares: its name, and its path. */
    private Map<String, String> declaredSubmodules() throws CliFailure, InterruptedException {
        Map<String, String> declared = new LinkedHashMap<>();
        String keys = git.ask("config", "-f", ".gitmodules", "--name-only", "--get-regexp", "^submodule\\..*\\.path$");
        if (keys == null || keys.isBlank()) {
            return declared;
        }
        for (String key : keys.lines().map(String::strip).filter(line -> !line.isEmpty()).toList()) {
            String name = key.substring("submodule.".length(), key.length() - ".path".length());
            declared.put(name, git.run("config", "-f", ".gitmodules", "--get", key));
        }
        return declared;
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
        for (Map.Entry<String, String> entry : declaredSubmodules().entrySet()) {
            String name = entry.getKey();
            String sub = entry.getValue();
            if (gitlink(sub) == null) {
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
