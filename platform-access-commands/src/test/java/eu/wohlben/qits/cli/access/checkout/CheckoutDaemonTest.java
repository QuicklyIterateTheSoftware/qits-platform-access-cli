package eu.wohlben.qits.cli.access.checkout;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cli.access.FakeIdp;
import eu.wohlben.qits.cli.access.FakePlatform;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.TestCli;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.session.Session;
import eu.wohlben.qits.cli.access.session.SessionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The command against real Git repositories in a temporary directory and a fake platform. No
 * docker, no platform, and no real waiting: the clock and the sleeper are {@link FakeTime}.
 */
class CheckoutDaemonTest {

    private static final Instant T0 = Instant.parse("2026-09-18T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROJECT = "8f1c2d3e-0000-4000-8000-000000000001";
    private static final String REPO = "qits-demo-service";
    private static final String SUB = "qits-demo-sub";
    private static final String VERSION = "2026.918.1";
    private static final String NEXT_VERSION = "2026.918.2";

    @TempDir
    Path tmp;

    private FakeIdp idp;
    private FakePlatform platform;
    private FakeTime time;
    private final Map<String, String> env = new HashMap<>();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final AtomicReference<Runnable> stopper = new AtomicReference<>();

    /** Where the git host would serve it: /git/<project id>/<repository>. */
    private Path upstream;
    private Path work;

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdp();
        platform = new FakePlatform();
        time = new FakeTime(T0);
        Path home = Files.createDirectories(tmp.resolve("home"));
        env.put("XDG_CONFIG_HOME", home.toString());
        env.put("QITS_EVENTS_URL", platform.url());
        // `git config --global` writes here, never to the person's ~/.gitconfig.
        env.put("GIT_CONFIG_GLOBAL", tmp.resolve("gitconfig").toString());
        new SessionFile(home.resolve("qits")).write(new Session(idp.url(), "qits-cli", FakeIdp.SECRET + "access-1",
                T0.plusSeconds(900), idp.issueRefreshToken(), T0.plus(Duration.ofDays(30))));
        // A submodule whose url is a local path is refused by default since git 2.38.
        git(tmp, "config", "--file", tmp.resolve("gitconfig").toString(), "protocol.file.allow", "always");

        upstream = Files.createDirectories(tmp.resolve("git").resolve(PROJECT).resolve(REPO));
        init(upstream);
        commit(upstream, "README.md", "one");
        work = tmp.resolve("work");
        git(tmp, "clone", "-q", upstream.toString(), work.toString());
        platform.answer("GET", "/events/api/events", "{\"events\":[],\"nextCursor\":null}");
    }

    @AfterEach
    void stop() {
        platform.close();
        idp.close();
        assertThat(out() + err()).doesNotContain(FakeIdp.SECRET);
    }

    // ---------- the fixture ----------

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private String git(Path dir, String... args) throws Exception {
        List<String> argv = new ArrayList<>(List.of("git", "-C", dir.toString()));
        argv.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(argv).redirectErrorStream(true);
        builder.environment().putAll(env);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(String.join(" ", argv) + "\n" + output).isZero();
        return output.strip();
    }

    private void init(Path repo) throws Exception {
        git(repo, "init", "-q", "--initial-branch=main");
        git(repo, "config", "user.email", "test@example.invalid");
        git(repo, "config", "user.name", "Test");
    }

    private String commit(Path repo, String file, String text) throws Exception {
        Files.writeString(repo.resolve(file), text);
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "change " + file);
        return git(repo, "rev-parse", "HEAD");
    }

    private String head(Path repo) throws Exception {
        return git(repo, "rev-parse", "HEAD");
    }

    private String branch(Path repo) throws Exception {
        return git(repo, "rev-parse", "--abbrev-ref", "HEAD");
    }

    /** One released commit in the upstream, tagged the way a release tags it. */
    private String release(String version) throws Exception {
        String sha = commit(upstream, "shipped.txt", version);
        git(upstream, "tag", "-a", version, "-m", "release " + version);
        return sha;
    }

    /** What the query API answers: the newest SCMRelease, its payload a JSON string. */
    private void announced(String payload) {
        String envelope = JSON.createObjectNode().put("id", "e-1").put("name", "SCMRelease")
                .put("occurredAt", "2026-09-18T10:00:01Z").put("payload", payload)
                .putNull("description").put("environment", "dev").toString();
        platform.answer("GET", "/events/api/events", "{\"events\":[" + envelope + "],\"nextCursor\":null}");
    }

    private String payload(String repositoryField, String name, String sha) {
        var node = JSON.createObjectNode().put(repositoryField, name).put("projectId", PROJECT).put("version", VERSION);
        if (sha != null) {
            node.put("commitSha", sha);
        }
        return node.toString();
    }

    private CliContext context() {
        return new CliContext(Map.copyOf(env), InputStream.nullInputStream(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                time, time, TokenClient::new, stopper::set);
    }

    private int run(String... extra) {
        List<String> args = new ArrayList<>(List.of("checkout-daemon", "--path", work.toString()));
        args.addAll(List.of(extra));
        return TestCli.execute(context(), args.toArray(String[]::new));
    }

    private CompletableFuture<Integer> watch(String... extra) {
        return CompletableFuture.supplyAsync(() -> run(extra));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the condition did not hold within 20 s");
            }
            Thread.sleep(20);
        }
    }

    // ---------- reconciling once ----------

    @Test
    void itHoldsTheCheckoutDetachedAtTheReleaseTheEventNames() throws Exception {
        String released = release(VERSION);
        announced(payload("repositoryName", REPO, released));

        assertThat(run("--once")).isZero();

        assertThat(head(work)).isEqualTo(released);
        assertThat(branch(work)).as("detached at the release, not on a branch").isEqualTo("HEAD");
        assertThat(out().lines()).containsExactly(VERSION);
        assertThat(err().lines()).allMatch(l -> l.matches("^\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d.*"
                + " qits checkout-daemon: .*"));
        String query = platform.requests("GET", "/events/api/events").get(0).query();
        assertThat(query).contains("name=SCMRelease").contains("attr=repositoryname=" + REPO)
                .contains("attr=projectid=" + PROJECT).contains("order=desc").contains("limit=1");
    }

    @Test
    void itTakesTheRepositoryFromRepositoryWhenThePayloadHasNoRepositoryName() throws Exception {
        String released = release(VERSION);
        announced(payload("repository", REPO, released));

        assertThat(run("--once")).isZero();

        assertThat(head(work)).isEqualTo(released);
    }

    @Test
    void aPayloadWithNoCommitShaIsFollowedByItsTag() throws Exception {
        String released = release(VERSION);
        announced(payload("repositoryName", REPO, null));

        assertThat(run("--once")).isZero();

        assertThat(head(work)).isEqualTo(released);
    }

    @Test
    void aCheckoutWithLocalChangesIsLeftAloneAndOnceExitsOne() throws Exception {
        release(VERSION);
        announced(payload("repositoryName", REPO, null));
        String before = head(work);
        Files.writeString(work.resolve("README.md"), "somebody is working here");

        assertThat(run("--once")).isEqualTo(1);

        assertThat(head(work)).isEqualTo(before);
        assertThat(Files.readString(work.resolve("README.md"))).isEqualTo("somebody is working here");
        assertThat(err()).contains("has local changes").contains(work.toString());
        assertThat(err().lines()).allMatch(l -> l.matches("^\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d.*"
                + " qits checkout-daemon: .*"));
    }

    /**
     * The regression of 2026-09-19. An untracked path is not work this command can destroy:
     * {@code git checkout --detach} never deletes one. Counting them froze every checkout that
     * outlived a submodule removal, because the stranded directory never goes away.
     */
    @Test
    void anUntrackedFileOrDirectoryIsNotALocalChangeAndTheCheckoutStillMoves() throws Exception {
        String released = release(VERSION);
        announced(payload("repositoryName", REPO, released));
        Files.writeString(work.resolve("scratch.txt"), "nobody tracked this");
        Path stray = Files.createDirectories(work.resolve("components/qits-artifacts/qits-artifacts-cli"));
        Files.writeString(stray.resolve("pom.xml"), "left behind by a removed submodule");

        assertThat(run("--once")).isZero();

        assertThat(head(work)).isEqualTo(released);
        assertThat(branch(work)).isEqualTo("HEAD");
        assertThat(Files.readString(work.resolve("scratch.txt"))).as("untracked work is still there")
                .isEqualTo("nobody tracked this");
        assertThat(Files.readString(stray.resolve("pom.xml"))).isEqualTo("left behind by a removed submodule");
    }

    /** Staged is tracked: the guard must still refuse, or the checkout would move under the work. */
    @Test
    void aStagedChangeIsStillALocalChange() throws Exception {
        release(VERSION);
        announced(payload("repositoryName", REPO, null));
        String before = head(work);
        Files.writeString(work.resolve("new.txt"), "staged, not committed");
        git(work, "add", "new.txt");

        assertThat(run("--once")).isEqualTo(1);

        assertThat(head(work)).isEqualTo(before);
        assertThat(err()).contains("has local changes").contains(work.toString());
    }

    /**
     * What keeps {@code --ignore-submodules=none} in the guard. The entry says {@code ignore = all},
     * the way every wrapper's does, so without the flag Git's status says the root is clean and the
     * checkout would move out from under somebody's work in the submodule.
     */
    @Test
    void aDirtySubmoduleUnderACleanRootStillRefuses() throws Exception {
        Path checkedOut = addSubmodule();
        release(VERSION);
        announced(payload("repositoryName", REPO, null));
        String before = head(work);
        Files.writeString(checkedOut.resolve("lib.txt"), "somebody is working in the submodule");

        assertThat(run("--once")).isEqualTo(1);

        assertThat(head(work)).isEqualTo(before);
        assertThat(Files.readString(checkedOut.resolve("lib.txt"))).isEqualTo("somebody is working in the submodule");
        assertThat(err()).contains("has local changes").contains(work.toString());
    }

    /**
     * The live case, end to end. A release removes a component; the move leaves its working
     * directory on disk, untracked, forever. The next release must still be followed — before the
     * fix this checkout was frozen from here on, and in watch mode silently so.
     */
    @Test
    void aStrandedSubmoduleDirectoryDoesNotFreezeTheNextRelease() throws Exception {
        Path checkedOut = addSubmodule();

        git(upstream, "rm", "-q", "-f", "components/demo/" + SUB);
        git(upstream, "commit", "-q", "-m", "remove the component");
        String withoutIt = release(VERSION);
        announced(payloadFor(withoutIt, VERSION));

        assertThat(run("--once")).isZero();
        assertThat(head(work)).isEqualTo(withoutIt);
        // Git collapses it to the first untracked directory, which is the whole point: it is there,
        // it is untracked, and nothing this command does will ever remove it.
        assertThat(git(work, "status", "--porcelain")).as("the directory git left behind")
                .startsWith("?? components/");
        assertThat(Files.exists(checkedOut.resolve("lib.txt"))).isTrue();

        out.reset();
        err.reset();
        String next = release(NEXT_VERSION);
        announced(payloadFor(next, NEXT_VERSION));

        assertThat(run("--once")).isZero();

        assertThat(head(work)).as("it followed the next release with the stranded directory there").isEqualTo(next);
        assertThat(err()).doesNotContain("has local changes");
    }

    /** The submodule as a wrapper declares it — {@code ignore = all} and all — initialised here. */
    private Path addSubmodule() throws Exception {
        Path sub = Files.createDirectories(tmp.resolve("git").resolve(PROJECT).resolve(SUB));
        init(sub);
        commit(sub, "lib.txt", "one");
        git(upstream, "submodule", "add", "--name", SUB, sub.toString(), "components/demo/" + SUB);
        git(upstream, "config", "-f", ".gitmodules", "submodule." + SUB + ".ignore", "all");
        git(upstream, "add", "-A");
        git(upstream, "commit", "-q", "-m", "declare the submodule");

        git(work, "fetch", "-q", "origin");
        git(work, "reset", "--hard", "-q", "origin/main");
        git(work, "-c", "submodule." + SUB + ".url=" + sub, "submodule", "update", "--init", "--",
                "components/demo/" + SUB);
        return work.resolve("components/demo/" + SUB);
    }

    private String payloadFor(String sha, String version) {
        return JSON.createObjectNode().put("repositoryName", REPO).put("projectId", PROJECT)
                .put("version", version).put("commitSha", sha).toString();
    }

    /**
     * The whole reason {@code submodule update} is passed {@code --checkout}. The submodule sits on
     * {@code main}, which is where a wrapper checkout leaves it, and its entry says
     * {@code update = merge}: without the flag Git merges the recorded commit into that branch and
     * leaves the submodule on it, so the tree is not the tree the release recorded.
     */
    @Test
    void aSubmoduleThatSaysUpdateMergeStillEndsDetachedAtTheGitlink() throws Exception {
        Path sub = Files.createDirectories(tmp.resolve("git").resolve(PROJECT).resolve(SUB));
        init(sub);
        String first = commit(sub, "lib.txt", "one");

        git(upstream, "submodule", "add", "--name", SUB, sub.toString(), "components/demo/" + SUB);
        // As the wrapper declares it: a relative url that resolves nowhere from this clone (so only
        // the url the command passes can work), and the update mode that would merge.
        git(upstream, "config", "-f", ".gitmodules", "submodule." + SUB + ".url", "../../nowhere/" + SUB);
        git(upstream, "config", "-f", ".gitmodules", "submodule." + SUB + ".update", "merge");
        git(upstream, "config", "-f", ".gitmodules", "submodule." + SUB + ".branch", "main");
        git(upstream, "add", "-A");
        git(upstream, "commit", "-q", "-m", "declare the submodule");

        // The checkout as a person leaves it: the submodule initialised and on main, at the pin.
        git(work, "fetch", "-q", "origin");
        git(work, "reset", "--hard", "-q", "origin/main");
        Path checkedOut = work.resolve("components/demo/" + SUB);
        git(work, "-c", "submodule." + SUB + ".url=" + sub, "submodule", "update", "--init", "--", "components/demo/" + SUB);
        git(checkedOut, "checkout", "-q", "main");
        assertThat(head(checkedOut)).isEqualTo(first);

        // The release moves the gitlink on, and main in the submodule moves with it.
        String pinned = commit(sub, "lib.txt", "two");
        Path inUpstream = upstream.resolve("components/demo/" + SUB);
        git(inUpstream, "fetch", "-q", "origin");
        git(inUpstream, "checkout", "-q", "--detach", pinned);
        git(upstream, "add", "-A");
        git(upstream, "commit", "-q", "-m", "move the gitlink on");
        String released = release(VERSION);
        announced(payload("repositoryName", REPO, released));

        assertThat(run("--once")).isZero();

        assertThat(head(work)).isEqualTo(released);
        assertThat(head(checkedOut)).as("the gitlink the release recorded").isEqualTo(pinned).isNotEqualTo(first);
        assertThat(branch(checkedOut)).as("detached, which is what --checkout does and merge does not")
                .isEqualTo("HEAD");
    }

    @Test
    void anOriginThatNamesTheRepositoryByItsIdIsRefusedUntilRepositoryIsGiven() throws Exception {
        git(work, "remote", "set-url", "origin", "http://githost.dev.invalid/git/" + PROJECT);
        git(tmp, "config", "--file", tmp.resolve("gitconfig").toString(),
                "credential.http://githost.dev.invalid.helper", "!qits git-credential");

        assertThat(run("--once")).isEqualTo(2);
        assertThat(err()).contains("--repository").contains("internal storage scheme");

        out.reset();
        err.reset();
        assertThat(run("--once", "--repository", REPO)).isZero();
        assertThat(err()).contains("nothing is released yet");
    }

    @Test
    void anOriginWhoseHostHasNoCredentialHelperIsRefusedBeforeAnythingIsFetched() throws Exception {
        git(work, "remote", "set-url", "origin", "https://githost.dev.invalid/git/" + PROJECT + "/" + REPO);

        assertThat(run("--once")).isEqualTo(2);

        assertThat(err()).contains("qits git-login").contains("githost.dev.invalid");
        assertThat(platform.requests("GET", "/events/api/events")).isEmpty();
    }

    // ---------- watching ----------

    @Test
    void aReleaseOnTheStreamIsHeldAndStopEndsTheWatchWithZero() throws Exception {
        String released = release(VERSION);
        String envelope = JSON.createObjectNode().put("id", "e-1").put("name", "SCMRelease")
                .put("payload", payload("repositoryName", REPO, released)).toString();
        platform.streams.add(platform.stream(true, ": open", "id:e-1", "data:" + envelope, ""));

        CompletableFuture<Integer> run = watch();
        await(() -> fileEquals(released));
        await(() -> stopper.get() != null);
        stopper.get().run();

        assertThat(run.get(20, TimeUnit.SECONDS)).isZero();
        assertThat(branch(work)).isEqualTo("HEAD");
        assertThat(err()).contains("stopped");
    }

    @Test
    void aReleaseThatNeverReachedTheStreamIsFoundWhenItConnectsAgain() throws Exception {
        String released = release(VERSION);
        CountDownLatch drop = new CountDownLatch(1);
        platform.streams.add(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream body = exchange.getResponseBody();
            body.write(": open\n".getBytes(StandardCharsets.UTF_8));
            body.flush();
            drop.await();
        });
        platform.streams.add(platform.stream(true, ": open"));
        platform.streams.add(platform.stream(true, ": open"));

        CompletableFuture<Integer> run = watch();
        // The stream is open and has nothing to say, and the release happens now: no frame for it.
        await(() -> platform.requests("GET", "/events/api/events").size() >= 2);
        announced(payload("repositoryName", REPO, released));
        drop.countDown();

        await(() -> fileEquals(released));
        await(() -> stopper.get() != null);
        stopper.get().run();

        assertThat(run.get(20, TimeUnit.SECONDS)).isZero();
        assertThat(err()).contains("connected again to");
        assertThat(time.sleeps).as("it waited through the sleeper, never in real time").isNotEmpty();
    }

    private boolean fileEquals(String sha) {
        try {
            return head(work).equals(sha);
        } catch (Exception notYet) {
            return false;
        }
    }
}
