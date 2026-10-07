package eu.wohlben.qits.cli.access.report;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GitChangedLines} and {@link BaselineTag} against throwaway repositories: an "origin" with two
 * released tags, and a step's tree cloned from it the way qits-ci-daemon clones, shallow, at the fold.
 */
class GitChangedLinesTest {

    static final String BASELINE = "2026.1003.52637";
    static final String FOLD = "2026.1006.10000";

    @TempDir
    Path work;

    private Path origin;
    private final List<String> warnings = new ArrayList<>();

    @BeforeEach
    void origin() throws Exception {
        origin = Files.createDirectories(work.resolve("origin"));
        must(origin, "init", "--quiet", "--initial-branch=main");
        write(origin, "service/src/main/java/fx/Ledger.java", """
                package fx;

                public class Ledger {
                    long balance;

                    void add(long amount) {
                        balance += amount;
                    }
                }
                """);
        write(origin, "README.md", "one\ntwo\nthree\n");
        write(origin, "gone.txt", "bye\n");
        commit(origin, "first");
        must(origin, "tag", BASELINE);
        write(origin, "service/src/main/java/fx/Ledger.java", """
                package fx;

                public class Ledger {
                    long balance;

                    void add(long amount) {
                        if (amount == 0) {
                            throw new IllegalArgumentException();
                        }
                        balance += amount;
                    }
                }
                """);
        write(origin, "README.md", "one\nthree\n");
        write(origin, "service/src/main/java/fx/Clock.java", "package fx;\n\nclass Clock {\n}\n");
        Files.delete(origin.resolve("gone.txt"));
        commit(origin, "second");
        must(origin, "tag", "-a", "-m", "the fold", FOLD);
    }

    @Test
    void aShallowStepFetchesTheBaselineTagAndListsTheNewSidesLines() throws Exception {
        Path step = shallowClone();
        assertThat(git(step, "rev-parse", "--verify", "--quiet", "refs/tags/" + BASELINE + "^{commit}").exit())
                .as("the step's clone does not have the baseline")
                .isNotZero();

        GitChangedLines changed = changedLines(step, url());

        assertThat(changed.available()).as(String.join("\n", warnings)).isTrue();
        assertThat(changed.files()).containsExactlyInAnyOrder("service/src/main/java/fx/Ledger.java",
                "service/src/main/java/fx/Clock.java");
        assertThat(changed.lines("service/src/main/java/fx/Ledger.java")).containsExactly(7, 8, 9);
        assertThat(changed.lines("service/src/main/java/fx/Clock.java")).containsExactly(1, 2, 3, 4);
        assertThat(changed.lines("README.md")).as("a hunk that only removes adds no line").isEmpty();
        assertThat(changed.lines("gone.txt")).isEmpty();
        assertThat(warnings).isEmpty();
    }

    @Test
    void theTagIsFetchedOnFirstUseAndOnlyOnce() throws Exception {
        Path step = shallowClone();
        List<String> said = new ArrayList<>();
        BaselineTag tag = BaselineTag.in(step, Optional.of(baseline()), url(), said::add);
        GitChangedLines changed = new GitChangedLines(tag, said::add);
        assertThat(git(step, "tag", "--list").text()).as("nothing is fetched before it is asked")
                .doesNotContain(BASELINE);

        assertThat(changed.available()).isTrue();
        Files.move(origin, work.resolve("origin-gone"));

        assertThat(changed.files()).isNotEmpty();
        assertThat(tag.ref()).contains("refs/tags/" + BASELINE);
        assertThat(git(step, "tag", "--list").text()).contains(BASELINE);
        assertThat(said).isEmpty();
    }

    @Test
    void aTagAlreadyInTheTreeNeedsNoAddress() throws Exception {
        Path step = work.resolve("full");
        must(work, "clone", "--quiet", origin.toString(), step.toString());

        GitChangedLines changed = changedLines(step, null);

        assertThat(changed.available()).as(String.join("\n", warnings)).isTrue();
        assertThat(changed.lines("service/src/main/java/fx/Ledger.java")).containsExactly(7, 8, 9);
    }

    @Test
    void anUnknownTagIsUnavailableWithOneWarningNeverThrown() throws Exception {
        Path step = shallowClone();
        List<String> said = new ArrayList<>();
        BaselineTag tag = BaselineTag.in(step, Optional.of(new Baseline("2025.101.1", "run-0", null, null)), url(),
                said::add);
        GitChangedLines changed = new GitChangedLines(tag, said::add);

        assertThat(changed.available()).isFalse();
        assertThat(changed.files()).isEmpty();
        assertThat(changed.lines("service/src/main/java/fx/Ledger.java")).isEmpty();
        assertThat(said).hasSize(1);
        assertThat(said.getFirst()).startsWith("baseline tag 2025.101.1: could not be fetched, so nothing "
                + "compares with its files: fatal: ");
    }

    @Test
    void aTreeThatIsNotARepositoryIsUnavailable() throws Exception {
        Path plain = Files.createDirectories(work.resolve("plain"));

        GitChangedLines changed = changedLines(plain, url());

        assertThat(changed.available()).isFalse();
        assertThat(warnings).hasSize(1);
    }

    @Test
    void noAddressAndNoTagIsUnavailable() throws Exception {
        GitChangedLines changed = changedLines(shallowClone(), null);

        assertThat(changed.available()).isFalse();
        assertThat(warnings).containsExactly("baseline tag " + BASELINE + ": not in this tree, and "
                + "QITS_CI_REPOSITORY_URL is not set, so nothing compares with its files");
    }

    @Test
    void aVersionThatIsNotATagNameIsNeverPassedToGit() throws Exception {
        List<String> said = new ArrayList<>();
        BaselineTag tag = BaselineTag.in(shallowClone(), Optional.of(new Baseline("--upload-pack=x", "r", null, null)),
                url(), said::add);

        assertThat(tag.ref()).isEmpty();
        assertThat(said).singleElement().asString().contains("is not a tag name");
    }

    @Test
    void noBaselineIsNoTagAndNoGit() throws Exception {
        assertThat(BaselineTag.none().ref()).isEmpty();
        assertThat(BaselineTag.none().read("README.md")).isEmpty();
        assertThat(BaselineTag.none().list("service")).isEmpty();
    }

    @Test
    void aDirectoryListsItsFilesAsItWasAtTheBaseline() throws Exception {
        BaselineTag tag = BaselineTag.in(shallowClone(), Optional.of(baseline()), url(), warnings::add);

        assertThat(tag.list("service/src/main/java/fx")).as("Clock.java was added since").containsExactly("Ledger.java");
        assertThat(tag.list("service/src/main/java/fx/")).containsExactly("Ledger.java");
        assertThat(tag.list(".")).as("files only, no directories").containsExactly("README.md", "gone.txt");
        assertThat(tag.list("docs/database")).as("not there").isEmpty();
        assertThat(warnings).isEmpty();
    }

    @Test
    void aFileReadsAsItWasAtTheBaseline() throws Exception {
        BaselineTag tag = BaselineTag.in(shallowClone(), Optional.of(baseline()), url(), warnings::add);

        assertThat(tag.read("README.md")).map(b -> new String(b, StandardCharsets.UTF_8))
                .contains("one\ntwo\nthree\n");
        assertThat(tag.read("gone.txt")).isPresent();
        assertThat(tag.read("service/src/main/java/fx/Clock.java")).as("added since").isEmpty();
    }

    @Test
    void gitsAdviceIsNotTheWarning() {
        assertThat(Git.said(new Git.Result(128, new byte[0], """
                fatal: '/nowhere' does not appear to be a git repository
                fatal: Could not read from remote repository.

                Please make sure you have the correct access rights
                and the repository exists.""")))
                .isEqualTo("fatal: '/nowhere' does not appear to be a git repository");
        assertThat(Git.said(new Git.Result(1, new byte[0], ""))).isEqualTo("git exited 1");
    }

    @Test
    void theDiffIsReadAsTheNewSidesLineNumbers() {
        Map<String, Set<Integer>> lines = GitChangedLines.parse("""
                diff --git a/a.txt b/a.txt
                index 1..2 100644
                --- a/a.txt
                +++ b/a.txt
                @@ -1 +1 @@
                -x
                +y
                @@ -5,0 +6,2 @@ some context
                +p
                +q
                @@ -9,3 +10,0 @@
                -r
                diff --git a/old.txt b/old.txt
                deleted file mode 100644
                --- a/old.txt
                +++ /dev/null
                @@ -1,2 +0,0 @@
                -a
                -b
                diff --git "a/tab\\there.txt" "b/tab\\there.txt"
                --- "a/tab\\there.txt"
                +++ "b/tab\\there.txt"
                @@ -0,0 +1 @@
                +z
                diff --git a/caf\\303\\251.txt b/caf\\303\\251.txt
                --- "a/caf\\303\\251.txt"
                +++ "b/caf\\303\\251.txt"
                @@ -2 +2 @@
                -1
                +2
                """);

        assertThat(lines).containsOnlyKeys("a.txt", "tab\there.txt", "café.txt");
        assertThat(lines.get("a.txt")).containsExactly(1, 6, 7);
        assertThat(lines.get("tab\there.txt")).containsExactly(1);
        assertThat(lines.get("café.txt")).containsExactly(2);
    }

    // --- the repositories ------------------------------------------------------------------------

    private GitChangedLines changedLines(Path step, String url) {
        return new GitChangedLines(BaselineTag.in(step, Optional.of(baseline()), url, warnings::add), warnings::add);
    }

    private static Baseline baseline() {
        return new Baseline(BASELINE, "run-0", "rr-0", null);
    }

    private String url() {
        return origin.toUri().toString();
    }

    /** As qits-ci-daemon prepares a step: shallow, at the fold, without the other tags. */
    private Path shallowClone() throws Exception {
        Path step = work.resolve("step");
        Git.Result cloned = git(work, "clone", "--quiet", "--depth=1", "--no-tags", "--branch", FOLD, url(),
                step.toString());
        assertThat(cloned.exit()).as(cloned.err()).isZero();
        assertThat(Files.exists(step.resolve(".git/shallow"))).isTrue();
        return step;
    }

    private static void write(Path repo, String path, String text) throws IOException {
        Path file = repo.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static void commit(Path repo, String message) throws Exception {
        must(repo, "add", "-A");
        must(repo, "commit", "--quiet", "-m", message);
    }

    private static void must(Path directory, String... arguments) throws Exception {
        Git.Result result = git(directory, arguments);
        assertThat(result.exit()).as("git %s: %s", arguments[0], result.err()).isZero();
    }

    static Git.Result git(Path directory, String... arguments) throws Exception {
        String[] all = new String[arguments.length + 4];
        System.arraycopy(new String[] {"-c", "user.name=qits", "-c", "user.email=qits@example.invalid"}, 0, all, 0, 4);
        System.arraycopy(arguments, 0, all, 4, arguments.length);
        return new Git(directory).run(all);
    }
}
