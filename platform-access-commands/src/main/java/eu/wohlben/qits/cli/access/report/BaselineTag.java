package eu.wohlben.qits.cli.access.report;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * The baseline's tag in the step's tree: the newest released version, which is also its tag, fetched
 * on first use and at most once per submit. Every kind that compares with the baseline's files goes
 * through this one helper rather than fetching on its own: {@link GitChangedLines} diffs against it,
 * and a kind that reads files as they were at the baseline (screenshots, the entity diagram) asks
 * {@link #list} and {@link #read}.
 * <p>
 * The step's clone is shallow ({@code --depth}), so the tag is usually not there; it is fetched with
 * {@code git fetch --depth=1 "$QITS_CI_REPOSITORY_URL" refs/tags/<v>:refs/tags/<v>}, which a two-tree
 * diff is content with, since it needs no merge base. A tag already in the tree is used as it is. The
 * step's {@code GIT_CONFIG_GLOBAL} authenticates the fetch.
 * <p>
 * Never thrown, never wrong: without a baseline there is no tag, and when it cannot be had there is
 * none either, said once as one warning line.
 */
public final class BaselineTag {

    /** What a version must look like to be named in a git command: a tag name and nothing else. */
    private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z._+-]{0,127}");

    private final Optional<Baseline> baseline;
    private final String repositoryUrl;
    private final Git git;
    private final Consumer<String> warnings;

    private boolean resolved;
    private Optional<String> ref = Optional.empty();

    /**
     * @param repositoryUrl {@code QITS_CI_REPOSITORY_URL}, where the step cloned from; null when the step
     *                      does not say, and then only a tag already in the tree is used
     * @param warnings      where the one line goes when the tag cannot be had
     */
    BaselineTag(Git git, Optional<Baseline> baseline, String repositoryUrl, Consumer<String> warnings) {
        this.git = git;
        this.baseline = baseline;
        this.repositoryUrl = repositoryUrl == null || repositoryUrl.isBlank() ? null : repositoryUrl.strip();
        this.warnings = warnings;
    }

    /** The baseline's tag in the step's tree at {@code root}; see the constructor for the rest. */
    public static BaselineTag in(Path root, Optional<Baseline> baseline, String repositoryUrl,
                                 Consumer<String> warnings) {
        return new BaselineTag(new Git(root), baseline, repositoryUrl, warnings);
    }

    /** No baseline: never a tag, and never a git command. */
    public static BaselineTag none() {
        return new BaselineTag(null, Optional.empty(), null, line -> { });
    }

    public Optional<Baseline> baseline() {
        return baseline;
    }

    /**
     * {@code refs/tags/<version>}, present in the step's tree, to name in a git command; empty without
     * a baseline or when it could not be fetched. The first call fetches; later calls answer the same.
     */
    public synchronized Optional<String> ref() throws InterruptedException {
        if (resolved) {
            return ref;
        }
        resolved = true;
        if (baseline.isEmpty() || git == null) {
            return ref;
        }
        String version = baseline.get().version();
        if (version == null || !VERSION.matcher(version).matches()) {
            warnings.accept("baseline tag: '" + version + "' is not a tag name, so nothing compares with its files");
            return ref;
        }
        String name = "refs/tags/" + version;
        try {
            if (!present(name)) {
                if (repositoryUrl == null) {
                    warnings.accept("baseline tag " + version + ": not in this tree, and QITS_CI_REPOSITORY_URL "
                            + "is not set, so nothing compares with its files");
                    return ref;
                }
                Git.Result fetched = git.run("fetch", "--quiet", "--no-tags", "--depth=1", repositoryUrl,
                        name + ":" + name);
                if (!fetched.ok() || !present(name)) {
                    warnings.accept("baseline tag " + version + ": could not be fetched, so nothing compares with "
                            + "its files: " + Git.said(fetched));
                    return ref;
                }
            }
        } catch (Git.Failure failed) {
            warnings.accept("baseline tag " + version + ": " + failed.getMessage()
                    + ", so nothing compares with its files");
            return ref;
        }
        ref = Optional.of(name);
        return ref;
    }

    /**
     * {@code path} (relative to the step's root, forward slashes) as it was at the baseline; empty
     * without the tag, or when the file did not exist there.
     */
    public Optional<byte[]> read(String path) throws InterruptedException {
        Optional<String> tag = ref();
        if (tag.isEmpty() || path == null || path.isBlank()) {
            return Optional.empty();
        }
        try {
            Git.Result shown = git.run("show", tag.get() + ":./" + path);
            return shown.ok() ? Optional.of(shown.out()) : Optional.empty();
        } catch (Git.Failure failed) {
            return Optional.empty();
        }
    }

    /**
     * The files directly in {@code directory} (relative to the step's root, forward slashes) as it was
     * at the baseline, by name, sorted; subdirectories are not listed. Empty without the tag, or when
     * the directory did not exist there.
     */
    public List<String> list(String directory) throws InterruptedException {
        Optional<String> tag = ref();
        if (tag.isEmpty() || directory == null || directory.isBlank()) {
            return List.of();
        }
        String path = directory.endsWith("/") ? directory.substring(0, directory.length() - 1) : directory;
        try {
            Git.Result listed = git.run("ls-tree", "-z", tag.get() + ":./" + path);
            if (!listed.ok()) {
                return List.of();
            }
            List<String> names = new ArrayList<>();
            for (String entry : listed.text().split("\0")) {
                // <mode> SP <type> SP <object> TAB <name>
                int tab = entry.indexOf('\t');
                if (tab < 0) {
                    continue;
                }
                String[] head = entry.substring(0, tab).split(" ");
                if (head.length == 3 && head[1].equals("blob")) {
                    names.add(entry.substring(tab + 1));
                }
            }
            Collections.sort(names);
            return List.copyOf(names);
        } catch (Git.Failure failed) {
            return List.of();
        }
    }

    /** Git, in the step's tree, for a kind that diffs against {@link #ref()}. */
    Git git() {
        return git;
    }

    private boolean present(String name) throws Git.Failure, InterruptedException {
        return git.run("rev-parse", "--verify", "--quiet", name + "^{commit}").ok();
    }
}
