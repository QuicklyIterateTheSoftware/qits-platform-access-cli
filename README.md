# qits-platform-access-cli

The `qits` command: access to the qits platform from a workstation, with defaults that need no
setup. A static native binary for Linux and WSL.

This first version has two commands:

- `qits login` signs you in through the browser and stores the session.
- `qits session-daemon` keeps that session fresh for as long as it runs.

The binary is called `qits`. qits-bootstrap-cli's binary is `qits-bootstrap`.

## Build

    sdk env && ./mvnw package -Dnative -DskipTests   the binary: target/qits
    ./mvnw clean verify                              the tests; packages nothing

`.sdkmanrc` names the GraalVM (25.0.2-graalce), so `sdk env` sets `JAVA_HOME`. Without sdkman:
`JAVA_HOME=$HOME/.sdkman/candidates/java/25.0.2-graalce ./mvnw package -Dnative -DskipTests`.
There is no jar. Run `clean verify` before a native build, not after: `clean` removes the binary.

The tests need no docker and no platform. Copy `target/qits` to a directory on your `PATH`, for
example `~/.local/bin`.

## qits login

    qits login [--idp-url <url>] [--no-browser]

1. Prints the sign-in address and tries to open it (`wslview` on WSL, then `xdg-open`).
2. You sign in. The page shows a code.
3. Paste the code at `Paste the code:`. A mistyped or used code asks again, while the codes of
   this sign-in are still valid (5 minutes). Ctrl-C ends with nothing written.
4. The session goes to `$XDG_CONFIG_HOME/qits/t.json` (default `~/.config/qits/t.json`), and the
   command prints when the access token and the session end.

Which platform: `--idp-url`, else `QITS_IDP_URL`, else `https://idp.<QITS_ENV_NAME>.<QITS_DOMAIN>/idp`.
With `QITS_DOMAIN` set and `QITS_ENV_NAME` not set, the command stops and names the variable. With
neither, it uses the platform on this machine, `http://idp.<QITS_ENV_NAME or prod>.localhost:8080/idp`.

For the dev platform:

    qits login --idp-url https://idp.dev.wohlben.eu/idp

`--no-browser` only prints the address. Use it over SSH.

### The session file

    {
      "idpUrl" : "https://idp.dev.wohlben.eu/idp",
      "clientId" : "qits-cli",
      "accessToken" : "…",
      "accessExpiresAt" : "2026-09-11T20:15:00Z",
      "refreshToken" : "…",
      "refreshExpiresAt" : "2026-10-11T20:00:00Z"
    }

The directory is 0700 and the file 0600. Each write goes to a temporary file in the same directory,
is synced, and is then renamed over `t.json`, so a reader never sees half a file. The times are
absolute. The session end moves forward with each refresh.

`qits` never prints or logs a token.

## qits session-daemon

    qits session-daemon [--margin <seconds>]

Refreshes the access token `--margin` seconds (default 30) before it expires, and writes the new
pair to `t.json`. It logs one line per event to stderr. It runs in the foreground; add `&`, or run
it as a systemd user service (below).

- With no session file, it says so and waits for `qits login`.
- A refresh the idp refuses with `invalid_grant` ends the session. The daemon logs
  `session ended (revoked or expired) — run `qits login``, keeps running, and carries on when a new
  session file appears.
- A connection error or a 5xx is retried, waiting 1, 2, 4 … up to 60 seconds between tries, until
  the session ends.
- It reads the wall clock after each short sleep, so after a suspend (or WSL's clock jump) it
  refreshes at once. An access token that has already expired is refreshed, not an error.
- SIGTERM or SIGINT stops it cleanly, with exit code 0.

### Two lock files

Refresh tokens rotate, and the idp revokes the whole session when a spent refresh token comes back,
even from an honest race. So only one process may refresh at a time. One lock cannot do both jobs
this needs, so there are two, beside `t.json`:

- `t.json.lock` — the write lock. The daemon holds it for a short time around each
  read-refresh-write, and `qits login` around its write. It reads the file again under the lock, so
  a new login is never overwritten.
- `session-daemon.lock` — held for a daemon's whole life. A second daemon that cannot take it says
  so and exits with code 1.

Both are exclusive fcntl locks (what Java takes on Linux), not flock(2). The shell's `flock` tool
does not see them.

### As a systemd user service

WSL here runs systemd. A sample unit, `~/.config/systemd/user/qits-session-daemon.service`:

    [Unit]
    Description=Keep the qits session fresh

    [Service]
    ExecStart=%h/.local/bin/qits session-daemon
    Restart=on-failure
    RestartSec=30

    [Install]
    WantedBy=default.target

Then:

    systemctl --user daemon-reload
    systemctl --user enable --now qits-session-daemon
    journalctl --user -u qits-session-daemon -f

Installing it is not part of this version.
