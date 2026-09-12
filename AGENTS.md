# AGENTS.md — qits-platform-access-cli

## What this repository is

The `qits` command: access to the qits platform from a Linux or WSL workstation. A Quarkus
command-mode CLI with picocli, built as a GraalVM native binary. Today it has two commands:
`qits login` (browser sign-in, session stored in `$XDG_CONFIG_HOME/qits/t.json`) and
`qits session-daemon` (keeps that session fresh). The README says how each behaves.

## Layout

    idp/       the idp's /token endpoint and which idp to use
    session/   the session file, its atomic write, and the two locks
    login/     qits login: PKCE, the pasted code, the browser opener
    daemon/    qits session-daemon: the refresh loop, the sleeper seam, the stop signals

## Conventions

- **Plain Language everywhere**: comments, commit messages, documentation and every string the
  program prints. A comment says why, not what.
- **Never print or log a token** — not with a flag, not in an error, not in a test failure.
  `Session`, `TokenResponse` and `Pkce` override `toString` for that reason. An error from the idp
  names its status, `error` and `error_description`, never the request form. A Jackson error on a
  session or token body is replaced, because its message can quote the body.
- **Every refresh and every write of `t.json` happens under the write lock**, and the file is read
  again under it. Refresh tokens rotate; a spent one presented again revokes the whole session.
- **Write first.** Once the idp answers a refresh, the old refresh token is spent: write the new
  pair before anything else.
- **Time comes from a `Clock` and a `Sleeper`.** Nothing in `daemon/` calls `Thread.sleep` or
  `Instant.now()` directly, so the tests move time instead of waiting.
- **Stopping never interrupts the daemon thread.** An interrupt during the file write closes the
  channel and loses a rotated token. `stop()` wakes the sleeper instead.
- Do not configure the idp from its discovery document: it names the idp's internal issuer.
- The PKCE and token code was copied from qits-bootstrap-cli. Do not share a jar with it.

## Build forms

    sdk env && ./mvnw package -Dnative -DskipTests   the binary: target/qits
    ./mvnw clean verify                              the tests; packages nothing

There is no jar; the pom keeps it so the same way qits-bootstrap-cli's does. Run `clean verify`
before a native build, never after: `clean` removes the binary. `.sdkmanrc` pins 25.0.2-graalce.

Native rules: HTTP with `java.net.http`, never `java.awt` (the browser is `wslview`/`xdg-open`). A
class with a `SecureRandom` in a static field is initialised at run time (`Pkce`, in the native
profile). Records Jackson reads or writes carry `@RegisterForReflection`. A change to any of these
is proven with the native binary, not with the tests.

## Tests

`./mvnw clean verify` must be green on a clone with **no docker and no platform**. No test reaches
a real idp: `FakeIdp` is an idp in the test's own process (`com.sun.net.httpserver`), and
`FakeTime` is the clock and the sleeper. The lock tests start a second JVM (`LockHolder`), because
an fcntl lock works between processes and a test in one JVM would only prove the in-process gate.

What only a person can prove: a real sign-in in the browser, and a daemon that rotates the session
against the real idp for longer than one access token lives.

## Gotchas

- Java's file locks are fcntl record locks. The kernel drops a process's lock when ANY descriptor
  of that file closes, so nothing may open a lock file except `ExclusiveLock`. Inside one JVM an
  overlapping lock throws rather than waits; `ExclusiveLock`'s per-path semaphore covers that.
- An atomic rename gives `t.json` a new inode. Watch it by polling (`SessionFile.fingerprint`), not
  with a watch on the old inode.
