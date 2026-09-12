---
name: qits
description: "Use for any work on the qits platform from a terminal: signing in, projects and repositories, release requests, domain events, live telemetry, and Git pushes to the platform's git host."
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

## qits release-request

The release requests of one repository: the one way to release it. list shows them, create asks for a branch to be released, and join adds a branch to an open request.

A request folds main and its branches into one commit, and the builds of that commit are its gate. States: PENDING (waiting for its builds), READY, RELEASED, REJECTED (a gating build was red), FAILED (the release itself failed), CONFLICTED (the branches do not merge), WITHDRAWN.

### Notes

- A REJECTED or CONFLICTED request comes back by itself when one of its branches gets a new push. Fix the branch and push; do not open a new request.
- When a red build was the platform's fault and not the code's (a flaked container, a registry that was down), retry that run in qits-ci: it builds the same commit again. Do not open a new request.
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
