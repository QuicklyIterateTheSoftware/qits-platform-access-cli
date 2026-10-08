---
name: qits
description: "Use for any work on the qits platform from a terminal: signing in, projects and repositories, work items of every archetype (epics, tickets, features, tasks, campaigns) and their comment threads, release requests, CI runs and their logs, domain events, live telemetry, Git pushes to the platform's git host, and publishing release artifacts from a CI step."
---

# qits

Each command calls the platform through its edge, with the session of `qits login`, except `qits artifacts publish`, which runs in a CI step container with no person and never touches that session. `qits <command> --help` shows a command's options, examples and exit codes.

A workspace is the token home: on a runner node, or an admin or editor workspace placed directly on qits-containers, with QITS_TOKEN set every command sends that token as it is, to the public vhosts https://<app>.qits.<QITS_DOMAIN>. It wins over the session and the commissioned pair, nothing is minted or written to disk, and a 401 means the token was deleted with the workspace's container. QITS_URL_<APP> still overrides an address.

## Platform rules

- Sign in once with `qits login`, and keep `qits session-daemon` running so the session stays fresh.
- Release only through a release request: `qits release-request create`, or `join` to add a branch to an open one. A push releases nothing.
- Push only branches under refs/heads/external/, after `qits git-login`. Git gets the token from `qits git-credential`.
- qits never prints a token. The two exceptions are `qits git-credential get`, which Git runs, and `qits mcp-credential`, which Claude runs.
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
| `--idp-url <url>` | The idp's public base URL. Default: QITS_IDP_URL, else https://idp.qits.<QITS_DOMAIN>/idp, else https://idp.qits.wohlben.eu/idp. |
| `--no-browser` | Only print the sign-in address; do not start a browser. |

### Examples

```
qits login
qits login --idp-url https://idp.qits.wohlben.eu/idp
qits login --no-browser
```

Over SSH, use --no-browser and open the printed address on a machine with a browser.

### Exit codes

- `0` Signed in; the session is stored.
- `1` The sign-in did not complete.
- `2` Used wrongly.

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

## qits checkout-daemon

Hold a local checkout at what a repository released, and keep it there. It follows the releases, never the tips of the branches: the root ends detached at the release tag, and every submodule detached at the gitlink that release recorded. That tree is the estate somebody reviewed and released, which is not the same thing as `latest`.

It brings the checkout to the newest release at the start, then waits for SCMRelease on the live event stream. After every connect it reads the newest release back, so a release cut while it was stopped or reconnecting is not missed. Notes go to stderr, and every release it moves the checkout to is one line on stdout. Stops on SIGINT or SIGTERM.

```
qits checkout-daemon [--events-url <url>] [--once] [--path <dir>] [--repository <name>] [--submodules]
```

| Name | What it does |
|---|---|
| `--events-url <url>` | The events service's base URL, without /events. Default: QITS_EVENTS_URL, else the session's idp address with `idp` swapped for `events`. |
| `--once` | Bring the checkout to the newest release and exit; do not open the stream. |
| `--path <dir>` | The checkout to hold. Default: the directory the command runs in. |
| `--repository <name>` | Whose releases to follow. Default: the repository the checkout's origin names. |
| `--submodules` | Hold the submodules at the gitlinks the release recorded. --no-submodules holds the root alone. Default: true. |

### Examples

```
qits checkout-daemon --path /workspace
qits checkout-daemon --path /workspace --once
qits checkout-daemon --path /srv/qits --repository qits-qits --no-submodules
```

- A checkout with local changes is never touched: it is somebody's work. It says so and keeps watching; with --once that is exit code 1. It never stashes, resets or merges.
- Git authentication stays the credential helper's: run `qits git-login` on a workstation, and in a container the injected host (QITS_GIT_AUTH_HOST) answers. No token is ever put in a URL.
- --repository is needed when the origin addresses the repository by its id (/git/<repository id>), which is the git host's internal storage scheme and names nothing.

### Exit codes

- `0` Stopped by SIGINT or SIGTERM, --once is done, or stdout was closed.
- `1` The platform refused the stream (401, 403 or another 4xx), or with --once the checkout has local changes or a git command failed.
- `2` Used wrongly (the origin names no repository and --repository was not given), Git has no credential for the origin's host, not signed in, or the session ended.

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
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.wohlben.eu/idp gives https://projects.qits.wohlben.eu). |

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

The repositories of one project: list shows them, create stands a new one up. Release requests take a repository's id or name as --repository.

A repository's archetype is read off its name's role suffix, so the name is what says what kind of component it is. There is no flag for the archetype, on purpose.

## qits repositories list

List the project's repositories: name, archetype, component and id. --project may come before or after list.

```
qits repositories list [--output table|json] [--project <project>] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.wohlben.eu/idp gives https://projects.qits.wohlben.eu). |

### Examples

```
qits repositories --project qits list
qits repositories list --project qits -o json
```

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits repositories create

Create a repository in the project: a blank one on the platform's git host, seeded with the repository template, and mounted in the project's wrapper.

The NAME says what kind of component it is: the service reads the archetype off the name's role suffix (payments-daemon is a DAEMON), and there is no flag to state one, because a flag could contradict the name. Name the repository <component>[-<modifier>]-<role>.

--component places the wrapper entry at components/<component>/<name>. Without it the wrapper's own layout decides.

```
qits repositories create [--component <component>] [--output table|json] [--project <project>] [--projects-url <url>] <name>
```

| Name | What it does |
|---|---|
| `<name>` | The repository's name, and with it its kind: it ends in a role suffix, and that suffix is what the archetype is read from. It is also what ../<name>.git resolves to. |
| `--component <component>` | The technical component to mount the entry under: components/<component>/<name>. Default: the wrapper's own layout decides. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.wohlben.eu/idp gives https://projects.qits.wohlben.eu). |

### Examples

```
qits repositories --project qits create qits-docs-app --component qits-docs
qits repositories create qits-payments-service --project qits --component qits-payments
qits repositories --project qits create qits-docs-app -o json
```

- Roles: -service, -frontend, -app, -daemon, -oci, -cli, -javalib, -jslib. A name that carries none of them is refused by the service, because a guessed kind is the one thing nothing downstream could correct.
- Creating a repository needs the role qits:admin or qits:agent.
- The command prints what the service answered: the name, the archetype it derived, the component, the id, and the wrapper path the entry was written at.

### Exit codes

- `0` The repository exists and the wrapper names it.
- `1` The platform refused (the message names the status and what to do next), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits work

Work items of every archetype - epics, tickets, features, tasks and campaigns - and their comment threads. list shows a project's items, details shows one with its comments and children, create files one, update edits its fields, transition reshapes it into another archetype, status moves it along its lifecycle, and comment writes to its thread.

A write reads its payload, a JSON document, on stdin. With nothing on stdin (a terminal, or empty) it sends nothing and prints its usage and the payload's JSON schema instead, served by the service for that archetype.

Every archetype has a lifecycle: REPORTED, REFINED, READY_FOR_DEV, IMPLEMENTING, IMPLEMENTED, VERIFYING, VERIFIED, DONE, and DROPPED for work a decision was taken not to do. Features and tasks walk the same nine words as epics and tickets; campaigns keep a shorter walk and never enter IMPLEMENTING or VERIFYING. A SKIP transition lets READY_FOR_DEV move straight to IMPLEMENTED, bypassing IMPLEMENTING, and IMPLEMENTED move straight to VERIFIED, bypassing VERIFYING. IMPLEMENTING only moves forward or drops; it never moves back. REFINED to READY_FOR_DEV needs a person - a person's own `qits` CLI sign-in, or the browser; an agent credential is refused. `status` names the moves open from where an item stands.

### Notes

- --entity, --output and --projects-url may come before or after the command.
- --entity is the item's qualified id (qits-100) or its id, sent to the service as it is; the service resolves either. So is every id inside a payload (parent, membership.parent, supersededBy, dependsOn).
- Reading takes qits:admin or qits:agent; so does writing, except an epic's status move, which takes qits:admin. An agent writes only in its own project.
- REFINED to READY_FOR_DEV takes a person signed in with their own `qits` CLI or the browser; an agent credential is refused (HTTP 409, or HTTP 403 for an epic).

## qits work list

A project's work items, every archetype, in the service's order: id (qualified), archetype, status, title and when it last changed. BLOCKED appears when a ticket is.

Each filter narrows the list; without one it shows everything.

```
qits work list [--archetype <archetype>] [--entity <entity>] [--output table|json] [--parent <entity>] [--project <project>] [--projects-url <url>] [--status <status>]
```

| Name | What it does |
|---|---|
| `--archetype <archetype>` | Only items of this archetype: EPIC, TICKET, FEATURE, TASK or CAMPAIGN. |
| `--entity <entity>` | The work entity: its id or its qualified id (qits-100), passed to the service as it is. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--parent <entity>` | Only the children of this item: its id or its qualified id. |
| `--project <project>` | The project (required): its id or its slug. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.wohlben.eu/idp gives https://projects.qits.wohlben.eu). |
| `--status <status>` | Only items in this status, such as REFINED. |

### Examples

```
qits work list --project qits
qits work list --project qits --archetype epic --status REFINED
qits work list --project qits --parent qits-120 -o json
```

- --archetype and --status are case-insensitive. --parent lists the children of one item.

### Exit codes

- `0` Done.
- `1` The platform refused (for example an unknown archetype or status, HTTP 400, or a project it does not know, HTTP 404), or cannot be reached.
- `2` Used wrongly (for example no --project), not signed in, or the session ended.

## qits work details

One work item: its fields, its description, its comment thread and its children.

```
qits work details [--entity <entity>] [--output table|json] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--entity <entity>` | The work entity: its id or its qualified id (qits-100), passed to the service as it is. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.wohlben.eu/idp gives https://projects.qits.wohlben.eu). |

### Examples

```
qits work --entity qits-100 details
qits work details --entity 45a14f8e-f550-45bb-a117-6b34d8c472e3 -o json
```

- -o json prints one object: the item as the service answers it, its comments and its children. Control characters are written as escapes.

### Exit codes

- `0` Done.
- `1` The platform refused (for example an item it does not know, HTTP 404), or cannot be reached.
- `2` Used wrongly (for example no --entity), not signed in, or the session ended.

## qits work create

File a new work item of an archetype.

Reads the payload, a JSON object, on stdin, sets its "archetype" from --archetype, and sends it to POST /projects/api/work. A root item names its "project" (id or slug), a child its "parent" (qualified id or id). The item starts REPORTED if its archetype has a lifecycle. The command prints the new item, its qualified id first.

With nothing on stdin (a terminal, or empty) it sends nothing, prints this usage and the payload's JSON schema, served by the service at GET /projects/api/work/archetypes/{archetype}/schemas/create, and exits with 0.

```
qits work create [--archetype <archetype>] [--entity <entity>] [--output table|json] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--archetype <archetype>` | The new item's archetype (required): EPIC, TICKET, FEATURE, TASK or CAMPAIGN. Case-insensitive. |
| `--entity <entity>` | The work entity: its id or its qualified id (qits-100), passed to the service as it is. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.wohlben.eu/idp gives https://projects.qits.wohlben.eu). |

### Examples

```
qits work create --archetype ticket </dev/null
echo '{"project":"qits","title":"The log view stops at 64 KiB","ticketType":"BUG","impetus":"A long run's log is cut."}' | qits work create --archetype ticket
jq -n --rawfile d plan.md '{parent:"qits-120",title:"Retry",description:$d}' | qits work create --archetype feature -o json
```

- The first one prints the schema: nothing is sent.

### Exit codes

- `0` The item is filed, or nothing was put in and the schema is printed.
- `1` The platform refused (for example a payload it does not take, HTTP 400, your roles or another project, HTTP 403, or an archetype it does not know, HTTP 404), or cannot be reached.
- `2` Used wrongly (for example no --archetype, or a payload that is not a JSON object), not signed in, or the session ended.

## qits work update

Edit a work item's fields.

Reads a JSON merge patch on stdin and sends it unchanged to PATCH /projects/api/work/{entity} as application/merge-patch+json: a property left out stays as it is, null clears it. Status and archetype are not edited here; see status and transition.

With nothing on stdin (a terminal, or empty) it sends nothing, looks the item up for its archetype, prints this usage and the patch's JSON schema for that archetype, served by the service at GET /projects/api/work/archetypes/{archetype}/schemas/update, and exits with 0.

```
qits work update [--entity <entity>] [--output table|json] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--entity <entity>` | The work entity: its id or its qualified id (qits-100), passed to the service as it is. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.wohlben.eu/idp gives https://projects.qits.wohlben.eu). |

### Examples

```
qits work --entity qits-100 update </dev/null
echo '{"title":"The log view stops at 64 KiB"}' | qits work --entity qits-100 update
echo '{"assignee":null}' | qits work update --entity qits-100 -o json
```

### Exit codes

- `0` The item is edited, or nothing was put in and the schema is printed.
- `1` The platform refused (for example a patch it does not take, HTTP 400, your roles or another project, HTTP 403, or an item it does not know, HTTP 404), or cannot be reached.
- `2` Used wrongly (for example no --entity, or a patch that is not a JSON object), not signed in, or the session ended.

## qits work transition

Reshape a work item into another archetype (a ticket into an epic, a feature into a task, ...), keeping its id, its number and its thread.

The door is full-state: what the request leaves out is cleared. So the command starts from the item as it stands (title, description, status, ticketType, impetus, assignee, supersededBy, repositoryId, implementingAt, implementedAt, dependsOn, and membership {parent, position} if it has a parent), merges the JSON object on stdin over it as a merge patch (null clears), sets "archetype", and drops every property the target archetype's schema has no slot for, naming them on stderr. It sends the result to POST /projects/api/work/transition, keyed by --entity as it was given.

With nothing on stdin (a terminal, or empty) it sends nothing, prints this usage and the target's JSON schema, served at GET /projects/api/work/archetypes/{archetype}/schemas/transition, names the required properties the item does not carry yet and the ones that would be dropped, and exits with 0.

```
qits work transition [--archetype <archetype>] [--entity <entity>] [--output table|json] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--archetype <archetype>` | The archetype to turn the item into (required): EPIC, TICKET, FEATURE, TASK or CAMPAIGN. Case-insensitive. |
| `--entity <entity>` | The work entity: its id or its qualified id (qits-100), passed to the service as it is. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.wohlben.eu/idp gives https://projects.qits.wohlben.eu). |

### Examples

```
qits work --entity qits-100 transition --archetype epic </dev/null
echo '{}' | qits work --entity qits-100 transition --archetype epic
echo '{"membership":{"parent":"qits-120"}}' | qits work --entity qits-100 transition --archetype feature
```

- {} carries the item over as it stands. membership.parent, supersededBy and dependsOn take a qualified id or an id.

### Exit codes

- `0` The item is reshaped, or nothing was put in and the schema is printed.
- `1` The platform refused (for example a state it does not take, HTTP 400, your roles or another project, HTTP 403, an item or archetype it does not know, HTTP 404, or a conflict, HTTP 409), or cannot be reached.
- `2` Used wrongly (for example no --entity or --archetype, or a payload that is not a JSON object), not signed in, or the session ended.

## qits work status

Move a work item along its lifecycle: REPORTED, REFINED, READY_FOR_DEV, IMPLEMENTING, IMPLEMENTED, VERIFYING, VERIFIED, DONE, or DROPPED. Every archetype walks it, features and tasks included; campaigns keep a shorter walk and never enter IMPLEMENTING or VERIFYING. A SKIP transition lets READY_FOR_DEV move straight to IMPLEMENTED, bypassing IMPLEMENTING, and IMPLEMENTED move straight to VERIFIED, bypassing VERIFYING. IMPLEMENTING only moves forward or drops; it never moves back. REFINED to READY_FOR_DEV needs a person - a person's own `qits` CLI sign-in, or the browser; an agent credential is refused.

Reads {"target":"<STATUS>"} on stdin and sends it unchanged to POST /projects/api/work/{entity}/status. The service refuses a move its lifecycle does not allow (HTTP 409).

With nothing on stdin (a terminal, or empty) it sends nothing, prints this usage and the payload's schema, whose target enum is the moves open from the item's current status as the service's archetype registry (GET /projects/api/work/archetypes) states them, and exits with 0.

```
qits work status [--entity <entity>] [--output table|json] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--entity <entity>` | The work entity: its id or its qualified id (qits-100), passed to the service as it is. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.wohlben.eu/idp gives https://projects.qits.wohlben.eu). |

### Examples

```
qits work --entity qits-100 status </dev/null
echo '{"target":"REFINED"}' | qits work --entity qits-100 status
```

- An epic's status move takes qits:admin; an agent is answered HTTP 403.
- REFINED to READY_FOR_DEV takes a person signed in with their own `qits` CLI or the browser; an agent credential is refused (HTTP 409, or HTTP 403 for an epic).

### Exit codes

- `0` The item moved, or nothing was put in and the legal targets are printed.
- `1` The platform refused (for example a move the lifecycle does not allow, HTTP 409, your roles or another project, HTTP 403, or an item it does not know, HTTP 404), or cannot be reached.
- `2` Used wrongly (for example no --entity, an item on a service whose registry has not yet given its archetype a lifecycle, or a payload that is not a JSON object), not signed in, or the session ended.

## qits work comment

The comment thread of a work entity: create adds a comment, update edits one.

Both read a JSON document on stdin and send it as it is. With nothing on stdin they send nothing and print the payload's JSON schema, with its required fields named.

### Notes

- The author is the signed-in caller, and an edit leaves it as it is. Anybody who may comment may edit a comment's text.
- Deleting a comment takes qits:admin, and qits has no command for it.

## qits work comment create

Add a comment to a work entity's thread.

Reads the payload, a JSON object such as {"body":"..."}, on stdin and sends it unchanged to POST /projects/api/work/{entity}/comments. The body is Markdown. You are its author. The command prints the comment once filed.

With nothing on stdin (a terminal, or empty) it sends nothing, prints the payload's JSON schema from the service's OpenAPI document (/projects/q/openapi) with the required fields named, and exits with 0.

```
qits work comment create [--entity <entity>] [--output table|json] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--entity <entity>` | The work entity: its id or its qualified id (qits-100), passed to the service as it is. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.wohlben.eu/idp gives https://projects.qits.wohlben.eu). |

### Examples

```
echo '{"body":"I can reproduce it."}' | qits work --entity qits-100 comment create
jq -n --rawfile b note.md '{body: $b}' | qits work comment create --entity qits-100 -o json
qits work --entity qits-100 comment create </dev/null
```

- The last one prints the schema: nothing is sent.
- -o json prints the service's answer; the table prints the comment's id, author and time.

### Exit codes

- `0` The comment is filed, or nothing was put in and the schema is printed.
- `1` The platform refused (for example your roles or another project's entity, HTTP 403, an entity it does not know, HTTP 404, or a payload it does not take, HTTP 400), cannot be reached, or its OpenAPI document does not describe the payload.
- `2` Used wrongly (for example a payload that is not a JSON object, or no --entity), not signed in, or the session ended.

## qits work comment update

Edit a comment on a work entity's thread.

Reads a JSON merge patch, such as {"body":"..."}, on stdin and sends it unchanged to PATCH /projects/api/work/{entity}/comments/{commentId} as application/merge-patch+json. The path names the entity and the comment together, so the service answers a comment that is not on the entity's thread with HTTP 404. The author stays who it was.

With nothing on stdin (a terminal, or empty) it sends nothing, prints the patch's JSON schema from the service's OpenAPI document (/projects/q/openapi) with the required fields named, and exits with 0.

```
qits work comment update [--comment <comment>] [--entity <entity>] [--output table|json] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--comment <comment>` | The comment to edit (required unless nothing is put in): its id. |
| `--entity <entity>` | The work entity: its id or its qualified id (qits-100), passed to the service as it is. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.wohlben.eu/idp gives https://projects.qits.wohlben.eu). |

### Examples

```
echo '{"body":"Fixed in 2026.929.1."}' | qits work --entity qits-100 comment update --comment 8de195f1-631d-420a-893e-7baca2229be4
qits work --entity qits-100 comment update </dev/null
```

- --comment is the comment's full id, as `create` printed it.
- -o json prints the service's answer; the table prints the comment's id, author and time.

### Exit codes

- `0` The comment is edited, or nothing was put in and the schema is printed.
- `1` The platform refused (for example your roles, HTTP 403, an entity it does not know or a comment that is not on its thread, HTTP 404, or a patch it does not take, HTTP 400), cannot be reached, or its OpenAPI document does not describe the patch.
- `2` Used wrongly (for example a patch that is not a JSON object, or no --entity or --comment), not signed in, or the session ended.

## qits release-request

The release requests of one repository: the one way to release it. list shows them, create asks for a branch to be released, join adds a branch to an open request, and withdraw ends a request that must not ship.

A request folds main and its branches into one commit, and the builds of that commit are its gate. States: PENDING (waiting for its builds), READY, RELEASED (the tag is cut, waiting on its remaining gates), FINALIZED (the tag is merged into main, done), REJECTED (a gating build was red), FAILED (the release itself failed), CONFLICTED (the branches do not merge), WITHDRAWN, OBSOLETE (a later request for the repository superseded this one before it finished).

### Notes

- A REJECTED or CONFLICTED request comes back by itself when one of its branches gets a new push. Fix the branch and push; do not open a new request.
- When a red build was the platform's fault and not the code's (a flaked container, a registry that was down), retry that run with `qits ci retry <run id>`: it builds the same commit again. `qits ci runs --release-request <id>` finds the request's runs. Do not open a new request, and do not withdraw this one.
- `withdraw` is only for a request that must not ship: the change is wrong, or nobody wants it any more. WITHDRAWN is final.
- --project and --repository may come before or after the command.

## qits release-request list

List the repository's open release requests.

Open means every state but FINALIZED, WITHDRAWN and OBSOLETE. --state asks for other ones. The ID column shows the first 8 characters of the id, which is enough for `join`.

```
qits release-request list [--output table|json] [--project <project>] [--projects-url <url>] [--repository <repository>] [--state <STATE|all>]
```

| Name | What it does |
|---|---|
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.wohlben.eu/idp gives https://projects.qits.wohlben.eu). |
| `--repository <repository>` | The repository: its id or name. |
| `--state <STATE\|all>` | Only this state (PENDING, READY, RELEASED, FINALIZED, REJECTED, FAILED, CONFLICTED, WITHDRAWN, OBSOLETE), or all for every request. Default: the open ones. |

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
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.wohlben.eu/idp gives https://projects.qits.wohlben.eu). |
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
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.wohlben.eu/idp gives https://projects.qits.wohlben.eu). |
| `--repository <repository>` | The repository: its id or name. |

### Examples

```
qits release-request --project qits --repository qits-ci-service join --request 4f2a91c0 --branch feature/log-search
qits release-request --project qits --repository qits-ci-service join --request 4f2a91c0 --branch feature/log-search --priority BLOCKING
```

- Safe to repeat: a branch already on the request adds nothing. With --priority it states that priority again; without, the branch keeps its priority.
- A RELEASED, FINALIZED, WITHDRAWN or OBSOLETE request takes no more branches (HTTP 409): open a new one with `create`.

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
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.wohlben.eu/idp gives https://projects.qits.wohlben.eu). |
| `--reason <text>` | Why it must not ship, in a sentence. The request shows it as its detail. Default: the platform writes who withdrew it. |
| `--repository <repository>` | The repository: its id or name. |

### Examples

```
qits release-request --project qits --repository qits-ci-service withdraw --request 4f2a91c0
qits release-request --project qits --repository qits-ci-service withdraw --request 4f2a91c0 --reason "The log view moves to qits-observability"
```

- Only for a request that must not ship. A gating build that was red because of the platform, not the code, runs again with `qits ci retry <run id>`. A REJECTED or CONFLICTED request comes back by itself when one of its branches gets a new push.
- Without --reason the platform writes who withdrew it.
- A RELEASED, FINALIZED, WITHDRAWN or OBSOLETE request cannot be withdrawn (HTTP 409).

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly (for example a --request that fits no request, or more than one), not signed in, or the session ended.

## qits ci

The builds of qits-ci: runs lists a repository's runs, run shows one run with its steps and their logs, retry runs a finished run again, and report shows a run's release reports (report submit is the CI step's side, which collects and uploads them).

A release request's gating runs build its backing branch release/<request id> and carry the request's id. Statuses: QUEUED and RUNNING (not finished), SUCCESS, FAILED (the code's verdict), and CANCELLED, TIMED_OUT, CONFIG_ERROR (the run's end, not a verdict on the code).

### Notes

- Reading runs needs the role qits:admin or qits:system. retry needs qits:admin.
- To follow a run, run `qits ci run <run id>` again. There is no live log stream. A build's verdict also comes as an event: `qits events --filter=BuildSuccessful,BuildFailed`.
- --project, --repository, --output and the two -url options may come before or after the command.
- A release request's QA run carries release reports: which tests failed, with their class, name and message, and the line coverage of the tree and of the change, with more kinds to come. `qits ci report show <run id>` lists them.

## qits ci runs

List a repository's CI runs, newest first.

Columns: the run's id (its first 8 characters, enough for `run` and `retry` with --project and --repository), status, branch, commit, the release request, when it was created, and how long it took (so far, while it runs).

```
qits ci runs [--branch <branch>] [--ci-url <url>] [--limit <n>] [--output table|json] [--project <project>] [--projects-url <url>] [--release-request <id>] [--repository <repository>] [--status <STATUS>]
```

| Name | What it does |
|---|---|
| `--branch <branch>` | Only the runs of this branch: main, release/<request id>, or a version for a release run. |
| `--ci-url <url>` | The ci service's base URL, without /ci. Default: QITS_CI_URL, else the session's idp address with `idp` swapped for `ci` (https://idp.qits.wohlben.eu/idp gives https://ci.qits.wohlben.eu). |
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
| `--ci-url <url>` | The ci service's base URL, without /ci. Default: QITS_CI_URL, else the session's idp address with `idp` swapped for `ci` (https://idp.qits.wohlben.eu/idp gives https://ci.qits.wohlben.eu). |
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
| `--ci-url <url>` | The ci service's base URL, without /ci. Default: QITS_CI_URL, else the session's idp address with `idp` swapped for `ci` (https://idp.qits.wohlben.eu/idp gives https://ci.qits.wohlben.eu). |
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

## qits ci report

Release reports: what a release request's QA run found, beside its verdict. show lists a run's reports with their highlights, and prints one kind's whole report. submit is what every QA step runs after its script: it collects the reports from the step's files and uploads them.

Kinds today: test-results (every test run, and each failing test with its class, name, file and message), coverage (line coverage of the whole tree, its change against the baseline, and the coverage of the lines the change touched), contracts (the pacts the repository holds as consumer or verifies as provider, and the provider states it declares, with the pacts, interactions and states added or removed since the baseline), entity-changes (the generated entity diagrams under docs/database/, with the tables, columns and relations added, removed or changed since the baseline) and screenshots (the committed screenshot baselines added, changed or removed since the baseline, by path and blob, for the release request to show before and after). A kind or a run with nothing to report shows nothing; a report never fails or holds a release.

### Notes

- Each kind and its highlights are computed by the CLI that submitted them; qits-ci keeps them as they came, keyed by run, step and kind.
- A report compares with its baseline: the same kind in the QA run of the release request that produced the repository's newest released version. A first release has none.

## qits ci report submit

Collect this CI step's release reports from its files and upload them to qits-ci. Run in a QA step, after the step's own script, with that script's exit code.

For each kind: find its inputs under --root, parse them, compare with the baseline's report of the same kind, and PUT the result to the run's step. A kind with no inputs is not reported, and nothing is sent for it. One line per kind on stdout: `test-results: submitted (412 tests, 3 failed)`, `coverage: submitted (10031 lines, 80.9% covered)` or `coverage: not reported (no inputs)`.

```
qits ci report submit --exit-code <n> [--root <dir>]
```

| Name | What it does |
|---|---|
| `--exit-code <n>` | Required. What the step's own script exited with. Kept with the report; a non-zero code with no failing test is a highlight of its own. |
| `--root <dir>` | Where the step's tree is: the reports are looked for, and file paths are relative to, this directory. Default: the working directory. |

### Examples

```
qits ci report submit --exit-code "$qits_step_exit"
qits ci report submit --exit-code 1 --root /workspace/checkout
```

- test-results reads **/target/surefire-reports/TEST-*.xml, **/target/failsafe-reports/TEST-*.xml and .qits-reports/vitest-*.xml. A file that does not parse is skipped with a warning.
- A failing test gets the lines it sits on in its file for Java with surefire or failsafe (the JUnit test method, its annotations included) and for TypeScript or JavaScript with vitest (the it/test block); any other failure has none.
- coverage reads .qits-reports/jacoco.exec (JaCoCo 0.8.14's format) against every **/target/classes, and coverage/**/coverage-final.json or .qits-reports/coverage/**/coverage-final.json (istanbul's json, as vitest writes it). With a baseline it fetches the baseline's tag (git fetch --depth=1 "$QITS_CI_REPOSITORY_URL" refs/tags/<version>) and measures the lines `git diff -U0 <version> HEAD` names; when git cannot, the diff coverage is left out, with one warning.
- contracts reads pacts/*.json (the committed consumer pacts, Pact v2 to v4), .qits-reports/pact-verification/*.json (Pact-JVM's JSON verification report: the pacts the provider verified) and golden-masters/index.json (the provider states), and only in a step where test-results found a file, so a run reports its tree once.
- entity-changes reads the generated docs/database/*.md (the files `qits database diagram` wrote; a hand-written file there is ignored), only in step 0, and compares them with the same files at the baseline's tag, fetched as for coverage. Without a baseline, or at a baseline from before the diagrams, every unit is CURRENT: the diagram is new.
- screenshots reads the committed screenshot baselines (vitest browser mode's **/__screenshots__/<spec file>/<name>-<browser>-<platform>.png) from `git ls-tree` at HEAD and at the baseline's tag, fetched as for coverage, and lists the NEW, CHANGED and REMOVED ones by path and blob id, never their bytes, with the keys of **/testing/browser/renderer.txt that changed. Only in a step that rendered them: one on the renderer image (/etc/qits-renderer-provenance) or with @qits/angular's run record (node_modules/.cache/@qits/angular/screenshot-references.json); otherwise `screenshots: not reported (not rendered in this step)`, and `(no screenshot baselines)` when neither side holds one. QITS_CI_REPO_ID names the repository for the images. Without a baseline every image is NEW.
- The step's environment says which run and step: QITS_CI_RUN_ID, QITS_CI_STEP_INDEX, QITS_CI_SHA, QITS_CI_REPO_NAME and QITS_CI_PROJECT_ID, all required. qits-ci is https://ci.qits.$QITS_DOMAIN (QITS_DOMAIN defaults to wohlben.eu); no variable and no option names another address.
- The bearer comes from QITS_PUBLISH_TOKEN_COMMAND, QITS_PUBLISH_TOKEN, or QITS_COMMISSIONED_CLIENT_ID and QITS_COMMISSIONED_CLIENT_SECRET, the first that is set, as for `qits artifacts publish`. qits-ci takes only the run's own ci-run token, while the run is running.
- No baseline (a first release, or qits-ci cannot say) is not an error: the report is sent without the comparison.
- Gives up after 120 seconds in all, whatever is still waiting.
- The exit code says whether the reports were stored, never whether the tests passed. The hook that runs this ignores it, so a report never changes a step's verdict.

### Exit codes

- `0` Every kind that found inputs was stored, or no kind found any.
- `1` qits-ci refused a report (the message names the status: 403 for another run's token, 409 for a run that is not running, 404 for a qits-ci without the doors), could not be reached, or did not answer within 120 seconds.
- `2` Used wrongly: --exit-code missing, a QITS_CI_* variable missing, --root not a directory, or no credential in the environment.

## qits ci report show

Show a CI run's release reports: each kind, its version, the step that submitted it, and its highlights ("3 tests failed").

--kind also prints that kind's whole report as JSON: for test-results, the totals, the suites, and each failing test with its file, class, name, shape and message; for coverage, the total, the baseline's total, the changed lines' coverage with the uncovered ones, and a line per file; for contracts, the pacts by role (CONSUMER, PROVIDER) and pair with their interactions, and the provider states with their operations; for entity-changes, each diagram's unit with its status, the tables, columns and relations that changed, and its Mermaid text before and after; for screenshots, the payload: the githost repository id, the fold's and the baseline's commits, the totals, the renderer record's changed keys, and each NEW, CHANGED or REMOVED baseline image with its path, spec, name, browser, blob ids, sizes and any exact move.

```
qits ci report show [--ci-url <url>] [--kind <kind>] [--output table|json] [--project <project>] [--projects-url <url>] [--repository <repository>] <run id>
```

| Name | What it does |
|---|---|
| `<run id>` | The run: its id, or its start when --project and --repository name its repository. |
| `--ci-url <url>` | The ci service's base URL, without /ci. Default: QITS_CI_URL, else the session's idp address with `idp` swapped for `ci` (https://idp.qits.wohlben.eu/idp gives https://ci.qits.wohlben.eu). |
| `--kind <kind>` | Only this kind of report (test-results, coverage, contracts, entity-changes, screenshots), and print the whole of it. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where --project and --repository are looked up. Default: QITS_PROJECTS_URL, else derived from the idp address like --ci-url. |
| `--repository <repository>` | The repository: its id or name. |

### Examples

```
qits ci report show 5f2c0a9e-1b7d-4c2e-9a41-3d8e6f0b2c17
qits ci report show 5f2c0a9e --project qits --repository qits-ci-service --kind test-results
qits ci report show 5f2c0a9e --project qits --repository qits-ci-service --kind coverage
qits ci report show 5f2c0a9e --project qits --repository qits-landing-app --kind contracts
qits ci report show 5f2c0a9e --project qits --repository qits-ci-service --kind entity-changes
qits ci report show 5f2c0a9e --project qits --repository qits-landing-app --kind screenshots
qits ci report show 5f2c0a9e-1b7d-4c2e-9a41-3d8e6f0b2c17 -o json
```

- A release request's QA run is the one with reports: `qits ci runs --release-request <id>` finds it.
- <run id> is the run's whole id, or its start when --project and --repository name the repository.
- -o json prints the service's answer: the summaries, or with --kind the whole reports of that kind.

### Exit codes

- `0` Done, whatever the reports say, and also when the run has none.
- `1` The platform refused (for example no such run, HTTP 404), or cannot be reached.
- `2` Used wrongly (for example an id start that fits no run of the repository, or more than one), not signed in, or the session ended.

## qits database

A repository's database, read from its compiled code: `qits database diagram` writes the diagram of its entities under docs/database/, one Mermaid file per persistence unit.

## qits database diagram

Write the entity diagram of a maven repository's JPA mapping: one Markdown file with a Mermaid erDiagram per persistence unit it declares, read from its compiled classes.

Compile first: the classes come from every reactor module's target/classes and the libraries from each module's target/qits-classpath.txt, which `dependency:build-classpath -Dmdep.outputFile=target/qits-classpath.txt -DincludeScope=runtime` writes. One line per file on stdout: `written docs/database/ci.md`, `unchanged ...` or `deleted ...`.

```
qits database diagram [--check] [--out <dir>] [--root <dir>]
```

| Name | What it does |
|---|---|
| `--check` | Write and delete nothing; exit 1 when a file would change. Lines say `would write` and `would delete`. |
| `--out <dir>` | Where the files go. Default: docs/database under --root. |
| `--root <dir>` | The repository: the directory of its root pom.xml. Default: the working directory. |

### Examples

```
./mvnw -q -Dmaven.test.skip=true test-compile dependency:build-classpath -Dmdep.outputFile=target/qits-classpath.txt -DincludeScope=runtime
qits database diagram
qits database diagram --root . --out docs/database --check
```

- test-compile rather than compile: dependency:build-classpath resolves the test scope too, and in a reactor where one module depends on another's test jar that resolution fails at compile. -Dmaven.test.skip=true still compiles no test. The scope flag is -DincludeScope: the plugin reads no -Dmdep.includeScope, and with it the file lists every scope, the test jars too.
- A unit is an unprofiled quarkus.hibernate-orm.<unit>.packages key (quarkus.hibernate-orm.packages is <default>, written to default.md) in a reactor module's own application.properties or META-INF/microprofile-config.properties, never a library's. A profiled key or a ${...} value is ignored, with a warning on stderr.
- A unit draws every @Entity in its packages and their sub-packages, compiled here or in a library jar; each table names its origin, the reactor module or the library's artifactId. An entity of the repository that no unit lists is drawn in a file named after its Java package.
- Names are Hibernate's under Quarkus' defaults: JPA's implicit naming, and every name as the mapping spells it, unless the unit's physical-naming-strategy key names CamelCaseToUnderscoresNamingStrategy (then snake_case). A construct the diagram does not draw (@OneToOne, @ManyToMany, @EmbeddedId, @Inheritance, @SecondaryTable, @Formula and others) is listed under it as `Not drawn`, never refused.
- Deletes only the *.md files under --out that start with the generator's header and were not written this time. A file without the header is never touched.
- The files hold no version, time or absolute path: two runs over the same classes write the same bytes.
- Reads local files only: it needs no sign-in and calls no service.

### Exit codes

- `0` Done, or nothing to draw (`no JPA entities found`).
- `1` With --check: a file would be written or deleted. Otherwise: a file could not be read or written.
- `2` Used wrongly: --root is not a directory, or nothing is compiled under it (`compile first: no target/classes under <root>`).

## qits maintenance

Jobs of qits-maintenance: automations lists a release request's release-request automations and their state, automation run re-runs one kind, and bump shows how one job went.

### Notes

- --project, --repository, --output and the two -url options may come before or after the command.

## qits maintenance automations

List a release request's release-request automations and their state: the regenerations that must be fresh before the request can proceed, each one a kind the platform decided applies to this repository.

The request holds until every automation that applies to the repository is fresh for its merged commit. --fold reads an older fold; without it, the request's newest one.

```
qits maintenance automations --request <id> [--fold <sha>] [--maintenance-url <url>] [--output table|json] [--project <project>] [--projects-url <url>] [--repository <repository>]
```

| Name | What it does |
|---|---|
| `--request <id>` | Required. The release request: its id, or enough of its start to name one (`qits release-request list` shows 8 characters). |
| `--fold <sha>` | The fold to read; without it, the request's newest. |
| `--maintenance-url <url>` | The maintenance service's base URL, without /maintenance. Default: QITS_MAINTENANCE_URL, else the session's idp address with `idp` swapped for `maintenance`. |
| `-o, --output table\|json` | table (the default): one line per field. json: the service's answer, pretty-printed. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where --project, --repository and --request are looked up. Default: QITS_PROJECTS_URL, else derived like --maintenance-url. |
| `--repository <repository>` | The repository: its id or name. |

### Examples

```
qits maintenance --project qits --repository qits-landing-app automations --request 4f2a91c0
```

- RUN is the newest run of the kind's current attempt. `qits maintenance automation run` re-runs one; `qits ci run <id> --logs` shows a run.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits maintenance automation

One release-request automation of a release request. `run` re-runs a kind on the request's current fold.

## qits maintenance automation run

Re-run one release-request automation on a release request's current fold.

It skips carry-over and applicability, so a repository's first screenshot references come from here. The kind's own precondition still refuses the run with a sentence (no `test:browser` script, for example). It prints the job's id; `qits maintenance bump <id>` shows how it went.

```
qits maintenance automation run --kind <kind> --request <id> [--maintenance-url <url>] [--output table|json] [--project <project>] [--projects-url <url>] [--repository <repository>] [--work-item <id>]
```

| Name | What it does |
|---|---|
| `--kind <kind>` | Required. The automation's kind, as `qits maintenance automations --request <id>` names it. |
| `--request <id>` | Required. The release request: its id, or enough of its start to name one (`qits release-request list` shows 8 characters). |
| `--maintenance-url <url>` | The maintenance service's base URL, without /maintenance. Default: QITS_MAINTENANCE_URL, else the session's idp address with `idp` swapped for `maintenance`. |
| `-o, --output table\|json` | table (the default): one line per field. json: the service's answer, pretty-printed. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where --project, --repository and --request are looked up. Default: QITS_PROJECTS_URL, else derived like --maintenance-url. |
| `--repository <repository>` | The repository: its id or name. |
| `--work-item <id>` | The work item the commit names, for example qits-112. |

### Examples

```
qits maintenance --project qits --repository qits-landing-app automation run --request 4f2a91c0 --kind estate-pins
qits maintenance --project qits --repository qits-landing-app automation run --request 4f2a91c0 --kind estate-pins --work-item qits-112
```

- `qits maintenance automations --request <id>` names the kinds; `kind` is the first column.
- 404 means the kind or the repository is not one the platform knows. 409 means one is already running for this (request, kind), the request takes no branch, or bumping is off.
- --work-item is the commit subject's scope (chore(<work item>): update <label>). Without it, the newest one named on the request's own commits is used.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits maintenance bump

Show one qits-maintenance job: its mode, status, the sentence about how it went, and the commit it left.

```
qits maintenance bump [--maintenance-url <url>] [--output table|json] [--project <project>] [--projects-url <url>] [--repository <repository>] <id>
```

| Name | What it does |
|---|---|
| `<id>` | The job's id, as the request printed it. |
| `--maintenance-url <url>` | The maintenance service's base URL, without /maintenance. Default: QITS_MAINTENANCE_URL, else the session's idp address with `idp` swapped for `maintenance`. |
| `-o, --output table\|json` | table (the default): one line per field. json: the service's answer, pretty-printed. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where --project, --repository and --request are looked up. Default: QITS_PROJECTS_URL, else derived like --maintenance-url. |
| `--repository <repository>` | The repository: its id or name. |

### Examples

```
qits maintenance bump 6f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f
```

- Run it again to follow a job: REQUESTED and RUNNING are not finished.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

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

## qits events query

Print the qits-events domain events that already happened in a window of time, oldest first, from the events log. It answers with what exists when it is called and never waits for a new event; `qits events` is the live form.

The window is --since to --until, both inclusive; --since is an hour ago and --until is now unless given. When more than --limit events fall in it, the newest --limit are kept. The text form starts with a line giving the window as two absolute instants, then prints one JSON object per event, the same line `qits events` prints, and ends with a line saying so when the answer was cut. -o json prints one object: {"events": [...], "truncated": true|false, "window": {"since": ..., "until": ...}}.

```
qits events query [--events-url <url>] [--filter <names>] [--limit <n>] [--output <text|json>] [--since <time>] [--until <time>]
```

| Name | What it does |
|---|---|
| `--events-url <url>` | The events service's base URL, without /events. Default: QITS_EVENTS_URL, else the session's idp address with `idp` swapped for `events`. |
| `--filter <names>` | Which events: exact event names, comma-separated (for example BuildSuccessful,BuildFailed), or * for every event. No patterns. Default: *. |
| `--limit <n>` | At most this many, 1 to 1000. When more match, the newest are kept. Default: 100. |
| `-o, --output <text\|json>` | text: the window, then one line per event (the default). json: one object with the events, whether the answer was cut, and the window. |
| `--since <time>` | Where the window starts, inclusive: an ISO-8601 instant (2026-10-01T18:00:00Z) or a time back from now, a whole number with s, m, h or d (90s, 15m, 2h, 7d). Default: 1h. |
| `--until <time>` | Where the window ends, inclusive, in the same two forms. A time after now is taken as now. Default: now. |

### Examples

```
qits events query --filter=BuildFailed --since 1d
qits events query --filter=ReleaseRequestChanged --since 2026-10-01T18:00:00Z --until 2026-10-01T19:00:00Z
qits events query --since 15m -o json | jq -r '.events[].name'
```

--filter takes exact event names, as `qits events` does. A pattern such as Build* is refused.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly (a pattern in --filter, a time that cannot be read, --since after --until, a --limit outside 1..1000), not signed in, or the session ended.

## qits observe

Print what qits-observability takes in (logs, spans with their events, metrics) as it arrives. Use it to watch a service's errors or to follow one trace live. The service applies the filters and sends only the records that match.

Each --filter is one group of conditions, separated by spaces, that must all hold. A record that fits any group is printed. Live only: records that arrive while it reconnects are missed. Notes go to stderr. Stops on SIGINT or SIGTERM.

Conditions: F=V equal and F^=V starts with (both match case: status=ERROR, not status=error), F~V contains (any case), F? present, !F absent, level>=V severity at or above V (TRACE, DEBUG, INFO, WARN, ERROR, FATAL or 1-24).

Fields: kind service trace span level body name status event attr.<key> resource.<key>. attr.<key> is the record's own attribute, resource.<key> its resource's. Quote a value that holds spaces: body~"connection refused".

```
qits observe [--filter <conditions>...] [--observability-url <url>] [--output <text|json>]
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

## qits observe query

Print the logs, spans and metrics qits-observability still holds for a window of time, oldest first, filtered as `qits observe` filters. It answers with what the service holds when it is called and never waits for a new record; `qits observe` is the live form.

The window is --since to --until, both inclusive; --since is an hour ago and --until is now unless given. A record is in the window by its own time: a log's time, a span's start. A metric keeps only its latest point, so a metric is found only when that point is in the window. When more than --limit records match, the newest --limit are kept.

The service keeps a bounded buffer, not a history. When the window starts before the oldest record it still holds, a note on stderr says so: an empty answer there is not proof that nothing happened.

The text form starts with a line giving the window as two absolute instants, then prints one line per record, the line `qits observe` prints, and ends with a line saying so when the answer was cut. -o json prints one object: {"records": [...], "truncated": true|false, "window": {"since": ..., "until": ...}}, each record the frame the live stream sends.

--filter takes the conditions and fields `qits observe` takes; see `qits observe --help`.

```
qits observe query --filter <conditions>... [--limit <n>] [--observability-url <url>] [--output <text|json>] [--since <time>] [--source <key>] [--until <time>]
```

| Name | What it does |
|---|---|
| `--filter <conditions>...` | Required. One group of conditions, for example 'kind=log level>=ERROR'. Give it again for another group. '*' matches every record. |
| `--limit <n>` | At most this many, 1 to 1000. When more match, the newest are kept. Default: 100. |
| `--observability-url <url>` | The observability service's base URL, without /observability. Default: QITS_OBSERVABILITY_URL, else the session's idp address with `idp` swapped for `observability`. |
| `-o, --output <text\|json>` | text: the window, then one line per record (the default). json: one object with the records, whether the answer was cut, and the window. |
| `--since <time>` | Where the window starts, inclusive: an ISO-8601 instant (2026-10-01T18:00:00Z) or a time back from now, a whole number with s, m, h or d (90s, 15m, 2h, 7d). Default: 1h. |
| `--source <key>` | Only this source's records: a key such as _service/qits-ci. Default: every source. |
| `--until <time>` | Where the window ends, inclusive, in the same two forms. A time after now is taken as now. Default: now. |

### Examples

```
qits observe query --filter 'kind=log level>=ERROR' --since 2h
qits observe query --filter 'trace=4bf92f3577b34da6a3ce929d0e0e4736' --since 1d --limit 1000
qits observe query --filter 'kind=span status=ERROR' --source _service/qits-ci -o json
```

- --source takes a key as the service's telemetry sources list it (_service/<name>, a repository or a workspace). Without it every source is searched.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly (a --filter that cannot be read or that the service refused, a time that cannot be read, --since after --until, a --limit outside 1..1000), not signed in, or the session ended.

## qits git-login

Sign this workstation in for Git pushes to the platform's git host, through the browser. Run it once; afterwards Git gets its token from `qits git-credential`.

The sign-in may push branches under refs/heads/external/ and nothing else. It is stored in $XDG_CONFIG_HOME/qits/git.json, apart from the session of `qits login`.

```
qits git-login [--audience <audience>] [--configure] [--git-host <url>] [--idp-url <url>] [--no-browser] [--timeout <seconds>]
```

| Name | What it does |
|---|---|
| `--audience <audience>` | The audience of the token. Default: qits-platform, the one audience every platform service accepts. |
| `--configure` | Also run the two `git config --global` commands that make Git ask `qits git-credential` for this git host (and no other). |
| `--git-host <url>` | The git host's address. Default: QITS_GIT_HOST_URL, else the idp's host with `idp` swapped for `githost` (https://githost.qits.wohlben.eu). |
| `--idp-url <url>` | The idp's public base URL. Default: QITS_IDP_URL, else the idp of the `qits login` session, else https://idp.qits.<QITS_DOMAIN>/idp, else https://idp.qits.wohlben.eu/idp. |
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
- `2` Used wrongly, or the git host cannot be worked out.

## qits git-credential

Git's credential helper for the platform's git host. Git runs it; a person does not.

On a workstation `qits git-login` sets it up: get prints that sign-in's access token, refreshing it first when needed. store is ignored. erase drops the cached access token and keeps the sign-in.

Inside the platform there is no sign-in and none is needed: get answers the injected git host (QITS_GIT_AUTH_HOST) from the container's own credential, and answers no other host. store and erase do nothing there, and no file is written.

With a workspace token (QITS_TOKEN) get answers https://githost.qits.<QITS_DOMAIN> with that token, and answers no other host; store and erase do nothing and no file is written.

```
qits git-credential <get|store|erase>
```

| Name | What it does |
|---|---|
| `<get\|store\|erase>` | What Git asks for. |

### Examples

```
git config --global --get-all credential.https://githost.qits.wohlben.eu.helper
```

The example shows the workstation setup. Do not run `get` yourself: it prints a token on stdout, because that is Git's helper protocol.
Inside the platform Git is set up for every host at once, so the injected host is checked before anything is minted: the platform's bearer is never handed to a host someone else named.

### Exit codes

- `0` Done. For a host without a sign-in, and inside the platform for any host but the injected one, it prints nothing and Git asks its other helpers. A credential that cannot be had says why on stderr and still exits 0, so Git carries on.
- `1` The sign-in file cannot be read or written.
- `2` No action named.

## qits mcp-credential

The headers helper of Claude's MCP entry for the qits MCP server. Claude runs it; a person does not.

Prints {"Authorization":"Bearer <token>"} on stdout: inside the platform the container's own credential, on a workstation the session of `qits login`, refreshed first when needed, and with a workspace token (QITS_TOKEN) that token as it is.

Interim: once every workspace has a long-lived token of its own (qits-684) the entry carries it as a plain header and this command goes.

```
qits mcp-credential
```

### Examples

```
"qits": {"type": "http", "url": "http://dev-qits-platform-access-mcp-service:8080/mcp", "headersHelper": "qits mcp-credential"}
```

The example is the entry in Claude's MCP configuration. Do not run it yourself: it prints a token on stdout, because that is what a headers helper does.

With QITS_TOKEN set the header carries that token, so an entry rendered with this helper keeps working on a runner node; the url there is the public vhost, not the wire alias.

### Exit codes

- `0` Printed.
- `1` No token to be had (not signed in, the session ended, or the idp refused or cannot be reached): one line on stderr and nothing on stdout, so Claude connects without the header and is told 401.

## qits artifacts

The platform's artifacts store. `qits artifacts publish` is a CI release step's publish client: a maven module, an npm package, a contract package, an sbom, a docs bundle or a daemon binary.

## qits artifacts publish

Publish to qits-artifacts from a CI release step: a maven module or an npm package (built, hashed and uploaded here, optionally only if its content changed), a contract package, an sbom, a docs bundle, or a daemon binary. This is the qits-publish client.

Every publish follows one rule, for every surface: absent, PUT it and say what landed; occupied with the same bytes, say so and succeed (a retried or replayed step must go green); occupied with different bytes, fail naming both digests (a coordinate must never come to mean two things); occupied and not comparable, warn and skip.

### Notes

- This command never signs in and never reads or writes what `qits login` keeps: it runs in a CI step container with no person. `qits login` and `qits git-login` do not apply to it.
- Only a CI run may publish to qits-artifacts, so every request carries a bearer. The token comes from the first of these the environment has: QITS_PUBLISH_TOKEN_COMMAND (an executable that prints a fresh token, re-run for every request), QITS_PUBLISH_TOKEN (a token), or QITS_COMMISSIONED_CLIENT_ID and QITS_COMMISSIONED_CLIENT_SECRET (minted at the idp). With none of them the request still goes out, unauthenticated, and the store answers 401: the store decides who may write, not this client.
- Started under the name `qits-publish` (its own file, or a symlink to `qits`), any command runs exactly as `qits artifacts publish <command>`: `qits-publish sbom submit ...` behaves as `qits artifacts publish sbom submit ...`. A hand-written pipeline may still call it that way.
- The store is https://registry.qits.$QITS_DOMAIN and the Maven Central cache https://mirror.qits.$QITS_DOMAIN; QITS_DOMAIN defaults to wohlben.eu. No other variable names an address: the hosted npm and maven repositories, the docs, sbom and daemon stores are fixed paths under the store's host.

### Exit codes

- `0` Published, or already published with the same bytes.
- `1` Refused, and re-running will not help: invalid arguments, a 4xx, or the coordinate already holds different bytes.
- `2` Could not ask, or could not be answered: the store unreachable, an I/O failure, or a 5xx. A step may retry a 2 and must not retry a 1.

## qits artifacts publish maven

Publish one maven module of the reactor at the working directory: its jar and a flattened pom, with the content hash. Prints exactly one line on stdout: `published <version>` or `unchanged since <version>`; the reasoning goes to stderr.

The pom uploaded is generated from the module's effective model: no parent, every version resolved, and only external dependencies. Every dependency on another module of the same reactor is bundled into the jar (classes, resources, META-INF/services merged; any other path present twice with different bytes is refused), unless --link names it: a linked sibling stays a pom dependency at the version decided for it in this release. A pom-packaging module is a product (a parent or BOM another repository consumes) and keeps its dependencyManagement.

With --if-changed the module is uploaded only when its content hash differs from the newest published version's. No published version, no stored hash, or another algorithm version all count as changed. A re-run at a version already published answers `published <version>` without uploading.

```
qits artifacts publish maven [--if-changed] [--include <glob>...] [--link <groupId:artifactId>...] [--name <groupId:artifactId>...] [--path <dir>...] [--root <dir>...] [--sbom <file>...] [--version <version>...]
```

| Name | What it does |
|---|---|
| `--if-changed` | Upload only when the content differs from the newest published version. |
| `--include <glob>...` | Hash only the jar entries matching one of these globs. Narrows the hash, never what is uploaded. Repeatable. |
| `--link <groupId:artifactId>...` | A reactor sibling that stays a pom dependency instead of being bundled. Repeatable. |
| `--name <groupId:artifactId>...` | The coordinate the entry declares. |
| `--path <dir>...` | The module's directory, relative to the reactor root. Default: `.`. |
| `--root <dir>...` | The reactor root, whose pom.xml lists the modules. Default: the working directory. |
| `--sbom <file>...` | The module's CycloneDX document, hashed with the content. Required with --if-changed. |
| `--version <version>...` | The release version. |

### Examples

```
qits artifacts publish maven --name eu.wohlben.qits:qits-registries-npm --path npm --sbom npm/target/sbom.json --link eu.wohlben.qits:qits-blobstore --if-changed --version 2026.1002.1
```

### Exit codes

- `0` Published, already published at this version with the same content, or unchanged.
- `1` Refused: bad arguments, a module that is not --name at --version, a bundling conflict, a 4xx, or this version already holds other content.
- `2` Could not ask: the store unreachable, an I/O failure, a 5xx, or an unreadable answer; never read as unchanged.

## qits artifacts publish npm

Publish one npm package from its built directory: the tarball is packed here (deterministic: sorted entries, fixed mode, owner and mtime) and PUT with the registry's publish document. Prints exactly one line on stdout: `published <version>` or `unchanged since <version>`; the reasoning goes to stderr.

package.json in --path must name --name at --version and must not be private. Every regular file is packed except node_modules/, .git/, .npmrc and the lockfiles, narrowed by "files" when the manifest has it (package.json, README* and LICENSE* always go in), else by a root .npmignore (ng-packagr writes one): its patterns drop files, package.json, README* and LICENSE* at the root still go in, and the .npmignore itself is never packed. With "files" the .npmignore is not read. A negation (!pattern) or a .npmignore below the root is refused.

A version below the registry's latest is a replay: it is published under the tag `replay` and leaves the real tags alone. Otherwise the `main` dist-tag is moved onto the version after the publish, on a re-run too.

With --if-changed the package is uploaded only when its content hash differs from the newest published version's (by version order, never the latest tag). The manifest's version field is not part of the hash.

```
qits artifacts publish npm [--if-changed] [--include <glob>...] [--name <package>...] [--path <dir>...] [--sbom <file>...] [--version <version>...]
```

| Name | What it does |
|---|---|
| `--if-changed` | Upload only when the content differs from the newest published version. |
| `--include <glob>...` | Hash only the files matching one of these globs (paths inside the package). Narrows the hash, never what is uploaded. Repeatable. |
| `--name <package>...` | The npm package name. |
| `--path <dir>...` | The built package's directory, holding its package.json. Default: `.`. |
| `--sbom <file>...` | The package's CycloneDX document, hashed with the content. Required with --if-changed. |
| `--version <version>...` | The release version. |

### Examples

```
qits artifacts publish npm --name @qits/ui-components --path dist/qits-spa-ui-components --sbom sbom.json --if-changed --version 2026.1002.1
```

### Exit codes

- `0` Published, already published at this version with the same content, or unchanged.
- `1` Refused: bad arguments, a package.json that is not --name at --version, an unsupported .npmignore, a 4xx, or this version already holds other content.
- `2` Could not ask: the registry unreachable, an I/O failure, a 5xx, or an unreadable answer; never read as unchanged.

## qits artifacts publish npm plan

Retired by `qits artifacts publish npm`, which decides, builds and uploads in one call; kept until the build-only npm-library archetype is live everywhere.

Decide, in one word on stdout, what to do with package@version: publish (the ordinary case), skip (this exact version is already there; versions are immutable), or publish-replay (this version is below the registry's latest, so it must take a throwaway tag rather than move latest backwards).

Only the word goes to stdout; the reasoning goes to stderr, so `plan=$(qits artifacts publish npm plan ...)` captures just the word.

```
qits artifacts publish npm plan [--package <name>...] [--version <version>...]
```

| Name | What it does |
|---|---|
| `--package <name>...` | The npm package name. |
| `--version <version>...` | The version. |

### Examples

```
plan=$(qits artifacts publish npm plan --package @qits/ui-components --version 2026.906.1)
```

### Exit codes

- `0` Decided (the word is on stdout).
- `1` Refused: bad arguments, or a 4xx.
- `2` Could not ask: the registry unreachable, an I/O failure, or a 5xx; never read as "publish".

## qits artifacts publish npm dist-tag

Point a dist-tag at a version. Always run, never guarded behind whether this run's publish happened: moving a tag onto the version it already names costs one request and succeeds.

```
qits artifacts publish npm dist-tag [--package <name>...] [--tag <tag>...] [--version <version>...]
```

| Name | What it does |
|---|---|
| `--package <name>...` | The npm package name. |
| `--tag <tag>...` | The dist-tag to move. |
| `--version <version>...` | The version. |

### Examples

```
qits artifacts publish npm dist-tag --package @qits/ui-components --version 2026.906.1 --tag main
```

### Exit codes

- `0` The tag now names that version.
- `1` Refused: bad arguments, or a 4xx (for example a backwards move of latest).
- `2` Could not ask: the registry unreachable, an I/O failure, or a 5xx.

## qits artifacts publish contract

Pack a provider's golden masters or a consumer's pacts from a directory into one package (a maven jar or an npm tarball), and publish it if its content changed. Prints exactly one line on stdout: `published <version>` or `unchanged since <version>`.

The packages are built deterministically: the same tree gives the same bytes. Inside the package the files sit under golden-masters/ or pacts/, by --kind, whatever --from is called; the jar carries directory entries, because a class-path pact loader asks for the directory. Every contract package is if-changed, and decides on its own: packages built from the same tree agree without any link between them.

Golden masters pack the whole --from tree. Pacts pack only the top-level files of --from named <consumer>_<provider>.json for the --provider given (both repository names), so one flat pacts/ directory serves every provider.

--name is the coordinate qits-ci derived from the contracts: section; this command does not derive coordinates.

```
qits artifacts publish contract [--application <application>...] [--ecosystem <maven|npm>...] [--from <dir>...] [--kind <golden-masters|pacts>...] [--name <coordinate>...] [--provider <repository>...] [--version <version>...]
```

| Name | What it does |
|---|---|
| `--application <application>...` | The application whose contracts these are; named in the package's description. |
| `--ecosystem <maven\|npm>...` | Which package to build. |
| `--from <dir>...` | The tree to pack. |
| `--kind <golden-masters\|pacts>...` | What the tree is. |
| `--name <coordinate>...` | groupId:artifactId for maven, the package name for npm. |
| `--provider <repository>...` | The provider a pact is with, by repository name: the pacts packed are --from's *_<provider>.json. Required for pacts, refused for golden masters. |
| `--version <version>...` | The release version. |

### Examples

```
qits artifacts publish contract --kind golden-masters --ecosystem maven --name eu.wohlben.qits:qits-projects-golden-masters --application qits-projects --from golden-masters/ --version 2026.1002.1
qits artifacts publish contract --kind pacts --ecosystem maven --name eu.wohlben.qits:qits-workspaces-service-pacts-qits-projects-service --application qits-workspaces --provider qits-projects-service --from pacts/ --version 2026.1002.1
```

### Exit codes

- `0` Published, already published at this version with the same content, or unchanged.
- `1` Refused: bad arguments, an empty --from (for pacts: no *_<provider>.json in it), a 4xx, or this version already holds other content.
- `2` Could not ask: the store unreachable, an I/O failure, a 5xx, or an unreadable answer; never read as unchanged.

## qits artifacts publish contract-docs

Publish the @contracts/<application> docs bundle when at least one golden-masters package is at --version: the tree under golden-masters/, plus contracts.json listing every --package with its newest version and whether that is this release. Prints exactly one line on stdout: `published <version>` or `unchanged` (no package moved; the docs store keeps the previous bundle).

Run it after the contract packages are published: a package with no version at all is a refusal, not a state.

```
qits artifacts publish contract-docs [--application <application>...] [--from <dir>...] [--meta <key=value>...] [--package <ecosystem=coordinate>...] [--version <version>...]
```

| Name | What it does |
|---|---|
| `--application <application>...` | The provider application; the site is @contracts/<application>. |
| `--from <dir>...` | The golden-masters tree. |
| `--meta <key=value>...` | A metadata header, sent as X-Artifacts-Meta-<key>. Repeatable. |
| `--package <ecosystem=coordinate>...` | A golden-masters package, e.g. npm=@qits/projects-golden-masters. Repeatable. |
| `--version <version>...` | The release version. |

### Examples

```
qits artifacts publish contract-docs --application qits-projects --from golden-masters/ --package maven=eu.wohlben.qits:qits-projects-golden-masters --package npm=@qits/projects-golden-masters --version 2026.1002.1 --meta git.commit.hash=deadbeef
```

### Exit codes

- `0` Published, already published, or unchanged.
- `1` Refused: bad arguments, a package with no published version, or a 4xx.
- `2` Could not ask: the store unreachable, an I/O failure, or a 5xx.

## qits artifacts publish sbom

An SBOM: submit a document that already exists, or build one from a Dockerfile's FROM lines.

## qits artifacts publish sbom submit

Publish a CycloneDX document at (packageType, name, version).

```
qits artifacts publish sbom submit [--file <path>...] [--name <name>...] [--type <npm|maven|docker|daemon>...] [--version <version>...]
```

| Name | What it does |
|---|---|
| `--file <path>...` | The CycloneDX document to publish. |
| `--name <name>...` | The package name. |
| `--type <npm\|maven\|docker\|daemon>...` | The package type the sbom store files this under. |
| `--version <version>...` | The version. |

### Examples

```
qits artifacts publish sbom submit --type docker --name qits/qits-ci --version 2026.906.1 --file sbom.json
```

### Exit codes

- `0` Published, or already published with the same bytes.
- `1` Refused: bad arguments, a 4xx, or the coordinate already holds different bytes.
- `2` Could not ask: the store unreachable, an I/O failure, or a 5xx.

## qits artifacts publish sbom from-dockerfile

Build a CycloneDX document from a Dockerfile's FROM lines: one component per distinct upstream image. Publishes nothing itself: write the file with -o, then `sbom submit` it.

null-style variables in a FROM resolve from the file's own ARG default, or from --build-arg, in the precedence a real build has.

```
qits artifacts publish sbom from-dockerfile [--build-arg <NAME=value>...] [--dockerfile <path>...] [--output <path>...] [--root-name <name>...] [--root-version <version>...]
```

| Name | What it does |
|---|---|
| `--build-arg <NAME=value>...` | A build argument, for a FROM that names one. Repeatable. |
| `--dockerfile <path>...` | A Dockerfile to read FROM lines from. Repeatable. Default: Dockerfile. |
| `-o, --output <path>...` | Where to write the document. Required, exactly once. |
| `--root-name <name>...` | The image's own name, for the document's root component. |
| `--root-version <version>...` | The image's own version. |

### Examples

```
qits artifacts publish sbom from-dockerfile --root-name qits/qits-ci --root-version 2026.906.1 -o sbom.json
qits artifacts publish sbom from-dockerfile --root-name x --root-version 1 --dockerfile a.Dockerfile --dockerfile b.Dockerfile --build-arg BASE=alpine:3.20 -o sbom.json
```

### Exit codes

- `0` Wrote the document.
- `1` Refused: bad arguments, a Dockerfile with no FROM line, or a variable nothing resolves.

## qits artifacts publish docs

A documentation bundle, at (site, version).

## qits artifacts publish docs submit

Publish a documentation bundle at (site, version).

--openapi publishes one OpenAPI document instead of an archive: it is packed as the bundle's only entry, openapi.yml (or openapi.json for a .json file). That is the platform's @apidocs publish.

The store explodes the archive into per-file blobs and keeps no archive digest, so an occupied version cannot be verified: it is skipped, with a WARN naming the degradation, rather than reported as a plain success.

```
qits artifacts publish docs submit [--archive <tgz>...] [--meta <key=value>...] [--openapi <file>...] [--site <name>...] [--version <version>...]
```

| Name | What it does |
|---|---|
| `--archive <tgz>...` | The gzipped tar archive to publish. Exclusive with --openapi. |
| `--meta <key=value>...` | A metadata header, sent as X-Artifacts-Meta-<key>. Repeatable. |
| `--openapi <file>...` | An OpenAPI document (.yml, .yaml or .json) to publish as the whole bundle. Exclusive with --archive. |
| `--site <name>...` | The docs site's name. |
| `--version <version>...` | The version. |

### Examples

```
qits artifacts publish docs submit --site @apidocs/qits-ci --version 2026.906.1 --archive apidocs.tgz --meta git.commit.hash=deadbeef
qits artifacts publish docs submit --site @apidocs/qits-projects --version 2026.1002.1 --openapi docs/openapi.yml
```

### Exit codes

- `0` Published, or already published (see above: not verified in that case).
- `1` Refused: bad arguments, or a 4xx that is not the store's "already there".
- `2` Could not ask: the store unreachable, an I/O failure, or a 5xx.

## qits artifacts publish daemon

A daemon binary, at (name, version).

## qits artifacts publish daemon submit

Publish a daemon binary at (name, version).

Daemon versions are immutable: a re-publish always answers 409, even for identical bytes. The stored digest decides whether that is a re-fire of a run that already succeeded, or two builds claiming one version.

```
qits artifacts publish daemon submit [--file <bin>...] [--name <name>...] [--version <version>...]
```

| Name | What it does |
|---|---|
| `--file <bin>...` | The binary to publish. |
| `--name <name>...` | The daemon's name. |
| `--version <version>...` | The version. |

### Examples

```
qits artifacts publish daemon submit --name qits-platform-access-cli --version 2026.906.1 --file target/qits
```

### Exit codes

- `0` Published, or already published with the same bytes.
- `1` Refused: bad arguments, a 4xx, or the coordinate already holds different bytes.
- `2` Could not ask: the store unreachable, an I/O failure, or a 5xx.

## qits artifacts publish exists

Ask whether a coordinate is already published: daemon, docs, npm or sbom.

An sbom coordinate has two name parts, written <packageType>/<packageName>, for example docker/qits/qits-ci. A step that read an unreachable store as "absent" would republish on every outage, and one that read it as "present" would skip a publish that never happened, so a third exit code says "could not ask" instead.

```
qits artifacts publish exists [<type> <name> <version>]
```

| Name | What it does |
|---|---|
| `<type> <name> <version>` | The type (daemon, docs, npm or sbom), the name, and the version, in that order. |

### Examples

```
qits artifacts publish exists daemon qits-platform-access-cli 2026.906.1
qits artifacts publish exists sbom docker/qits/qits-ci 2026.906.1
qits artifacts publish exists npm @qits/ui-components 2026.906.1
```

### Exit codes

- `0` Published.
- `1` Not published, or the arguments are wrong.
- `2` Could not ask: the store unreachable, an I/O failure, or a 5xx.

## qits tui

Pick a command instead of remembering it: an interactive screen over every qits command.

The upper half is the picker: the commands, then the options of the one chosen, with the values the platform can offer. The command being built is shown between the halves, so the screen also teaches the command line. The lower half is the output of what was run.

```
qits tui
```

### Examples

```
qits tui
```

- Keys: arrows or k/j move, Enter chooses, Esc goes back, / filters, Ctrl-R runs, Ctrl-C stops a run, Ctrl-P shows this session's history, q quits.
- Needs an interactive terminal of at least 80x24. In a pipe or a CI step it says so and exits 2.
- It runs a command by starting this same binary again, so a run behaves exactly as it does when typed.

### Exit codes

- `0` Done.
- `1` Unused.
- `2` There is no interactive terminal, or it is smaller than 80x24.
