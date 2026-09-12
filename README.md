# qits-platform-access-cli

The `qits` command: access to the qits platform from a workstation, with defaults that need no
setup. A static native binary for Linux and WSL.

Commands:

- `qits login` signs you in through the browser and stores the session.
- `qits session-daemon` keeps that session fresh for as long as it runs.
- `qits projects list`, `qits repositories … list` and `qits release-request … list|create|join`
  read from and ask the projects service.
- `qits ci runs|run|retry` lists a repository's CI runs, shows a run with its steps and their logs,
  and runs a finished run again.
- `qits events` prints the platform's domain events as they happen.
- `qits observe` prints what qits-observability takes in (logs, spans, metrics) as it arrives,
  filtered by the service.
- `qits git-login` signs this workstation in for Git pushes to the platform's git host, and
  `qits git-credential` is the Git credential helper that uses that sign-in.

The binary is called `qits`. qits-bootstrap-cli's binary is `qits-bootstrap`.

## Download

Each release publishes the static binary to the platform's artifacts store, under this repository's
name and the released version, the way qits-ci-daemon and qits-artifacts-cli are published:

    https://registry.<env>.<domain>/artifacts/daemons/qits-platform-access-cli/<version>

It is one file with no dependencies, so it runs as it is on any x86-64 Linux, WSL and alpine
included:

    curl -fsSL -H "Authorization: Bearer <token>" -o qits \
      https://registry.<env>.<domain>/artifacts/daemons/qits-platform-access-cli/<version> \
      && chmod +x qits

**The download needs your own token.** The edge guards the registry host like every other host
and answers 401 without one. Use your own sign-in, never a machine client's id and secret.

- **The first time**, without `qits`: sign in to the platform in the browser, then open the address
  in the same browser. The edge session is your token, and the browser saves the file. Then
  `chmod +x qits`.
- **After that**, `qits` holds your token. `qits login` writes it to `t.json`, and
  `qits session-daemon` keeps it fresh (or run any `qits` command first: it refreshes a token that
  is about to expire). Use it as `<token>`:

      token=$(jq -r .accessToken "${XDG_CONFIG_HOME:-$HOME/.config}/qits/t.json")

- **There is no `latest` address.** A version is published once and never changes, and the store
  keeps no moving pointer. The newest version is `latestVersion` in the store's list of daemons (in
  the browser, open the same address):

      curl -fsSL -H "Authorization: Bearer <token>" \
        https://registry.<env>.<domain>/artifacts/api/repositories/daemons/daemons \
        | jq -r '.daemons[] | select(.name == "qits-platform-access-cli") | .latestVersion'

- Inside the platform network, the store's own address needs no token:
  `http://qits-platform-artifacts:8080/artifacts/daemons/qits-platform-access-cli/<version>`.

- The answer carries `Docker-Content-Digest: sha256:…`, the digest the release log prints.
- The store keeps the last two versions of every daemon. An older one goes after 90 days in which
  nobody downloaded it.

## Build

    sdk env && ./mvnw package -Dnative -DskipTests   the binary for this host: target/qits
    ./mvnw clean verify                              the tests; packages nothing
    docker build --target binary --output type=local,dest=out -f docker/Dockerfile .
                                                     the released form, static: out/qits

`.sdkmanrc` names the GraalVM (25.0.2-graalce), so `sdk env` sets `JAVA_HOME`. Without sdkman:
`JAVA_HOME=$HOME/.sdkman/candidates/java/25.0.2-graalce ./mvnw package -Dnative -DskipTests`.
There is no jar. Run `clean verify` before a native build, not after: `clean` removes the binary.

The tests need no docker and no platform. Copy `target/qits` to a directory on your `PATH`, for
example `~/.local/bin`.

The host build is glibc-linked. The released binary is static (musl), so it also runs on alpine.
`docker/Dockerfile` builds it inside the musl toolchain image `qits/graalvmce-musl-builder:jdk-25`,
which qits-ci-daemon makes: in that repository, `docker build -t qits/graalvmce-musl-builder:jdk-25
-f docker/Dockerfile.musl-builder docker/`. The build fails unless `ldd` finds the binary static;
`file out/qits` says `statically linked`. A second target, `--target sbom`, exports the release's
CycloneDX document from the same build.

## Releases

Only through a release request, like every repository here. `.config/qits/` holds the two recipes,
shaped like qits-artifacts-cli's:

- `ci-event-release-request.yml` gates a request's fold: `./mvnw verify`, then the static binary on
  the platform's BuildKit, and a `--help` run of it on an alpine image.
- `ci-event-release.yml` runs on the release tag: the same build and its SBOM, then a PUT of the
  binary to `/artifacts/daemons/qits-platform-access-cli/<version>` and of the SBOM to
  `/artifacts/sboms/daemon/qits-platform-access-cli/-/<version>`. It declares the artifact
  `{type: daemon, name: qits-platform-access-cli}`, so qits-ci announces the release. A version that
  exists already (HTTP 409) fails the release: a version is never published twice.

The toolchain image is not built here. The recipes use the `graalvmce-musl-builder:jdk-25` tag that
qits-ci-daemon's pipelines push.

## Using qits from an agent

`qits help skill` prints the help of every command as a SKILL.md: when to use qits, the platform
rules, and each command with its options, examples and exit codes. It is hidden from `qits --help`.
To give it to Claude Code:

    mkdir -p ~/.claude/skills/qits && qits help skill > ~/.claude/skills/qits/SKILL.md

The repository holds the same file as `SKILL.md`. The help texts are its one source: a test fails
when the file differs from them, and `./mvnw test -Dtest=SkillDocumentTest -Dqits.skill.update=true`
writes it again.

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
    qits release-request --project <project> --repository <repository> join \
        --request <id> --branch <branch> [--priority <priority>]
    qits ci runs --project <project> --repository <repository> [--branch <branch>] \
        [--status <STATUS>] [--release-request <id>] [--limit <n>]
    qits ci run <run id> [--logs] [--project <project> --repository <repository>]
    qits ci retry <run id> [--project <project> --repository <repository>]
    qits events [--filter=<names>]
    qits observe --filter <conditions> [--filter <conditions> …] [-o json]

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
its first label: `https://idp.dev.wohlben.eu/idp` gives `https://projects.dev.wohlben.eu`,
`https://ci.dev.wohlben.eu`, `https://events.dev.wohlben.eu` and
`https://observability.dev.wohlben.eu`. To name the address yourself (a base URL, without
`/projects`, `/ci`, `/events` or `/observability`):

- projects: `--projects-url`, else `QITS_PROJECTS_URL`
- ci: `--ci-url`, else `QITS_CI_URL`
- events: `--events-url`, else `QITS_EVENTS_URL`
- observability: `--observability-url`, else `QITS_OBSERVABILITY_URL`. The stream is a WebSocket,
  so `https` becomes `wss` and `http` becomes `ws`.

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

`join` adds a branch to an open request. `--request` is the request's id, or enough of its start to
name one: the 8 characters `list` shows are enough. The command looks among all the repository's
requests, of every state; a start that fits none, or more than one, stops with exit code 2 and
names what it found. `--branch` is required; `--priority` is as for `create`. The platform folds the
request again with the branch and, if that makes a new commit, its builds run on that commit. The
command prints the request that came back, with its sources, like `create`.

- A branch already on the request adds nothing. With `--priority` it states that priority again;
  without, the branch keeps the priority it has.
- A RELEASED or WITHDRAWN request takes no more branches (HTTP 409, exit code 1). Open a new one
  with `create`.
- HTTP 404 means the platform has no such request (exit code 1). A branch the git host does not
  have is not a 404: the fold fails, and the request's detail says why.

    qits release-request --project qits --repository qits-ci-service list
    qits release-request --project qits --repository qits-ci-service list --state all -o json
    qits release-request --project qits --repository qits-ci-service create \
        --branch feature/log-view --summary "Show the build log live" --priority HIGH
    qits release-request --project qits --repository qits-ci-service join \
        --request 4f2a91c0 --branch feature/log-search

A red gating build that was the platform's fault and not the code's (a flaked container, a registry
that was down) is retried with `qits ci retry`, not with a new request.

### qits ci

The builds of qits-ci. `--project` and `--repository` are as for `release-request`, and may come
before or after the command. Reading runs needs the role `qits:admin` or `qits:system`; `retry`
needs `qits:admin`.

`runs` lists a repository's runs, newest first. Columns: id (the first 8 characters), status,
branch, commit, release request, created, and how long the run took (so far, while it runs). The
service filters by repository and count only. So `--branch`, `--status` and `--release-request` are
applied here, over all of the repository's runs, and `--limit` (default 20) after them. Without a
filter, `--limit` goes to the service.

A release request's gating runs build its backing branch `release/<request id>` and carry the
request's id. `--release-request` takes that id, or its start (the 8 characters
`qits release-request list` shows).

`run` shows one run: what it built, its status, trigger and times, and its steps with their exit
codes. `--logs` adds each step's output. The service keeps the end of each step's output, and for a
running step, what it has printed so far; there is no live stream, so run the command again to
follow a run. The output is written by the code the run builds, so the command takes terminal
control characters out of every line, as `qits observe` does, and `-o json` writes them as escapes.
The exit code says whether the read worked, not whether the run passed.

`retry` runs a finished run again: the same commit, pipeline and release request. It prints the new
run's id and how to follow it. A run that has not finished yet answers HTTP 409, and an unknown run
HTTP 404; both exit with 1.

`run` and `retry` take the whole run id. With `--project` and `--repository` they also take the
start of it, looked up among that repository's runs.

    qits ci runs --project qits --repository qits-ci-service --release-request 4f2a91c0
    qits ci run 5f2c0a9e --project qits --repository qits-ci-service --logs
    qits ci retry 5f2c0a9e --project qits --repository qits-ci-service

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

### qits observe

    qits observe --filter <conditions> [--filter <conditions> …] [-o json] [--observability-url <url>]

Prints what qits-observability takes in, as it arrives: logs, spans (with their events, such as
`exception`) and metrics. Domain events stay on `qits events`. The command sends its filters over a
WebSocket, `wss://observability.<env>.<domain>/observability/stream`; the service checks each record
and sends only the ones that match. It needs the role `qits:admin`.

    qits observe --filter 'kind=log level>=ERROR' \
                 --filter 'trace=4bf92f3577b34da6a3ce929d0e0e4736' \
                 --filter 'kind=span event=exception'

One `--filter` is one group. The conditions of a group are separated by spaces, and all of them
must hold. A record that fits any group is printed. At least one `--filter` is required;
`--filter '*'` streams every record.

| written | means |
|---|---|
| `F=V` | the field is V (matches case: `status=ERROR`, not `status=error`) |
| `F^=V` | the field starts with V (matches case) |
| `F~V` | the field contains V (any case) |
| `F?` | the field is there and not empty |
| `!F` | the field is not there |
| `level>=V` | the severity is V or higher: TRACE, DEBUG, INFO, WARN (or WARNING), ERROR, FATAL, or a number 1-24 |

| field | applies to | is |
|---|---|---|
| `kind` | all | `log`, `span` or `metric` |
| `service` | all | the resource's `service.name` |
| `trace`, `span` | log, span | the trace id and the span id, lowercase hex (the command lowercases what you give) |
| `level` | log | the severity; takes `>=` only |
| `body` | log | the message |
| `name` | span, metric | the span's or the metric's name |
| `status` | span | `OK`, `ERROR` or `UNSET` |
| `event` | span | the name of any of the span's events, for example `exception` |
| `attr.<key>` | all | the record's own attribute `<key>` |
| `resource.<key>` | all | the resource attribute `<key>` |

Everything after `attr.` or `resource.` is the key, dots included: `attr.exception.type`. A span's
exception is an event of the span, not an attribute of it, so `attr.exception.type?` matches logs
and `event=exception` matches spans. A field that a record does not have fails every condition but
`!F`. Quote a value that holds spaces: `body~"connection refused"`; inside the quotes, `\"` is a
quote and `\\` a backslash. A condition the command cannot read stops it with exit code 2, and the
message names that condition. Nothing is sent before every condition reads.

    qits observe --filter '*'
    qits observe --filter 'service^=qits-ci kind=log body~"connection refused"'
    qits observe --filter 'resource.service.version=2026.912.1 status=ERROR'
    qits observe --filter 'kind=log level>=WARN' -o json | jq -r .record.body

The default output is one line per record, flushed at once:

    12:03:11.123 log    qits-ci ERROR connection refused  [4bf92f35]
    12:03:11.140 span   qits-ci ERROR GET /ci/api/builds  [4bf92f35]
    12:03:12.000 metric qits-ci 42 ms http.server.duration

That is the local time (the record's own, else when the service received it), the kind, the
service, the level (log), the status (span) or the value and its unit (metric), the body (log) or
the name (span, metric), and the first 8 characters of the trace id. `-o json` prints each frame as
the service sends it, one per line; `record` is what the service's query API returns for that kind:

    {"kind":"log","receivedAtMillis":1757685791123,"source":"_service/qits-ci","record":{…}}

Ingest takes records without a sign-in, so a record can say anything, escape sequences included.
The line form removes every terminal control character from every value: C0 and C1 characters, ESC
sequences, DEL and the bidirectional controls. A tab or a line break becomes a space. The JSON form
writes them as escapes (`\u001B`), so a reader such as `jq` still gets the same text.

Notes go to stderr, one line each with a time:

- `{"dropped": N}` from the service: it dropped N records, because this side read too slowly. The
  note also gives the total since the command started.
- `{"error": …}` before the first record is the service's answer to the filters (it sends no other
  answer): the command stops with exit code 2 and the service's reason. After a record, an error is
  only a note.
- A dropped socket or a 5xx is followed by a reconnect, waiting 1, 2, 4 … up to 30 seconds, and the
  filters are sent again. The note says that records in the gap are missed: the stream has no
  replay. An access token about to expire is refreshed before each connection.
- The command pings every 20 seconds, and the service every 30. A socket that sends nothing (no
  frame, ping or pong) for 60 seconds counts as dropped.
- A 401 (`The platform refused the token`), a 403 (`Your roles do not allow this`) or any other 4xx
  on the upgrade stops the command with exit code 1.
- SIGINT (Ctrl-C) or SIGTERM stops it with exit code 0. So does a closed stdout, at the next record.

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
