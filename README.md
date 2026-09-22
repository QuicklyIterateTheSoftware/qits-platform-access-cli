# qits-platform-access-cli

The `qits` command: access to the qits platform from a workstation, with defaults that need no
setup. A static native binary for Linux and WSL.

Commands:

- `qits login` signs you in through the browser and stores the session.
- `qits session-daemon` keeps that session fresh for as long as it runs.
- `qits projects list`, `qits repositories … list`, `qits ticket … list|new|details` and
  `qits release-request … list|create|join|withdraw` read from and ask the projects service.
- `qits ci runs|run|retry` lists a repository's CI runs, shows a run with its steps and their logs,
  and runs a finished run again.
- `qits events` prints the platform's domain events as they happen.
- `qits observe` prints what qits-observability takes in (logs, spans, metrics) as it arrives,
  filtered by the service.
- `qits git-login` signs this workstation in for Git pushes to the platform's git host, and
  `qits git-credential` is the Git credential helper that uses that sign-in — and, inside the
  platform, the container's own credential instead.
- `qits artifacts publish` publishes a release artifact to qits-artifacts from a CI step: an sbom,
  a docs bundle, a daemon binary, or an npm decision. It runs with no person signed in — see below.
- `qits tui` opens an interactive screen over all of the above: pick a command instead of
  remembering it, and see it run in the lower half — see below.

The binary is called `qits`. qits-bootstrap-cli's binary is `qits-bootstrap`. Started under the
name `qits-publish`, it behaves as `qits artifacts publish` — see below.

## Install

`install.sh`, at the root of this repository, puts `qits` on a stable path and points Git at it.
There is no public download of the script on its own: reading a raw file from the platform's git
host needs a credential, same as any other read, so get it from a checkout of this repository (one
you already have, or `git clone` the wrapper that holds it) and run it from there:

    ./install.sh

It does four things:

1. Signs you in through the idp — the same browser-and-pasted-code exchange as `qits login` — and
   holds the access token in a shell variable only. It is never written to disk or printed.
2. Finds the latest released version in qits-artifacts and downloads it with that token.
3. Installs it as `qits` in the install directory (`~/.local/bin` by default), replacing any file
   there of the same name, and checks that it runs.
4. Sets Git's credential helper for the platform's git host to the installed binary, the way
   `qits git-login --configure` does (see below): it replaces any earlier helper for that host,
   including one pointing at a build output such as `platform-access-cli/target/qits`, and leaves
   every other host's helper (GitHub's, for example) alone.

Environment overrides:

- `QITS_IDP_URL` — the idp's base URL. Default `https://idp.dev.wohlben.eu/idp`.
- `QITS_ARTIFACTS_URL` — the artifacts store's base URL. Default: derived from the idp URL, the
  same way `qits` derives every other service's address (swap the idp host's first label).
- `QITS_GIT_HOST_URL` — the git host's base URL. Default: derived the same way.
- `QITS_INSTALL_DIR` — where to install. Default `~/.local/bin`.

If the install directory is not on `PATH`, the script says so and prints the line to add:

    echo 'export PATH="$PATH:$HOME/.local/bin"' >> ~/.bashrc

(zsh users: the same line in `~/.zshrc`). Open a new shell afterwards.

It needs bash, curl, and one of `jq` or `python3` to read JSON answers (`jq` is used when present).

`scripts/test-install.sh` runs the script offline against stub servers standing in for the idp and
qits-artifacts, and checks the binary lands, the PATH hint appears, Git is set up correctly, and the
token never appears in what the script prints. Run it with `./scripts/test-install.sh`.

## Download

Each release publishes the static binary to the platform's artifacts store, under this repository's
name and the released version, the way qits-ci-daemon is published:

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

    sdk env && ./mvnw package -Dnative -DskipTests   the binary for this host:
                                                     platform-access-cli/target/qits
    ./mvnw clean verify                              the tests; packages only the pin jar
    docker build --target binary --output type=local,dest=out -f docker/Dockerfile .
                                                     the released form, static: out/qits

Run all three from the repository root: it is a two-module Maven reactor.

    platform-access-cli         the program — everything described above, and the native binary.
                                It builds no jar and is deployed to no Maven repository: what it
                                produces is a file, published to the artifacts store as bytes.
    platform-access-cli-binary  one small jar whose own version *is* the version of the binary the
                                same release published. It carries no bytes of the binary, only its
                                name, the command name and that version. This is the only artifact
                                this repository deploys to a Maven repository, and qits-ci is what
                                depends on it: its release steps run `qits`, and depending on a
                                coordinate is how its pom decides *which* `qits` instead of taking
                                whatever the store had latest when a step started. See
                                `PlatformAccessCliBinary`.

`.sdkmanrc` names the GraalVM (25.0.2-graalce), so `sdk env` sets `JAVA_HOME`. Without sdkman:
`JAVA_HOME=$HOME/.sdkman/candidates/java/25.0.2-graalce ./mvnw package -Dnative -DskipTests`.
Run `clean verify` before a native build, not after: `clean` removes the binary.

The tests need no docker and no platform. Copy `platform-access-cli/target/qits` to a directory on
your `PATH`, for example `~/.local/bin`.

The host build is glibc-linked. The released binary is static (musl), so it also runs on alpine.
`docker/Dockerfile` builds it on a musl toolchain it builds first, as a stage: a copy of
qits-ci-daemon's `docker/Dockerfile.musl-builder`. Off the platform, pass the upstream tarballs and
base image, or `--build-arg BUILDER_IMAGE=` a toolchain image built from that file; the Dockerfile's
header gives both commands. The build fails unless `ldd` finds the binary static;
`file out/qits` says `statically linked`. A second target, `--target sbom`, exports the release's
CycloneDX document from the same build.

## Releases

Only through a release request, like every repository here. `.config/qits/` holds the two recipes,
shaped like the retired qits-artifacts-cli's were:

- `ci-event-release-request.yml` gates a request's fold: `./mvnw verify`, then the static binary on
  the platform's BuildKit, and a `--help` run of it on an alpine image.
- `ci-event-release.yml` runs on the release tag: the same build and its SBOM, then a PUT of the
  binary to `/artifacts/daemons/qits-platform-access-cli/<version>` and of the SBOM to
  `/artifacts/sboms/daemon/qits-platform-access-cli/-/<version>`. A version that exists already
  (HTTP 409) fails the release: a version is never published twice. A second step, on a maven image
  and deliberately after the binary is published, deploys the pin jar
  `eu.wohlben.qits:qits-platform-access-cli-binary` under the same version — a jar resolvable before
  the binary it names would be a pin pointing at nothing. It re-runs as a no-op: a deploy of the
  same bytes is skipped when both the jar's pom and its parent's are already there.

  The file declares both artifacts — `{type: daemon, name: qits-platform-access-cli}` and
  `{type: maven, name: eu.wohlben.qits:qits-platform-access-cli-binary}` — so qits-ci announces one
  release for each.

The recipes need no toolchain image in the registry: `docker/Dockerfile` builds the musl toolchain
as a stage, from two tarballs in the platform's Maven store, which the bootstrap seeds.

## Using qits from an agent

`qits help skill` prints the help of every command as a SKILL.md: when to use qits, the platform
rules, and each command with its options, examples and exit codes. It is hidden from `qits --help`.
To give it to Claude Code:

    mkdir -p ~/.claude/skills/qits && qits help skill > ~/.claude/skills/qits/SKILL.md

The repository holds the same file as `SKILL.md`. The help texts are its one source: a test fails
when the file differs from them, and `./mvnw test -Dtest=SkillDocumentTest -Dqits.skill.update=true`
writes it again.

## qits tui

    qits tui

An interactive screen over every command above. The upper half is the picker — the commands, then
the options of the one you chose, required first and marked `*`; the line between the halves is the
command your choices have built, so the screen also teaches the command line; the lower half is what
the last run printed.

     qits tui · dev.wohlben.eu · signed in as jan
     ┌ ci ▸ runs ──────────────────────────────────────────────────────────────────┐
     │ * --project        qits                                                     │
     │ * --repository     ▸ qits-ci-service                                        │
     │   --status         (any)            RUNNING FAILED SUCCESS                  │
     │   --limit          20                                                       │
     │ ↑↓ move · ⏎ choose · ␛ back · / search · ⌃R run · q quit                     │
     └─────────────────────────────────────────────────────────────────────────────┘
     $ qits ci runs --project qits --repository qits-ci-service
     ┌ output ─────────────────────────────────────────────────────────── exit 0 ──┐
     │ ID        STATUS   BRANCH   COMMIT    REQUEST   CREATED                     │
     │ 473131bb  SUCCESS  main     767cd203  -         2026-09-13 19:50:50 GMT     │
     └─────────────────────────────────────────────────────────────────────────────┘

| key | does |
|---|---|
| `↑` `↓` (also `k` `j`) | move in the list |
| `⏎` | drill into a command, or edit the selected option |
| `␛` / `←` | back one segment |
| `/` | filter the list by typing; `␛` clears it |
| `⌃R` | run the command as shown |
| `⌃C` | stop a running command; it does not quit the screen |
| `⌃L` | in an open dropdown: forget what the platform said and ask again |
| `⌃P` | history: the commands run this session; `⏎` re-runs one, `e` puts it back in the picker |
| `q` | quit |

`--project`, `--repository`, `--release-request`, `--ticket` and a run's id are lists the platform
fills in: pick a project and the repository list is that project's. An option with nothing to offer
is a field to type into, and so is one whose source could not answer — the screen says why and stays
usable. Nothing it fetches is written to disk, and a value typed into a hidden field is never
echoed, never remembered and never put on the child's command line.

It needs an interactive terminal of at least 80×24; in a pipe or a CI step it says so and exits 2.
A command is run by starting this same binary again, so a run behaves exactly as it does when typed.

The screen holds no knowledge of any command: it reads picocli's own model, the same one that makes
`--help` and `SKILL.md`. A command says the little that model cannot with
`@TuiCommand(interaction, output)` — whether it streams, opens a browser, or only means anything in
a CI step — and an option says where its values come from with `@Completes(SomeSource.class)`. A
command that says neither still appears and still runs.

## qits from inside the platform

The same binary, in a workspace container on the platform, is a second home and not a second
program. There is no browser and nobody to sign in; there is the commissioned client the container
was injected with (`QITS_COMMISSIONED_CLIENT_ID` / `QITS_COMMISSIONED_CLIENT_SECRET`). Both set is
the signal, decided once at startup:

- the credential is minted at the internal idp with `client_credentials`, **once per process** — the
  answer's `aud` claim carries every audience the client holds — and kept in memory only. It is
  never written to `t.json` and never under the agent's config folder;
- the public vhosts do not resolve inside, so a service is dialled by its wire alias:
  `http://dev-qits-projects:8080` for a service on an environment's plane, and its own alias for one
  on the platform plane — `http://qits-platform-idp:8080` for the idp, `http://qits-events:8080` for
  events. Those aliases are not one pattern, so the CLI holds the two it dials by name rather than
  spelling them. The tier in an environment name comes from `QITS_ENV`, else from the host of whichever
  platform URL the container carries — `QITS_WORKSPACE_DAEMON_URL` in a workspace,
  `QITS_PROJECTS_DAEMON_URL` in a project-agent container, `QITS_REPOSITORY_MCP_URL` in both.
  `QITS_URL_<APP>` overrides any of them;
- the credential is `qits:agent`. Every read door answers and an operator write answers `403`, which
  is correct behaviour and is worded as such;
- **nothing is refused before the call.** The audience a command dials is not this credential's to
  judge: every qits service accepts `qits-platform` beside its own name
  (`quarkus.oidc.token.audience=${qits.auth.machine.audience},qits-platform`, in all fourteen
  services that carry the setting), and the one token this credential mints carries exactly that.
  A service that really does refuse the token answers `401`, and only then does the CLI add a
  sentence of its own, naming the audience it asked the idp for.

This used to work the other way round, and it was wrong. The CLI read the minted token's `aud`
claim and refused any service the claim did not name — a false negative, because the claim names
each service's own audience and not the fleet-wide one every service also accepts. It cost
`qits observe` its entire reason for existing in a container: qits-observability grants `qits:agent`
read access **on purpose** (its `AgentReadAccessTest` asserts that an agent reads the telemetry API
and opens the live stream), and the CLI was the only thing saying no. Measured from a workspace
container on 2026-09-14, with the very bearer the CLI would not send:

    $ curl -H "Authorization: Bearer $(qits-token qits-platform)" \
        http://dev-qits-observability:8080/observability/api/telemetry/sources
    200
    $ ... /observability/api/telemetry/errors?limit=3
    200  {"groups":[],"total":0,…}

A workstation is untouched by any of this: without the pair, nothing ever mints with a client
secret and the session file is the only credential there is.

The container is also quiet. The workspace image sets `QUARKUS_ANALYTICS_DISABLED=true` for the
Maven builds the agent runs there, and this binary is a Quarkus application as well, so that
variable used to make it print a configuration warning on stdout ahead of every answer — corrupt
data for `qits ticket list | head` and for anything else that reads the output. The build now
records the same value, the two agree, and nothing but the command's own answer is printed.

### The smoke run

Repeat it from any workspace container. Recorded on dev, 2026-09-13, from the released binary
(`2026.913.220350`) inside a workspace container:

    $ qits ci runs --project qits --repository qits-platform-access-cli --limit 3
    ID        STATUS   BRANCH                                        COMMIT    REQUEST   CREATED                  TOOK
    473131bb  SUCCESS  2026.913.195048                               767cd203  -         2026-09-13 19:50:50 GMT  2m40s
    f4f1ec8d  SUCCESS  release/33f56c22-24f8-42b9-9305-1d0d73d3a3b5  d47a2770  33f56c22  2026-09-13 19:46:08 GMT  3m52s
    b6824390  SUCCESS  2026.913.185314                               109ec668  -         2026-09-13 18:53:16 GMT  2m42s
    # exit 0 — no `qits login`, and no session file anywhere

    $ qits ci retry 00000000-0000-0000-0000-000000000000
    403 - this credential is qits:agent, which reads but does not write
    Your roles do not allow this (HTTP 403): POST http://dev-qits-ci:8080/ci/api/runs/00000000-0000-0000-0000-000000000000/retry
    # exit 1 — a write door, answered as what it is

    $ qits observe --filter=service=qits-ci
    # the stream opens and prints telemetry until stopped
    # the run recorded here on 2026-09-13 showed this exiting 2 with "the workspace credential is
    # not granted dev-qits-observability". That was the CLI's own false negative, not the
    # platform's answer: the service takes this credential and always did (see above).

    $ qits login
    no browser in the platform - the workspace credential is already in use
    # exit 2 — unchanged on a workstation

    $ qits tui < /dev/null
    qits tui needs an interactive terminal. (Unable to create a terminal)
    # exit 2 — it says so rather than waiting for a key nobody can press

On a container's own terminal the same command paints, and its header says which home it is in:

    qits tui · in platform · dev · agent (qits:agent)

with `login` and `git-login` dim and sorted last, the way a CI-only command already is.

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
    qits ticket --project <project> list [--status <STATUS>] [--type <TYPE>]
    qits ticket --project <project> new --title <text> --type <TYPE> \
        [--description <text> | --description-file <path|->] [--assignee <name>]
    qits ticket --project <project> details --ticket <id, slug or start of the id>
    qits ticket --project <project> transition --ticket <id, slug or start of the id> --target <STATUS>
    qits release-request --project <project> --repository <repository> list [--state <STATE|all>]
    qits release-request --project <project> --repository <repository> create \
        --branch <branch> --summary <text> [--priority <priority>]
    qits release-request --project <project> --repository <repository> join \
        --request <id> --branch <branch> [--priority <priority>]
    qits release-request --project <project> --repository <repository> withdraw \
        --request <id> [--reason <text>]
    qits ci runs --project <project> --repository <repository> [--branch <branch>] \
        [--status <STATUS>] [--release-request <id>] [--limit <n>]
    qits ci run <run id> [--logs] [--project <project> --repository <repository>]
    qits ci retry <run id> [--project <project> --repository <repository>]
    qits events [--filter=<names>]
    qits observe --filter <conditions> [--filter <conditions> …] [-o json]
    qits checkout-daemon [--path <dir>] [--repository <name>] [--once] [--no-submodules]

They call the platform through its edge over HTTPS, with the access token from `qits login` as a
bearer. The options of `projects`, `repositories`, `ticket` and `release-request` may come before or
after the subcommand: `qits repositories --project qits list` and `qits repositories list --project qits`
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

`qits checkout-daemon` reads the events service the same way, with `--events-url` or
`QITS_EVENTS_URL`; the git host it fetches from is the checkout's own `origin`, never a flag.

Inside a container none of those vhosts resolve and the wire aliases answer instead, on two planes:
a service on an environment's plane is `http://<env>-qits-<app>:8080` (projects, ci, observability,
the git host), and one on the platform plane has its own alias — `http://qits-platform-idp:8080`
and `http://qits-events:8080`. The flags and variables above still come first in either home (see
*qits from inside the platform*).

### Output, errors and exit codes

`--output table` (the default) prints aligned columns. `--output json` (or `-o json`) prints the
service's answer, pretty-printed. The default `release-request list` leaves out the finalized
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

### qits ticket

A ticket is a small piece of work in a project: a `BUG` (something behaves other than it should) or
an `IMPROVEMENT` (something works and could work better). Its status walks five phases: `REPORTED`
(somebody said what is wrong or could be better, and nothing more), `REFINED` (it says what to do,
and is ready to be picked up), `IMPLEMENTED` (released and deployed, not merely merged), `VERIFIED`
(somebody checked the platform and it no longer occurs) and `DONE` (closed, a person's call).
`DROPPED` is the exit for work a decision was taken not to do. A ticket is also blocked or not:
blocked says the phase its status belongs to cannot proceed, and any transition clears it. Work that
needs a plan is an epic, not a ticket. `--project` is as above. Reading tickets needs the role
`qits:admin` or `qits:agent`; filing one, commenting and moving one to another status need
`qits:admin`.

`list` shows the project's tickets, oldest first. Columns: id (the first 8 characters), type,
status, title, the assignee when a ticket has one, and a `BLOCKED` column when one is blocked.
`--status REFINED` (or any of the six) goes to the service, which refuses a status it does not
know (HTTP 400, exit code 1) rather than answer with no tickets. The service has no type filter, so `--type BUG` (or `IMPROVEMENT`) is applied here, in both
output forms.

`new` files a ticket; it starts `REPORTED`. `--title` and `--type` are required: the platform takes
no ticket that is neither a bug nor an improvement. The description is Markdown, from `--description`
or from a file with `--description-file` (`-` is stdin), not both; trailing blank lines are left
out. `--assignee` names who takes it. The service stamps the reporter from your token. The command
prints the new ticket the way `details` does.

`details` shows one ticket: id, slug, type, status, whether it is blocked, title, assignee, who
created it, the times, the live workspaces on it, its description and its comments, the oldest
first. `--ticket` is the ticket's
id, its slug, or the start of its id; the command looks among the project's tickets, and a value that
fits none, or more than one, stops with exit code 2. `--ticket` may also come before `details`.
`-o json` prints `{"ticket": …, "comments": […]}`.

`transition` moves a ticket to another status. `--ticket` is as for `details`, and `--target` is any
of the six statuses. One verb takes every move: which moves are allowed from where is the service's
to say, and it refuses the rest, the ticket's own status included, with HTTP 409 and a sentence
naming both ends (exit code 1). A status it does not know is an HTTP 400. Any transition clears the
blocked flag. The command prints the ticket the way `details` does, without its comments.

A ticket's text is written by people and agents, so, as for `qits ci`, the table form takes terminal
control characters out of every value, and `-o json` writes them as escapes.

    qits ticket --project qits list --status REFINED --type BUG
    qits ticket --project qits new --type BUG --title "The log view stops at 64 KiB" \
        --description-file report.md
    qits ticket --project qits details --ticket 4f2a91c0
    qits ticket --project qits transition --ticket 4f2a91c0 --target DROPPED

Editing a ticket's title or description is not in `qits` yet.

### qits release-request

`--project` as above. `--repository` is the repository's id or name within that project.

A request folds main and its branches into one commit, and the builds of that commit are its gate.
States: PENDING (waiting for its builds), READY, RELEASED (the tag is cut, waiting on its remaining
gates), FINALIZED (the tag is merged into main, done), REJECTED (a gating build was red), FAILED
(the release itself failed), CONFLICTED (the branches do not merge), WITHDRAWN, OBSOLETE (a later
request for the repository superseded this one before it finished). RELEASED is open, not final:
the request still waits on its remaining gates, one of which (PUBLISH) is the tag's own release
pipeline.

`list` shows the open requests: every state but FINALIZED, WITHDRAWN and OBSOLETE. The service's
default answer holds the open requests and the last 10 finalized ones; the command drops the
finalized. `--state all` shows every request, and `--state PENDING` (or READY, RELEASED, FINALIZED,
REJECTED, FAILED, CONFLICTED, WITHDRAWN, OBSOLETE) shows one state. Columns: id (the first 8
characters), state, priority, summary, version, updated.

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
- A RELEASED, FINALIZED, WITHDRAWN or OBSOLETE request takes no more branches (HTTP 409, exit code
  1). Open a new one with `create`.
- HTTP 404 means the platform has no such request (exit code 1). A branch the git host does not
  have is not a 404: the fold fails, and the request's detail says why.

`withdraw` withdraws an open request, so it does not ship. `--request` is found as for `join`.
`--reason` is a sentence the request then shows as its detail; without it, the platform writes who
withdrew it. WITHDRAWN is final: the request is not built or released again, and the next `create`
for one of its branches opens a new request. The command prints the request that came back, like
`create`.

- A RELEASED, FINALIZED, WITHDRAWN or OBSOLETE request cannot be withdrawn (HTTP 409, exit code 1).
- HTTP 404 means the platform has no such request (exit code 1).

    qits release-request --project qits --repository qits-ci-service list
    qits release-request --project qits --repository qits-ci-service list --state all -o json
    qits release-request --project qits --repository qits-ci-service create \
        --branch feature/log-view --summary "Show the build log live" --priority HIGH
    qits release-request --project qits --repository qits-ci-service join \
        --request 4f2a91c0 --branch feature/log-search
    qits release-request --project qits --repository qits-ci-service withdraw \
        --request 4f2a91c0 --reason "The log view moves to qits-observability"

A red gating build that was the platform's fault and not the code's (a flaked container, a registry
that was down) is retried with `qits ci retry`, not with a new request and not with `withdraw`.
`withdraw` is only for a request that must not ship.

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

### qits checkout-daemon

    qits checkout-daemon [--path <dir>] [--repository <name>] [--once] [--[no-]submodules] \
        [--events-url <url>]

Holds a local checkout at what the repository released, and keeps it there. **It follows the
releases, not the tips of the branches**: the root ends detached at the release tag, and every
submodule detached at the gitlink that release recorded. That tree is the estate somebody reviewed
and released, which is not the same thing as `latest` — a wrapper's branch tips are whatever each
component pushed since.

    qits checkout-daemon --path /workspace
    qits checkout-daemon --path /workspace --once
    qits checkout-daemon --path /srv/qits --repository qits-qits --no-submodules

At the start, and again after every connect, it reads the newest `SCMRelease` of the repository
from the events service and brings the checkout to it; in between it waits for `SCMRelease` on the
live stream. The stream has no replay, so the reconcile after a connect is what closes the gap a
reconnect leaves — and the version last acted on is remembered, so the frame that follows a
reconcile does not run Git twice.

Which repository it follows comes from the checkout's `origin`. An origin of the form
`https://<git host>/git/<project id>/<repository>` names it, and the project id narrows the events
it matches. `http://<git host>/git/<repository id>` does not: that is the git host's internal
storage scheme, and the command refuses with exit code 2 until `--repository <name>` says which
repository this is.

What it runs is what a person would run:

    git -C <path> fetch <origin> refs/tags/<version>:refs/tags/<version>
    git -C <path> checkout --detach <version>
    git -C <path> -c submodule.<name>.url=<the origin's parent>/<name> \
        submodule update --init --checkout -- <path of the submodule>

`--checkout` is the flag that matters. Every entry of the wrapper sets `update = merge`, and
`submodule update --init` copies that into the checkout: without the flag Git merges the recorded
commit into whatever branch the submodule sits on and leaves it there, at that branch's tip rather
than at the commit the release recorded. `--no-submodules` holds the root alone. A gitlink
`.gitmodules` declares but the released tree does not carry is skipped with a note, not a failure.

- **A checkout with local changes is never touched.** Before anything it runs
  `git status --porcelain --ignore-submodules=none`; anything at all there and it says so, names
  the path, and leaves the checkout as it is. It never stashes, resets or merges away somebody's
  work. With `--once` that is exit code 1; while watching it keeps watching, and the next release
  tries again.
- **Git authentication stays the credential helper's.** No token is put in a URL or an
  `http.extraHeader`: the helper is pinned to one host on purpose, and a machine credential handed
  to a submodule remote somebody else authored is a way out for it. At the start it checks there is
  one — `qits git-login` on a workstation, the injected `QITS_GIT_AUTH_HOST` in a container — and
  refuses with exit code 2 rather than hanging on a password prompt at the first release. An origin
  that is a local path has no host and needs none.
- Every note is one line on stderr with a time. Every release the checkout is moved to is one line
  on stdout, so `qits checkout-daemon --path . | while read v; do ...; done` works.
- A dropped connection or a 5xx is followed by a reconnect, waiting 1, 2, 4 … up to 30 seconds; a
  connection silent for 60 seconds counts as dropped. A 4xx stops the command with exit code 1, and
  SIGINT or SIGTERM with 0.

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

On a workstation Git asks `qits git-credential` for the git host and for no other host, so a global
helper (for example Git Credential Manager for GitHub) stays as it is. The empty value first clears
the helper list for this host:

    git config --global --replace-all credential.https://githost.dev.wohlben.eu.helper ''
    git config --global --add credential.https://githost.dev.wohlben.eu.helper '!/home/you/.local/bin/qits git-credential'

`qits git-login` prints these two lines with the git host and its own path. `--configure` runs them.
Running them again changes nothing.

Inside the platform the setup is not per host: the workspace image points a *global*
`credential.helper` at `qits git-credential`, so Git runs it for every http remote a checked-out
repository names, submodule remotes included. Which host it will answer is settled by the
environment instead — see below.

### qits git-credential

Git sends the token as HTTP Basic `oauth2:<access token>`; the edge checks it and forwards it to the
git host. The command has the CLI's two homes.

On a workstation, the sign-in of `qits git-login` in `git.json` is what answers:

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

Inside the platform there is no sign-in and none is needed: `qits git-login` wants a browser and is
refused there, so `git.json` never exists. The container's own commissioned credential answers
instead, minted at the internal idp on demand:

- `get`: for the injected git host, prints `username=oauth2` and the container's bearer as
  `password`. The host is `QITS_GIT_AUTH_HOST` (a bare authority, `githost.dev.internal:8080`), and
  it is compared as a normalised origin, so an explicit default port on either side still matches.
- Any other host gets no answer, and nothing is minted for it: the helper is global, and the
  platform's bearer is not handed to a host someone else wrote into a remote. A missing or
  unreadable `QITS_GIT_AUTH_HOST` therefore answers nothing at all, rather than answering
  everything.
- `store` and `erase` do nothing, and nothing is written: neither `git.json` nor `git.json.lock` is
  created. The agent's config folder is becoming a git repository, and a machine credential
  committed to a branch is a leak with a history.
- An idp that cannot be reached or refuses the client says why on stderr and still exits `0`, so
  Git carries on with its other helpers.

`git-credential get` is the one place `qits` prints a token, because that is how Git's helper
protocol works. stderr never carries one.

## qits artifacts publish

`qits artifacts` is the platform's artifacts store, qits-artifacts. `qits artifacts publish` is its
publish client: what a CI release step calls to put an sbom, a docs bundle, a daemon binary, or an
npm decision where it belongs. It is `qits-publish`, the client of the retired qits-artifacts-cli,
folded into `qits`.

**It never signs in, and it never touches the session `qits login` keeps.** It runs inside a CI step
container, with no person, on a bare alpine image (the binary is static). `qits login`,
`qits session-daemon` and `qits git-login` do not apply to it, and it reads none of their files.

Commands:

    qits artifacts publish sbom submit --type <npm|maven|docker|daemon> --name <n> --version <v> --file <path>
    qits artifacts publish sbom from-dockerfile --root-name <n> --root-version <v> \
                      [--dockerfile <path>]... [--build-arg NAME=value]... -o <out.json>
    qits artifacts publish docs submit --site <name> --version <v> --archive <tgz> [--meta key=value]...
    qits artifacts publish daemon submit --name <n> --version <v> --file <bin>
    qits artifacts publish exists <daemon|docs|npm|sbom> <name> <version>
    qits artifacts publish npm plan --package <n> --version <v>
    qits artifacts publish npm dist-tag --package <n> --version <v> --tag <t>
    qits artifacts publish npm rewrite-lockfile-origin [--lockfile package-lock.json]

`qits artifacts publish <command> --help` shows a command's options, examples and exit codes;
`qits help skill` includes them all.

**Environment**: `QITS_ARTIFACTS_URL` (the store's origin; required by everything that talks to it),
`QITS_DOCS_URL` (the docs root, including its `docs` repository segment; derived from the origin
when unset), `QITS_NPM_REGISTRY_URL` (the hosted npm registry, the `@qits` scope) and
`QITS_NPM_PROXY_URL` (the npmjs pull-through cache). A CI step sets what each command needs.

**Credentials**: a bearer on every request. Only a CI run may publish to qits-artifacts — the store
refuses an anonymous publish — and its reads want a bearer too, so every PUT, GET and HEAD this
client makes carries an `Authorization` header. The token comes from the first of these the
environment has:

1. `QITS_PUBLISH_TOKEN_COMMAND` — an executable that prints a fresh token on stdout. Preferred, and
   run again for **every request** rather than once per process: a release step can publish an hour
   after it started, and a token minted at the top of the step would be expired by then. qits-ci
   writes `/tmp/qits-publish-token` and points this at it.
2. `QITS_PUBLISH_TOKEN` — a token, used verbatim.
3. `QITS_COMMISSIONED_CLIENT_ID` and `QITS_COMMISSIONED_CLIENT_SECRET` — the commissioned client pair
   a step container carries, exchanged at the internal idp for the `qits-platform` audience by the
   same minter every other `qits` command uses.

With none of them the request is still **sent**, without the header, and the store answers `401`.
That is deliberate: the store is the authority on who may write, and refusing here would replace its
refusal — which names the real reason — with a client-side error naming a variable. No token is ever
printed, to stdout, to stderr or into an error message.

**The one idempotency policy**, unchanged from `qits-publish` and identical across every surface:
absent, PUT it and say what landed; occupied with the same bytes, say so and succeed (a retried or
replayed step must go green); occupied with different bytes, fail naming both digests (a coordinate
must never come to mean two things); occupied and not comparable (docs: the store keeps no archive
digest), warn and skip.

**Exit codes**: `0` published, or already published with the same bytes. `1` refused, and re-running
will not help: invalid arguments, a 4xx, or an occupied coordinate holding different bytes (`exists`
also uses it for "absent"). `2` could not ask, or could not be answered: no store configured, an I/O
failure, or a 5xx. A step may retry a `2` and must not retry a `1`.

### Started as `qits-publish`

A binary or a symlink started under the name `qits-publish` — its own name, before it folded into
`qits` — behaves exactly as `qits artifacts publish`: `qits-publish sbom submit ...` is
`qits artifacts publish sbom submit ...`, argument for argument. `qits artifacts publish`'s own
release still publishes the binary as `qits-platform-access-cli`; a step that still says
`qits-publish` needs only that name on its `PATH`, as a copy or a symlink of `qits`. New pipelines
should call `qits artifacts publish` directly.
