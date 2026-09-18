# AGENTS.md — qits-platform-access-cli

## What this repository is

The `qits` command: access to the qits platform from a Linux or WSL workstation. A Quarkus
command-mode CLI with picocli, built as a GraalVM native binary. Its commands: `qits login`
(browser sign-in, session stored in `$XDG_CONFIG_HOME/qits/t.json`), `qits session-daemon` (keeps
that session fresh), `qits projects|repositories|ticket|release-request` (the projects service),
`qits ci runs|run|retry` (qits-ci's runs, a run's step logs, a retry), `qits events` (the live event
stream), `qits checkout-daemon` (a local checkout held at what a repository released, root and
submodules), `qits observe` (the live, server-filtered telemetry stream of qits-observability, over
a WebSocket), and `qits git-login` / `qits git-credential` (Git pushes to
`refs/heads/external/*`, sign-in stored in `$XDG_CONFIG_HOME/qits/git.json`), and `qits tui` (an
interactive screen over all of them). The README says how each behaves.

It has **two homes**. A workstation, where a person signs in with a browser and the session lives in
a file, and a workspace container on the platform, where the commissioned client pair the container
was injected with is the credential and the services are dialled by their wire aliases. The mode is
decided once at startup and the two are never mixed.

## Two modules

Since 2026-09-17 this repository is a Maven reactor. The root `pom.xml` is an aggregator
(`eu.wohlben:qits`, packaging `pom`) holding the shared properties and the quarkus-bom import, the
shape qits-ci-daemon's root has, and it lists two modules:

    platform-access-cli-binary  the pin. One class, three strings, no bytes of the binary: the
                                daemons-store name, the command name (`qits`), and the version the
                                same release published the binary under, filtered in from
                                `${project.version}`. THE ONLY ARTIFACT THIS REPOSITORY DEPLOYS TO A
                                MAVEN REPOSITORY.
    platform-access-cli         the program. Everything that was here before; the sources moved from
                                `src/` to `platform-access-cli/src/` unchanged, and its published
                                coordinates (`eu.wohlben.qits:qits-platform-access-cli`) did not
                                move — they are the repository's identity. It deploys nothing
                                (`maven.deploy.skip`): what it produces is the binary, published to
                                the artifacts store as bytes.

**Why a second module rather than a second pom beside one.** The release stamper walks a reactor by
`<module>` only, so a nested pom the root does not list is never version-stamped — and the pin is
worthless unless its version is the released version by construction.

**Why the pin exists at all.** qits-ci hands the `qits` CLI to every composed release step, and it
used to download whatever was latest in the daemons store at the moment the step started: a shared,
unversioned, unreviewed input to every release on the platform at once. On 2026-09-13 one bad CLI
release broke all of them, with nothing changed in any consumer's tree and no line to revert. Now
qits-ci's *pom* depends on this jar and injects the version it names — a bad CLI breaks one
repository's gate, and the fix is a revert of one line. `PlatformAccessCliBinary`'s javadoc carries
the whole reasoning; `.config/qits/ci-event-release.yml`'s last step publishes it.

`SKILL.md` stays at the repository root and did not follow the sources into the module; the test
that keeps it in step (`SkillDocumentTest`) resolves it one directory up and says why.

## Layout

The application module's sources, under `platform-access-cli/src/main/java/eu/wohlben/qits/cli/`:

    idp/       the idp's /token endpoint and which idp to use
    session/   the session file, its atomic write, the two locks, and the one refresh (SessionRefresh)
    login/     qits login: PKCE, the pasted code, the browser opener
    daemon/    qits session-daemon: the refresh loop, the sleeper seam, the stop signals
    platform/  what every platform command shares: the context, the token (with inline refresh),
               the HTTP client and its error messages, which address a service has (PlatformUrls),
               and the aligned table (Table)
    projects/  qits projects, repositories, ticket and release-request
    ci/        qits ci: runs, run (with the step logs) and retry, on qits-ci's run API
    events/    qits events: the SSE parser and the reconnecting stream
    checkout/  qits checkout-daemon: the Git side of one checkout (Checkout) and the reconcile-and-
               watch loop over SCMRelease (ReleaseWatcher), a sibling of events/EventStream that
               shares its SSE parser
    observe/   qits observe: the --filter grammar, the reconnecting WebSocket stream, the line form,
               and SafeText (terminal control characters out of every streamed value)
    git/       qits git-login and git-credential: the loopback callback, git.json, Git's helper
               protocol, the per-host Git setup
    artifacts/ qits artifacts: the group. Its only command today is publish, in publish/ below.
    publish/   qits artifacts publish: the qits-publish client, folded in from the retired
               qits-artifacts-cli. Store, Publisher, Npm, DockerfileSbom, Http, Json, Sha256,
               VersionOrder, Env, Console, CliException and ExitCode were that repository's
               classes, kept as they were; the picocli commands and PublishArgs (the
               argument-grammar checks Args used to do) are new. Touches no session file — see
               Conventions.
    help/      qits help skill (hidden): the commands' help arranged as SKILL.md
    complete/  the six platform sources behind the TUI's dropdowns (projects, repositories,
               release requests, tickets, runs, versions), over the credential the commands use

Two packages sit outside `access/`, because neither is about one command:

    ../tui/      `qits tui`: the screen over the whole command tree. api/ is its contract
                 (@TuiCommand, CompletionSource, @Completes), model/ the tree read from
                 CommandSpec, screen/ the lines, run/ the fork and the history, complete/ the
                 resolution and caching of sources. TuiApp is all of the behaviour, with no
                 terminal in it.
    ../session/  which of the CLI's two homes this is (Mode), where the services are in it
                 (PlatformEndpoints), the container's credential (AgentCredential), what a call
                 is made with either way (Credential), and BrowserGuard.

## Conventions

- **Plain Language everywhere**: comments, commit messages, documentation and every string the
  program prints. A comment says why, not what.
- **The help texts are the one source of SKILL.md.** A command's `description`, `footer` (examples
  are the lines with two spaces in front; the other lines are notes) and `exitCodeList` are
  picocli's own fields; no custom annotations, which would need reflection in the native binary.
  `SkillDocumentTest` fails when `SKILL.md` differs from the help: write it again with
  `./mvnw test -Dtest=SkillDocumentTest -Dqits.skill.update=true` and commit it with the change.
  Keep the help ASCII, and remember that picocli formats it (`%n` is a line break, `%%` a percent).
- **Never print or log a token** — not with a flag, not in an error, not in a test failure.
  `Session`, `TokenResponse` and `Pkce` override `toString` for that reason. An error from the idp
  names its status, `error` and `error_description`, never the request form. A Jackson error on a
  session or token body is replaced, because its message can quote the body.
  **The one exception is `qits git-credential get`**: it prints the git access token on stdout,
  because that is Git's credential helper protocol. Nothing else prints one, and stderr and logs
  never carry one, that command's included.
- **Every refresh and every write of `t.json` happens under the write lock**, and the file is read
  again under it. Refresh tokens rotate; a spent one presented again revokes the whole session.
  `SessionRefresh` is the only code that spends a `t.json` refresh token; the daemon and the
  platform commands both call it. Do not copy it. `git.json` follows the same rules under
  `git.json.lock` (`GitAccess`); its tokens are another client's (`qits-git-workstation`), so the
  two files never share a token. Both are written through `PrivateFiles`.
- **One place decides a service's address**: `PlatformEndpoints`. `PlatformUrls` keeps the commands'
  own flags and `QITS_<APP>_URL` variables and hands the rest to it. The public vhosts on a
  workstation, the wire aliases inside the platform, `QITS_URL_<APP>` over either. The epic
  *Remove the platform service concept* deletes the environment prefix and the platform/environment
  split; that must stay one edit there.
- **One credential interface**: `Credential`. `AccessTokens` is the workstation's, `AgentCredential`
  the container's, and `CliContext.credential()` picks by mode. A command never asks which it has.
  The two are never mixed: in-platform never opens the session file, and a workstation never mints
  with a client secret (`BothHomesTest`).
- **The TUI holds no command's name.** Everything it shows it read from picocli's model.
  `FictionalCommandTest` fails the build if a string literal under `eu.wohlben.qits.cli.tui` names a
  command. What the model cannot say is said by `@TuiCommand(interaction, output)` on the command
  and `@Completes(SomeSource.class)` on the option — both optional, both with a working default.
- **The CLI never sends `X-Qits-User` / `X-Qits-Roles`.** Those are what the gateway asserts about a
  caller; a client that writes them asserts a role it does not hold. An agent that needs a door its
  credential cannot open is granted the audience instead.
- **Platform answers are read as Jackson trees** (`JsonNode`), not records: the services add fields
  and grow their word lists, and a tree needs no reflection in the native binary.
- **Write first.** Once the idp answers a refresh, the old refresh token is spent: write the new
  pair before anything else.
- **Time comes from a `Clock` and a `Sleeper`.** Nothing in `daemon/` calls `Thread.sleep` or
  `Instant.now()` directly, so the tests move time instead of waiting.
- **Stopping never interrupts the daemon thread.** An interrupt during the file write closes the
  channel and loses a rotated token. `stop()` wakes the sleeper instead. `qits events` stops the
  same way, and aborts its open connection (`HttpClient.shutdownNow`), never the thread.
  `EventStream` waits through its `Sleeper`; only its idle watchdog reads `System.nanoTime`.
  `qits observe` does the same: `stop()` aborts the socket and puts a stop mark on the loop's queue.
- **Streamed telemetry is untrusted text.** Ingest takes records without a sign-in, so anyone who
  reaches it writes what `qits observe` shows. Every streamed value goes through `SafeText.line`
  (the line form) or `SafeText.JSON` (the JSON form), notices and close reasons included. A new
  field in the output goes through them too. A CI step's output is untrusted in the same way (the
  code the run builds writes it), so `qits ci` puts every value through `SafeText` as well. So does
  `qits ticket`: people and agents write a ticket's title, description and comments.
- **The observe wire protocol is `qits-observe-plan.md`** in the superproject, shared with
  qits-observability, which is built from the same text. Change it there first, and on both sides.
  The server sends no acknowledgement for a subscribe frame, so an `{"error": …}` before the first
  record is taken as its answer.
- Do not configure the idp from its discovery document: it names the idp's internal issuer.
- The PKCE and token code, `GitOrigin` and `LoopbackCallback` were copied from qits-bootstrap-cli.
  Do not share a jar with it.
- **Git setup is per host.** `qits git-login` sets `credential.<git host>.helper` and never
  `credential.helper`: the person's global helper serves GitHub and must stay. A test that runs
  `git config --global` sets `GIT_CONFIG_GLOBAL` to a scratch file first.
- **`qits artifacts publish` never touches `t.json`, `git.json` or their locks.** It runs in a CI
  step container with no person signed in, so its commands read only `CliContext.env()`, `.out()`
  and `.err()` — never `.sessionFile()` or `.tokens()`. If a change to `publish/` needs either, that
  change belongs somewhere else.
- **A `publish/` command that has its own `--version` flag must not use
  `mixinStandardHelpOptions`.** The mixin's `-V`/`--version` (the binary's own version) collides by
  name with qits-publish's `--version` (the artifact version), and picocli then recognises neither
  the mixin's `--version` nor `--help` on that command — proven the hard way, and guarded by
  `CommandSurfaceTest.helpWorksOnEveryCommandThatHasItsOwnVersionFlag`. Declare `-h`/`--help` alone
  instead (`usageHelp = true`, no `versionHelp`).

## Build forms

    sdk env && ./mvnw package -Dnative -DskipTests   the binary: platform-access-cli/target/qits
    ./mvnw clean verify                              the tests; packages only the pin jar
    docker build --target binary --output type=local,dest=out -f docker/Dockerfile .
                                                     the released form: static musl, out/qits

All three are ROOT invocations and build the whole reactor. The pin module is one class with no
main-scope dependency, so it costs seconds next to a native compile and is never worth skipping.

The application module builds no jar; its pom keeps it so the same way qits-bootstrap-cli's does.
The one jar this repository produces is `platform-access-cli-binary`'s, and it carries three
strings rather than any of this code — see Two modules above. Run `clean verify` before a native
build, never after: `clean` removes the binary. `.sdkmanrc` pins 25.0.2-graalce.

The released binary is static (musl) and the host build is not: only `docker/Dockerfile` adds
`--static --libc=musl`, with `additional-build-args-append`, so the pom's own build args stay. The
two recipes in `.config/qits/` build that Dockerfile on the platform's BuildKit, and the release
recipe publishes `out/qits` as the daemon binary `qits-platform-access-cli` and then, in a second
step on a maven image, deploys the pin jar that names it (README, Releases). The export layout is
what keeps those recipes short: the binary lands in `platform-access-cli/target/` now, but the
Dockerfile's `binary` stage still exports `out/qits` and its `sbom` stage `/sbom.json`, so nothing
outside the Dockerfile moved when the reactor split.
Keep their `buildctl` calls identical, argument for argument: the builder's cache is shared, and
that is what makes a release after a green fold cache hits. The musl toolchain is a stage of that
Dockerfile, copied from qits-ci-daemon's `docker/Dockerfile.musl-builder`: no registry tag, so a cold
platform needs no other repository's run. Keep the copy identical to that file. The recipes pass the
tarballs' in-network URLs; off the platform, pass the build args the Dockerfile's header names.

Native rules: HTTP and WebSocket with `java.net.http`, never `java.awt` (the browser is `wslview`/`xdg-open`). A
class with a `SecureRandom` in a static field is initialised at run time (`Pkce`, in the native
profile). Records Jackson reads or writes carry `@RegisterForReflection`. A change to any of these
is proven with the native binary, not with the tests.

JLine is asked for its `exec` provider by name and the pom depends on `jline-terminal`, never the
aggregate `jline`: the jni and ffm providers do not survive GraalVM's analysis. `org.jline.nativ` is
initialised at run time for the same reason. `NativeImageRulesTest` fails the build if a forbidden
provider reaches the classpath, and `docker/Dockerfile` runs `qits tui` in the built binary with no
terminal and checks it says so and exits 2 rather than waiting for a key. An enum used as an option
type needs `@RegisterForReflection` for the TUI to list its constants; without it the option is
typed rather than picked, never a crash.

## Tests

`./mvnw clean verify` must be green on a clone with **no docker and no platform**. No test reaches
a real idp: `FakeIdp` is an idp in the test's own process (`com.sun.net.httpserver`), and
`FakeTime` is the clock and the sleeper. The lock tests start a second JVM (`LockHolder`), because
an fcntl lock works between processes and a test in one JVM would only prove the in-process gate.
`FakePlatform` is the edge and the services: canned JSON by method and path, and an SSE route whose
connections follow scripts. `FakeSocketServer` is a WebSocket server on a plain `ServerSocket` (the
RFC 6455 handshake; text, ping, pong and close frames), one script per connection; it needs only the
JDK, so a native proof can run it from `target/test-classes`. `PlatformCommandsTest` runs picocli in the test's process with its own
`CliContext` (environment, stdout, stderr, clock), so a command test needs no second process.

What only a person can prove: a real sign-in in the browser, and a daemon that rotates the session
against the real idp for longer than one access token lives.

## Gotchas

- Java's file locks are fcntl record locks. The kernel drops a process's lock when ANY descriptor
  of that file closes, so nothing may open a lock file except `ExclusiveLock`. Inside one JVM an
  overlapping lock throws rather than waits; `ExclusiveLock`'s per-path semaphore covers that.
- An atomic rename gives `t.json` a new inode. Watch it by polling (`SessionFile.fingerprint`), not
  with a watch on the old inode.
