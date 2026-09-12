# qits-platform-access-cli

The `qits` command: access to the qits platform from a workstation, with defaults that need no
setup. A static native binary for Linux and WSL.

Commands:

- `qits login` signs you in through the browser and stores the session.
- `qits session-daemon` keeps that session fresh for as long as it runs.
- `qits projects list`, `qits repositories … list` and `qits release-request … list|create` read
  from and ask the projects service.
- `qits events` prints the platform's domain events as they happen.
- `qits git-login` signs this workstation in for Git pushes to the platform's git host, and
  `qits git-credential` is the Git credential helper that uses that sign-in.

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

## Platform commands

    qits projects list
    qits repositories --project <project> list
    qits release-request --project <project> --repository <repository> list [--state <STATE|all>]
    qits release-request --project <project> --repository <repository> create \
        --branch <branch> --summary <text> [--priority <priority>]
    qits events [--filter=<names>]

They call the platform through its edge over HTTPS, with the access token from `qits login` as a
bearer. The options of `projects`, `repositories` and `release-request` may come before or after
the subcommand: `qits repositories --project qits list` and `qits repositories list --project qits`
are the same.

### The session

Each command reads `t.json`. With no file it prints `Not signed in — run `qits login`.` and exits
with 2.

With `qits session-daemon` running, the commands only read the file. Without it, a command whose
access token has less than 30 seconds left refreshes it first, the way the daemon does: under
`t.json.lock`, after reading the file again. If the daemon or another command refreshed in the
meantime, the command uses that pair, so a refresh token is never spent twice. A refresh the idp
refuses prints `Session ended — run `qits login`.` and exits with 2.

### Which address

A service lives at `<app>.<env>.<domain>`. The commands take the session's idp address and swap
its first label: `https://idp.dev.wohlben.eu/idp` gives `https://projects.dev.wohlben.eu` and
`https://events.dev.wohlben.eu`. To name the address yourself (a base URL, without `/projects` or
`/events`):

- projects: `--projects-url`, else `QITS_PROJECTS_URL`
- events: `--events-url`, else `QITS_EVENTS_URL`

### Output, errors and exit codes

`--output table` (the default) prints aligned columns. `--output json` (or `-o json`) prints the
service's answer, pretty-printed. The default `release-request list` leaves out the released
requests in both forms (see below).

When the platform refuses:

- HTTP 401: `The platform refused the token (HTTP 401: <error>)`, with the error from the
  `WWW-Authenticate` header when there is one.
- HTTP 403: `Your roles do not allow this (HTTP 403)`. These calls need `qits:admin` or
  `qits:system`.
- Any other status: the method, the address, the status, and the service's own message.

Exit codes: 0 done; 1 the platform refused, or cannot be reached; 2 the command was used wrongly,
there is no session, or the session ended.

### qits projects list

Columns: slug, name, id.

### qits repositories --project \<project\> list

`--project` is the project's id, slug or name. The command lists the projects and finds the one
that matches; a value that matches none, or more than one, stops with a message. Columns: name,
archetype, component, id.

### qits release-request

`--project` as above. `--repository` is the repository's id or name within that project.

`list` shows the open requests: every state but RELEASED and WITHDRAWN. The service's default
answer holds the open requests and the last 10 released ones; the command drops the released.
`--state all` shows every request, and `--state PENDING` (or READY, RELEASED, REJECTED, FAILED,
CONFLICTED, WITHDRAWN) shows one state. Columns: id (the first 8 characters), state, priority,
summary, version, updated.

`create` asks for a branch to be released once its builds are green. `--branch` and `--summary`
are required; `--priority` is LOWEST, LOW, MEDIUM, HIGH, HIGHER or BLOCKING (the platform's
default is MEDIUM). The platform may answer with a new request, with the open request that already
holds the branch, or (on a project wrapper) with the open request the branch joined. The command
prints the request that came back, with its sources.

    qits release-request --project qits --repository qits-ci-service list
    qits release-request --project qits --repository qits-ci-service list --state all -o json
    qits release-request --project qits --repository qits-ci-service create \
        --branch feature/log-view --summary "Show the build log live" --priority HIGH

### qits events

    qits events [--filter=<names>] [--events-url <url>]

Prints the domain events of qits-events as they happen, one JSON object per line on stdout, each
line flushed at once, so `| jq` shows an event when it arrives:

    {"id":"…","name":"BuildSuccessful","occurredAt":"…","payload":{"repoName":"qits-ci-service",…},"description":null,"parentId":null,"environment":"dev"}

`payload` travels as a JSON string; the command reads it into JSON. A payload that is not JSON
stays a string.

The filter is written as services write their subscriptions: exact event names, comma-separated,
or `*` for every event. `*` is the default. There are no patterns and no filter by source.

    qits events --filter=BuildSuccessful,BuildFailed
    qits events --filter=SCMPublishCommit,SCMDeleteBranch
    qits events --filter=ReleaseRequestChanged | jq -r '.payload'

The stream is live only: it has no replay. Notes go to stderr, one line each with a time.

- A dropped connection or a 5xx is followed by a reconnect, waiting 1, 2, 4 … up to 30 seconds.
  The note says that events in the gap are missed.
- A connection that sends nothing for 60 seconds (the service sends a keepalive every 20) counts as
  dropped. After a suspend a connection can look open on this side and be gone on the other.
- A 401 or 403 (or any other 4xx) stops the command with exit code 1.
- SIGINT (Ctrl-C) or SIGTERM stops it with exit code 0.
- When stdout is closed (`| head -3`), it stops, with exit code 0, at the next event it would
  print. Keepalives print nothing, so on a quiet stream that can take a while.

## Git pushes from this workstation

    qits git-login [--idp-url <url>] [--git-host <url>] [--audience <audience>]
                   [--timeout <seconds>] [--no-browser] [--configure]
    qits git-credential <get|store|erase>      Git runs this; a person does not

`qits git-login` signs this workstation in for Git pushes to the platform's git host. The sign-in
may push branches under `refs/heads/external/` and nothing else; the git host checks that. It needs
no keyring: the sign-in is a file, like the session of `qits login`.

1. It prints the sign-in address and tries to open it (`wslview` on WSL, then `xdg-open`).
   `--no-browser` only prints it.
2. You sign in. The browser comes back to a one-time address on this machine
   (`http://127.0.0.1:<port>/callback`), and the command takes the answer from there. It waits
   `--timeout` seconds (default 300). An answer for another sign-in (its `state` differs) is
   refused.
3. The sign-in goes to `$XDG_CONFIG_HOME/qits/git.json` (0600, in the 0700 directory), under the
   git host's address. The command prints how long it lasts and the Git setup.

Which platform:

- idp: `--idp-url`, else `QITS_IDP_URL`, else the idp of the `qits login` session, else as
  `qits login` finds it.
- git host: `--git-host`, else `QITS_GIT_HOST_URL`, else the idp's host with `idp` swapped for
  `githost` (`https://githost.dev.wohlben.eu`).
- audience: `--audience`, else `<env>-qits-githost`, where `<env>` is the idp host's second label
  (`dev` in `idp.dev.wohlben.eu`).

The OAuth client is `qits-git-workstation` (PKCE, no secret), not the `qits-cli` of `qits login`.

### Git setup, for this host only

Git asks `qits git-credential` for the git host and for no other host, so a global helper (for
example Git Credential Manager for GitHub) stays as it is. The empty value first clears the helper
list for this host:

    git config --global --replace-all credential.https://githost.dev.wohlben.eu.helper ''
    git config --global --add credential.https://githost.dev.wohlben.eu.helper '!/home/you/.local/bin/qits git-credential'

`qits git-login` prints these two lines with the git host and its own path. `--configure` runs them.
Running them again changes nothing.

### qits git-credential

Git sends the token as HTTP Basic `oauth2:<access token>`; the edge checks it and forwards it to the
git host.

- `get`: for a git host with a sign-in, prints `username=oauth2` and the access token as
  `password`. An access token with more than 60 seconds left is used as it is. Otherwise it is
  refreshed first, under `git.json.lock` and after reading the file again (another Git process may
  have refreshed it), and the new pair is written before the token is printed. A host without a
  sign-in gets no answer, and Git asks its other helpers.
- `store`: ignored. Only `qits git-login` stores a sign-in.
- `erase`: Git erases after any refused request. Only the cached access token goes; the sign-in
  stays, so a passing 401 does not cost a new browser sign-in.
- A refresh the idp refuses prints `Git sign-in ended — run `qits git-login`.` on stderr and
  nothing on stdout.

`git-credential get` is the one place `qits` prints a token, because that is how Git's helper
protocol works. stderr never carries one.
