# AGENTS.md — qits-platform-access-cli

## What this repository is

The `qits` command: access to the qits platform from a Linux or WSL workstation. A Quarkus
command-mode CLI with picocli, built as a GraalVM native binary. Its commands: `qits login`
(browser sign-in, session stored in `$XDG_CONFIG_HOME/qits/t.json`), `qits session-daemon` (keeps
that session fresh), `qits projects|repositories|release-request` (the projects service),
`qits ci runs|run|retry` (qits-ci's runs, a run's step logs, a retry), `qits events` (the live event
stream), `qits observe` (the live, server-filtered telemetry stream
of qits-observability, over a WebSocket), and `qits git-login` / `qits git-credential` (Git pushes to
`refs/heads/external/*`, sign-in stored in `$XDG_CONFIG_HOME/qits/git.json`). The README says how
each behaves.

## Layout

    idp/       the idp's /token endpoint and which idp to use
    session/   the session file, its atomic write, the two locks, and the one refresh (SessionRefresh)
    login/     qits login: PKCE, the pasted code, the browser opener
    daemon/    qits session-daemon: the refresh loop, the sleeper seam, the stop signals
    platform/  what every platform command shares: the context, the token (with inline refresh),
               the HTTP client and its error messages, which address a service has (PlatformUrls),
               and the aligned table (Table)
    projects/  qits projects, repositories and release-request
    ci/        qits ci: runs, run (with the step logs) and retry, on qits-ci's run API
    events/    qits events: the SSE parser and the reconnecting stream
    observe/   qits observe: the --filter grammar, the reconnecting WebSocket stream, the line form,
               and SafeText (terminal control characters out of every streamed value)
    git/       qits git-login and git-credential: the loopback callback, git.json, Git's helper
               protocol, the per-host Git setup
    help/      qits help skill (hidden): the commands' help arranged as SKILL.md

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
- **One place decides a service's address**: `PlatformUrls`. A later in-platform mode (internal
  names, a token from the container) goes there, not into the commands.
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
  code the run builds writes it), so `qits ci` puts every value through `SafeText` as well.
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

## Build forms

    sdk env && ./mvnw package -Dnative -DskipTests   the binary: target/qits
    ./mvnw clean verify                              the tests; packages nothing
    docker build --target binary --output type=local,dest=out -f docker/Dockerfile .
                                                     the released form: static musl, out/qits

There is no jar; the pom keeps it so the same way qits-bootstrap-cli's does. Run `clean verify`
before a native build, never after: `clean` removes the binary. `.sdkmanrc` pins 25.0.2-graalce.

The released binary is static (musl) and the host build is not: only `docker/Dockerfile` adds
`--static --libc=musl`, with `additional-build-args-append`, so the pom's own build args stay. The
two recipes in `.config/qits/` build that Dockerfile on the platform's BuildKit, and the release
recipe publishes `out/qits` as the daemon binary `qits-platform-access-cli` (README, Releases).
Keep their `buildctl` calls identical, argument for argument: the builder's cache is shared, and
that is what makes a release after a green fold cache hits. The musl toolchain is a stage of that
Dockerfile, copied from qits-ci-daemon's `docker/Dockerfile.musl-builder`: no registry tag, so a cold
platform needs no other repository's run. Keep the copy identical to that file. The recipes pass the
tarballs' in-network URLs; off the platform, pass the build args the Dockerfile's header names.

Native rules: HTTP and WebSocket with `java.net.http`, never `java.awt` (the browser is `wslview`/`xdg-open`). A
class with a `SecureRandom` in a static field is initialised at run time (`Pkce`, in the native
profile). Records Jackson reads or writes carry `@RegisterForReflection`. A change to any of these
is proven with the native binary, not with the tests.

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
