---
name: qits
description: "Use for any work on the qits platform from a terminal: signing in, projects and repositories, tickets, release requests, CI runs and their logs, domain events, live telemetry, and Git pushes to the platform's git host."
---

# qits

Each command calls the platform through its edge, with the session of `qits login`. `qits <command> --help` shows a command's options, examples and exit codes.

## Platform rules

- Sign in once with `qits login`, and keep `qits session-daemon` running so the session stays fresh.
- Release only through a release request: `qits release-request create`, or `join` to add a branch to an open one. A push releases nothing.
- Push only branches under refs/heads/external/, after `qits git-login`. Git gets the token from `qits git-credential`.
- qits never prints a token. The one exception is `qits git-credential get`, which Git runs.
- A command that says `Not signed in` or `Session ended` exits with 2: run `qits login`.

## Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits login

Sign in through the browser and store the session. Run it first, and again when a command says `Not signed in` or `Session ended`.

The session goes to $XDG_CONFIG_HOME/qits/t.json (default ~/.config/qits/t.json). The page shows a code: paste it at `Paste the code:`. The codes of one sign-in last 5 minutes.

```
qits login [--idp-url <url>] [--no-browser]
```

| Name | What it does |
|---|---|
| `--idp-url <url>` | The idp's public base URL. Default: QITS_IDP_URL, else https://idp.<QITS_ENV_NAME>.<QITS_DOMAIN>/idp, else (no QITS_DOMAIN) the platform on this machine, http://idp.<QITS_ENV_NAME or prod>.localhost:8080/idp. |
| `--no-browser` | Only print the sign-in address; do not start a browser. |

### Examples

```
qits login
qits login --idp-url https://idp.dev.wohlben.eu/idp
qits login --no-browser
```

Over SSH, use --no-browser and open the printed address on a machine with a browser.

### Exit codes

- `0` Signed in; the session is stored.
- `1` The sign-in did not complete.
- `2` The idp address cannot be worked out (see --idp-url), or the command was used wrongly.

## qits session-daemon

Keep the session from `qits login` fresh for as long as this runs. Start it once per workstation and leave it running.

It refreshes the access token shortly before it expires and writes the new pair to $XDG_CONFIG_HOME/qits/t.json. It logs one line per event to stderr, and stops on SIGTERM or SIGINT.

```
qits session-daemon [--margin <seconds>]
```

| Name | What it does |
|---|---|
| `--margin <seconds>` | Refresh this many seconds before the access token expires, 0 to 300. Default: 30. |

### Examples

```
qits session-daemon &
systemctl --user enable --now qits-session-daemon
```

- The systemd unit is in the README. Only one daemon runs at a time; a second one exits with 1.
- When the session ends (revoked or expired), it says so, keeps running, and carries on after the next `qits login`.
- Without it, a command refreshes an access token that is about to expire before it calls the platform.

### Exit codes

- `0` Stopped by SIGTERM or SIGINT.
- `1` Another qits session-daemon is running.
- `2` --margin is not between 0 and 300.

## qits projects

The platform's projects. Other commands take a project's id, slug or name as --project.

## qits projects list

List the projects: slug, name and id.

```
qits projects list [--output table|json] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.dev.wohlben.eu/idp gives https://projects.dev.wohlben.eu). |

### Examples

```
qits projects list
qits projects list -o json
```

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits repositories

The repositories of one project. Release requests take a repository's id or name as --repository.

## qits repositories list

List the project's repositories: name, archetype, component and id. --project may come before or after list.

```
qits repositories list [--output table|json] [--project <project>] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.dev.wohlben.eu/idp gives https://projects.dev.wohlben.eu). |

### Examples

```
qits repositories --project qits list
qits repositories list --project qits -o json
```

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits ticket

The tickets of one project: small pieces of work, each a bug or an improvement. list shows them, new files one, and details shows one with its description and comments.

Types: BUG (something behaves other than it should) and IMPROVEMENT (something works and could work better). Statuses: OPEN (a new ticket starts here) and RESOLVED.

### Notes

- --project, --output and --projects-url may come before or after the command. So may --ticket, which only `details` takes.
- Reading tickets needs the role qits:admin or qits:agent. Filing one needs qits:admin.
- The reporter is the signed-in caller. Nobody can file a ticket as somebody else.
- Work that needs a plan is an epic, not a ticket. qits does not resolve, edit or comment on a ticket yet.

## qits ticket list

List the project's tickets, oldest first: id, type, status, title, and the assignee when a ticket has one.

Without --status and --type it lists every ticket. The ID column shows the first 8 characters of the id, which is enough for `details`.

```
qits ticket list [--output table|json] [--project <project>] [--projects-url <url>] [--status <STATUS>] [--type <TYPE>]
```

| Name | What it does |
|---|---|
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.dev.wohlben.eu/idp gives https://projects.dev.wohlben.eu). |
| `--status <STATUS>` | Only the tickets in this status: OPEN or RESOLVED. Default: every status. |
| `--type <TYPE>` | Only the tickets of this type: BUG or IMPROVEMENT. Default: every type. |

### Examples

```
qits ticket --project qits list
qits ticket --project qits list --status OPEN --type BUG
qits ticket list --project qits -o json
```

- The service applies --status, and refuses a status it does not know (HTTP 400) rather than answer with no tickets. --type is applied here, in both output forms.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits ticket new

File a ticket in the project: a bug or an improvement.

The ticket starts OPEN, and you are its reporter. The command prints the new ticket the way `details` does.

```
qits ticket new --title <text> --type <TYPE> [--assignee <name>] [--description <text>] [--description-file <path>] [--output table|json] [--project <project>] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--title <text>` | Required. What is wrong or could be better, in a short line. |
| `--type <TYPE>` | Required. BUG or IMPROVEMENT. |
| `--assignee <name>` | Who takes it, as a name. Default: nobody. |
| `--description <text>` | The long form, in Markdown: what happens, what should happen, how to see it. |
| `--description-file <path>` | Read the description from this file (UTF-8), or from stdin for -. Not together with --description. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.dev.wohlben.eu/idp gives https://projects.dev.wohlben.eu). |

### Examples

```
qits ticket --project qits new --type BUG --title "The log view stops at 64 KiB"
qits ticket --project qits new --type IMPROVEMENT --title "Filter runs by author" --description "The runs list needs an author filter."
qits ticket new --project qits --type BUG --title "Login loops" --description-file report.md
cat report.md | qits ticket --project qits new --type BUG --title "Login loops" --description-file -
```

- --type is required: the platform takes no ticket that is neither a bug nor an improvement.
- The description is Markdown. --description-file - reads it from stdin.

### Exit codes

- `0` The ticket is filed.
- `1` The platform refused (for example a type it does not know, HTTP 400, or your roles, HTTP 403), or cannot be reached.
- `2` Used wrongly (for example an empty title, or a description file that cannot be read), not signed in, or the session ended.

## qits ticket details

Show one ticket: id, slug, type, status, title, assignee, who created it and when, its description, and its comments, the oldest first.

--ticket takes the ticket's id, its slug, or the start of its id (list shows 8 characters). Terminal control characters are taken out of the text.

```
qits ticket details [--output table|json] [--project <project>] [--projects-url <url>] [--ticket <ticket>]
```

| Name | What it does |
|---|---|
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.dev.wohlben.eu/idp gives https://projects.dev.wohlben.eu). |
| `--ticket <ticket>` | The ticket (required, before or after details): its id, its slug, or enough of the start of its id to name one. |

### Examples

```
qits ticket --project qits details --ticket 4f2a91c0
qits ticket details --ticket the-log-view-stops-at-64-kib --project qits
qits ticket --project qits details --ticket 4f2a91c0 -o json | jq -r .ticket.description
```

- -o json prints one object: the ticket as the service answers it, and its comments as a list. Control characters are written as escapes.

### Exit codes

- `0` Done.
- `1` The platform refused (for example the ticket was deleted a moment ago, HTTP 404), or cannot be reached.
- `2` Used wrongly (for example a --ticket that fits no ticket of the project, or more than one), not signed in, or the session ended.

## qits release-request

The release requests of one repository: the one way to release it. list shows them, create asks for a branch to be released, join adds a branch to an open request, and withdraw ends a request that must not ship.

A request folds main and its branches into one commit, and the builds of that commit are its gate. States: PENDING (waiting for its builds), READY, RELEASED, REJECTED (a gating build was red), FAILED (the release itself failed), CONFLICTED (the branches do not merge), WITHDRAWN.

### Notes

- A REJECTED or CONFLICTED request comes back by itself when one of its branches gets a new push. Fix the branch and push; do not open a new request.
- When a red build was the platform's fault and not the code's (a flaked container, a registry that was down), retry that run with `qits ci retry <run id>`: it builds the same commit again. `qits ci runs --release-request <id>` finds the request's runs. Do not open a new request, and do not withdraw this one.
- `withdraw` is only for a request that must not ship: the change is wrong, or nobody wants it any more. WITHDRAWN is final.
- --project and --repository may come before or after the command.

## qits release-request list

List the repository's open release requests.

Open means every state but RELEASED and WITHDRAWN. --state asks for other ones. The ID column shows the first 8 characters of the id, which is enough for `join`.

```
qits release-request list [--output table|json] [--project <project>] [--projects-url <url>] [--repository <repository>] [--state <STATE|all>]
```

| Name | What it does |
|---|---|
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.dev.wohlben.eu/idp gives https://projects.dev.wohlben.eu). |
| `--repository <repository>` | The repository: its id or name. |
| `--state <STATE\|all>` | Only this state (PENDING, READY, RELEASED, REJECTED, FAILED, CONFLICTED, WITHDRAWN), or all for every request. Default: the open ones. |

### Examples

```
qits release-request --project qits --repository qits-ci-service list
qits release-request --project qits --repository qits-ci-service list --state all -o json
```

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits release-request create

Ask for a branch to be released once its builds are green.

The platform may answer with a new request, the open request that already holds the branch, or (on a project wrapper) the open request the branch joined. It prints what came back.

```
qits release-request create --branch <branch> --summary <text> [--output table|json] [--priority <priority>] [--project <project>] [--projects-url <url>] [--repository <repository>]
```

| Name | What it does |
|---|---|
| `--branch <branch>` | Required. The branch to release. |
| `--summary <text>` | Required. What the release is for, in a sentence. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--priority <priority>` | LOWEST, LOW, MEDIUM, HIGH, HIGHER or BLOCKING. Default: the platform's (MEDIUM). |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.dev.wohlben.eu/idp gives https://projects.dev.wohlben.eu). |
| `--repository <repository>` | The repository: its id or name. |

### Examples

```
qits release-request --project qits --repository qits-ci-service create --branch feature/log-view --summary "Show the build log live"
qits release-request --project qits --repository qits-ci-service create --branch main --summary "Release main" --priority HIGH
```

- Asking again for a branch that is on an open request answers that request; it opens no second one. Check the id that comes back.
- On a project wrapper the answer can be a request somebody else opened. Its summary stands, and a red gate holds every branch on it.
- To add another branch to a request you have, use `join`.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits release-request join

Add a branch to an open release request.

The platform folds the request again with the branch and, if that makes a new commit, builds that commit. It prints the request that came back.

```
qits release-request join --branch <branch> --request <id> [--output table|json] [--priority <priority>] [--project <project>] [--projects-url <url>] [--repository <repository>]
```

| Name | What it does |
|---|---|
| `--branch <branch>` | Required. The branch to add. |
| `--request <id>` | Required. The request: its id, or enough of its start to name one (list shows 8 characters). |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--priority <priority>` | LOWEST, LOW, MEDIUM, HIGH, HIGHER or BLOCKING. Default: MEDIUM for a new branch; a branch already on the request keeps its priority. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.dev.wohlben.eu/idp gives https://projects.dev.wohlben.eu). |
| `--repository <repository>` | The repository: its id or name. |

### Examples

```
qits release-request --project qits --repository qits-ci-service join --request 4f2a91c0 --branch feature/log-search
qits release-request --project qits --repository qits-ci-service join --request 4f2a91c0 --branch feature/log-search --priority BLOCKING
```

- Safe to repeat: a branch already on the request adds nothing. With --priority it states that priority again; without, the branch keeps its priority.
- A RELEASED or WITHDRAWN request takes no more branches (HTTP 409): open a new one with `create`.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly (for example a --request that fits no request, or more than one), not signed in, or the session ended.

## qits release-request withdraw

Withdraw an open release request, so it does not ship.

WITHDRAWN is final: the request is not built or released again, and its branches are free. The next `create` for one of them opens a new request. It prints the request that came back.

```
qits release-request withdraw --request <id> [--output table|json] [--project <project>] [--projects-url <url>] [--reason <text>] [--repository <repository>]
```

| Name | What it does |
|---|---|
| `--request <id>` | Required. The request: its id, or enough of its start to name one (list shows 8 characters). |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.dev.wohlben.eu/idp gives https://projects.dev.wohlben.eu). |
| `--reason <text>` | Why it must not ship, in a sentence. The request shows it as its detail. Default: the platform writes who withdrew it. |
| `--repository <repository>` | The repository: its id or name. |

### Examples

```
qits release-request --project qits --repository qits-ci-service withdraw --request 4f2a91c0
qits release-request --project qits --repository qits-ci-service withdraw --request 4f2a91c0 --reason "The log view moves to qits-observability"
```

- Only for a request that must not ship. A gating build that was red because of the platform, not the code, runs again with `qits ci retry <run id>`. A REJECTED or CONFLICTED request comes back by itself when one of its branches gets a new push.
- Without --reason the platform writes who withdrew it.
- A RELEASED or WITHDRAWN request cannot be withdrawn (HTTP 409).

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly (for example a --request that fits no request, or more than one), not signed in, or the session ended.

## qits ci

The builds of qits-ci: runs lists a repository's runs, run shows one run with its steps and their logs, and retry runs a finished run again.

A release request's gating runs build its backing branch release/<request id> and carry the request's id. Statuses: QUEUED and RUNNING (not finished), SUCCESS, FAILED (the code's verdict), and CANCELLED, TIMED_OUT, CONFIG_ERROR (the run's end, not a verdict on the code).

### Notes

- Reading runs needs the role qits:admin or qits:system. retry needs qits:admin.
- To follow a run, run `qits ci run <run id>` again. There is no live log stream. A build's verdict also comes as an event: `qits events --filter=BuildSuccessful,BuildFailed`.
- --project, --repository, --output and the two -url options may come before or after the command.

## qits ci runs

List a repository's CI runs, newest first.

Columns: the run's id (its first 8 characters, enough for `run` and `retry` with --project and --repository), status, branch, commit, the release request, when it was created, and how long it took (so far, while it runs).

```
qits ci runs [--branch <branch>] [--ci-url <url>] [--limit <n>] [--output table|json] [--project <project>] [--projects-url <url>] [--release-request <id>] [--repository <repository>] [--status <STATUS>]
```

| Name | What it does |
|---|---|
| `--branch <branch>` | Only the runs of this branch: main, release/<request id>, or a version for a release run. |
| `--ci-url <url>` | The ci service's base URL, without /ci. Default: QITS_CI_URL, else the session's idp address with `idp` swapped for `ci` (https://idp.dev.wohlben.eu/idp gives https://ci.dev.wohlben.eu). |
| `--limit <n>` | At most this many runs, the newest. Default: 20. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where --project and --repository are looked up. Default: QITS_PROJECTS_URL, else derived from the idp address like --ci-url. |
| `--release-request <id>` | Only the runs of this release request: its id, or the start of it. |
| `--repository <repository>` | The repository: its id or name. |
| `--status <STATUS>` | Only the runs in this status: QUEUED, RUNNING, SUCCESS, FAILED, CANCELLED, TIMED_OUT or CONFIG_ERROR. |

### Examples

```
qits ci runs --project qits --repository qits-ci-service
qits ci runs --project qits --repository qits-ci-service --status FAILED --limit 5
qits ci runs --project qits --repository qits-ci-service --release-request 4f2a91c0
qits ci runs --project qits --repository qits-ci-service --branch main -o json
```

- A release request's gating runs: --release-request with the request's id, or the 8 characters `qits release-request list` shows. They build the branch release/<request id>.
- The service filters by repository and count only. --branch, --status and --release-request are applied here, over all of the repository's runs, and --limit after them.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits ci run

Show one CI run: what it built, its status, and its steps with their exit codes.

--logs also prints each step's output, the first step first. The service keeps the end of each step's output. A running step shows what it has printed so far. Terminal control characters are taken out.

```
qits ci run [--ci-url <url>] [--logs] [--output table|json] [--project <project>] [--projects-url <url>] [--repository <repository>] <run id>
```

| Name | What it does |
|---|---|
| `<run id>` | The run: its id, or its start when --project and --repository name its repository. |
| `--ci-url <url>` | The ci service's base URL, without /ci. Default: QITS_CI_URL, else the session's idp address with `idp` swapped for `ci` (https://idp.dev.wohlben.eu/idp gives https://ci.dev.wohlben.eu). |
| `--logs` | Also print each step's output, with terminal control characters taken out. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where --project and --repository are looked up. Default: QITS_PROJECTS_URL, else derived from the idp address like --ci-url. |
| `--repository <repository>` | The repository: its id or name. |

### Examples

```
qits ci run 5f2c0a9e-1b7d-4c2e-9a41-3d8e6f0b2c17 --logs
qits ci run 5f2c0a9e --project qits --repository qits-ci-service
qits ci run 5f2c0a9e-1b7d-4c2e-9a41-3d8e6f0b2c17 -o json | jq -r .status
```

- <run id> is the run's whole id, or its start when --project and --repository name the repository.
- The exit code says whether the read worked, not whether the run passed. Read the status.
- -o json prints the service's answer, the logs included.

### Exit codes

- `0` Done, whatever the run's status.
- `1` The platform refused (for example no such run, HTTP 404), or cannot be reached.
- `2` Used wrongly (for example an id start that fits no run of the repository, or more than one), not signed in, or the session ended.

## qits ci retry

Run a finished CI run again: the same commit, the same pipeline, the same release request.

For a red run that was the platform's fault, not the code's: a flaked container, a registry that was down, a step that ran out of time on a busy host. The new run is queued; the command prints its id and how to follow it. Its verdict counts for the release request like the first run's would have.

```
qits ci retry [--ci-url <url>] [--output table|json] [--project <project>] [--projects-url <url>] [--repository <repository>] <run id>
```

| Name | What it does |
|---|---|
| `<run id>` | The run to retry: its id, or its start when --project and --repository name its repository. |
| `--ci-url <url>` | The ci service's base URL, without /ci. Default: QITS_CI_URL, else the session's idp address with `idp` swapped for `ci` (https://idp.dev.wohlben.eu/idp gives https://ci.dev.wohlben.eu). |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where --project and --repository are looked up. Default: QITS_PROJECTS_URL, else derived from the idp address like --ci-url. |
| `--repository <repository>` | The repository: its id or name. |

### Examples

```
qits ci retry 5f2c0a9e-1b7d-4c2e-9a41-3d8e6f0b2c17
qits ci retry 5f2c0a9e --project qits --repository qits-ci-service
```

- Only a finished run can be retried. One that is queued or running answers HTTP 409: wait for it.
- A retry builds the same commit. To fix the code, push the branch instead: the release request folds again and builds the new commit.
- Needs the role qits:admin.

### Exit codes

- `0` The new run is queued.
- `1` The platform refused: the run has not finished yet (HTTP 409), there is no such run (HTTP 404), or your roles do not allow it (HTTP 403). Or it cannot be reached.
- `2` Used wrongly (for example an id start that fits no run of the repository, or more than one), not signed in, or the session ended.

## qits events

Print qits-events domain events as they happen, one JSON object per line on stdout. Use it to wait for something on the platform: a build (BuildSuccessful, BuildFailed) or a release request (ReleaseRequestChanged).

Each line is the event with its payload read as JSON. Live only: the stream has no replay, so start it before the thing you wait for; events that happen while it reconnects are missed. Notes go to stderr. Stops on SIGINT or SIGTERM.

```
qits events [--events-url <url>] [--filter <names>]
```

| Name | What it does |
|---|---|
| `--events-url <url>` | The events service's base URL, without /events. Default: QITS_EVENTS_URL, else the session's idp address with `idp` swapped for `events`. |
| `--filter <names>` | Which events, as services subscribe to them: exact event names, comma-separated (for example BuildSuccessful,BuildFailed), or * for every event. No patterns. Default: *. |

### Examples

```
qits events --filter=BuildSuccessful,BuildFailed
qits events --filter=ReleaseRequestChanged | jq -r '.payload'
```

--filter takes exact event names. A pattern such as Build* is refused.

### Exit codes

- `0` Stopped by SIGINT or SIGTERM, or stdout was closed.
- `1` The platform refused the stream (401, 403 or another 4xx).
- `2` Used wrongly (for example a pattern in --filter), not signed in, or the session ended.

## qits observe

Print what qits-observability takes in (logs, spans with their events, metrics) as it arrives. Use it to watch a service's errors or to follow one trace live. The service applies the filters and sends only the records that match.

Each --filter is one group of conditions, separated by spaces, that must all hold. A record that fits any group is printed. Live only: records that arrive while it reconnects are missed. Notes go to stderr. Stops on SIGINT or SIGTERM.

Conditions: F=V equal and F^=V starts with (both match case: status=ERROR, not status=error), F~V contains (any case), F? present, !F absent, level>=V severity at or above V (TRACE, DEBUG, INFO, WARN, ERROR, FATAL or 1-24).

Fields: kind service trace span level body name status event attr.<key> resource.<key>. attr.<key> is the record's own attribute, resource.<key> its resource's. Quote a value that holds spaces: body~"connection refused".

```
qits observe --filter <conditions>... [--observability-url <url>] [--output <text|json>]
```

| Name | What it does |
|---|---|
| `--filter <conditions>...` | Required. One group of conditions, for example 'kind=log level>=ERROR'. Give it again for another group. '*' streams every record. |
| `--observability-url <url>` | The observability service's base URL, without /observability. Default: QITS_OBSERVABILITY_URL, else the session's idp address with `idp` swapped for `observability`. |
| `-o, --output <text\|json>` | text: one line per record (the default). json: each record's frame as the service sends it, one per line. |

### Examples

```
qits observe --filter 'kind=log level>=ERROR' --filter 'kind=span event=exception'
qits observe --filter 'service^=qits-ci kind=log body~"connection refused"'
qits observe --filter 'trace=4bf92f3577b34da6a3ce929d0e0e4736'
qits observe --filter 'kind=log level>=WARN' -o json | jq -r .record.body
```

- A span's exception is an event of the span: event=exception finds it. attr.exception.type? matches logs only.
- F=V and F^=V match case: status=ERROR, not status=error. F~V ignores case.
- level takes >= only (level>=WARN), and only logs have a level.
- --filter '*' streams every record, which can be a lot.

### Exit codes

- `0` Stopped by SIGINT or SIGTERM, or stdout was closed.
- `1` The platform refused the socket (401, 403 or another 4xx).
- `2` A --filter cannot be read or the service refused the filters, not signed in, or the session ended.

## qits git-login

Sign this workstation in for Git pushes to the platform's git host, through the browser. Run it once; afterwards Git gets its token from `qits git-credential`.

The sign-in may push branches under refs/heads/external/ and nothing else. It is stored in $XDG_CONFIG_HOME/qits/git.json, apart from the session of `qits login`.

```
qits git-login [--audience <audience>] [--configure] [--git-host <url>] [--idp-url <url>] [--no-browser] [--timeout <seconds>]
```

| Name | What it does |
|---|---|
| `--audience <audience>` | The audience of the token. Default: <env>-qits-githost, where <env> is the idp host's second label (dev in idp.dev.wohlben.eu). |
| `--configure` | Also run the two `git config --global` commands that make Git ask `qits git-credential` for this git host (and no other). |
| `--git-host <url>` | The git host's address. Default: QITS_GIT_HOST_URL, else the idp's host with `idp` swapped for `githost` (https://githost.dev.wohlben.eu). |
| `--idp-url <url>` | The idp's public base URL. Default: QITS_IDP_URL, else the idp of the `qits login` session, else https://idp.<QITS_ENV_NAME>.<QITS_DOMAIN>/idp. |
| `--no-browser` | Only print the sign-in address; do not start a browser. |
| `--timeout <seconds>` | How long to wait for the browser to come back. Default: 300. |

### Examples

```
qits git-login --configure
git push <remote> HEAD:refs/heads/external/log-view
```

- The git host refuses a push to any other ref, main included. A push releases nothing: ask for a release with `qits release-request`.
- --configure sets the credential helper for the git host only; a global helper (for example for GitHub) stays.

### Exit codes

- `0` Signed in.
- `1` The sign-in did not complete (the browser did not come back in time, or the idp refused).
- `2` Used wrongly, or the idp or the git host cannot be worked out.

## qits git-credential

Git's credential helper for the platform's git host. Git runs it; a person does not. `qits git-login` sets it up.

get prints the stored sign-in's access token for Git, refreshing it first when needed. store is ignored. erase drops the cached access token and keeps the sign-in.

```
qits git-credential <get|store|erase>
```

| Name | What it does |
|---|---|
| `<get\|store\|erase>` | What Git asks for. |

### Examples

```
git config --global --get-all credential.https://githost.dev.wohlben.eu.helper
```

The example shows the setup. Do not run `get` yourself: it prints a token on stdout, because that is Git's helper protocol.

### Exit codes

- `0` Done. For a host without a sign-in it prints nothing, and Git asks its other helpers.
- `1` The sign-in file cannot be read or written.
- `2` No action named.
