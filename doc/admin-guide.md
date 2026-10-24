# engram admin guide

This document helps a competent admin deploy engram. engram runs as one Docker
container. The container runs sshd, the `engram-proxy` forced command, and the
memory server on localhost. Only the SSH port is open.

## Port and filesystem mappings

The container listens for SSH on port 22. The host maps a port to it. The example
below maps host port 2222, because the host sshd owns port 22.

Three mounts feed the container:

- The data directory mounts at `/data`. The server writes the LMDB database here.
- The configuration file mounts at `/config/engram-config.edn`.
- The `authorized_keys` file mounts at `/home/engram/.ssh/authorized_keys`.

A directory mount and a file mount differ. The data mount is a directory. The
configuration mount and the `authorized_keys` mount are file-to-file. A file
mount needs the host file to exist first. If the host file is absent, Docker
creates a directory at that path, and the server or sshd then fails.

The entrypoint chowns `/data` to the engram user at start. The host directory
owner does not matter.

A sample `docker run`:

```bash
docker run --rm --name engram \
  -p 2222:22 \
  -v /srv/engram/data:/data \
  -v /srv/engram/config.edn:/config/engram-config.edn:ro \
  -v /srv/engram/authorized_keys:/home/engram/.ssh/authorized_keys:ro \
  --security-opt seccomp=unconfined \
  ghcr.io/crinklywrappr/engram:latest
```

The `--security-opt seccomp=unconfined` flag is required under an old Docker
seccomp profile. The modern JVM uses the `clone3` syscall. An old profile rejects
it, and the JVM fails to start a thread. The clean alternative is Docker 20.10.10
or later, whose default profile allows `clone3`.

## The running version

The server reports its version two ways. The first runs over the SSH proxy from
a client whose key the admin added. The GET carries no body, so close stdin:

```bash
ssh engram GET /healthz </dev/null
```

The response body carries a `version` field, such as `1.0.42`. The second reads
the image label on the host:

```bash
docker inspect --format '{{index .Config.Labels "org.opencontainers.image.version"}}' ghcr.io/crinklywrappr/engram:latest
```

## The configuration file

The admin writes the configuration file and mounts it. The `ENGRAM_CONFIG`
environment variable names the container path, and the image sets it to
`/config/engram-config.edn`. The section below shows a recommended configuration
to start from.

The file is an EDN map with these keys:

- `:recall-half-life-days` sets the decay for the recent recall count. It defaults to 14.
- `:freshness-half-life-days` sets the decay for a memory's freshness. It defaults to 30.
- `:configurations` is a vector of acceptable configurations.
- `:categories` is optional. It describes each category for the client.

A configuration maps each category to a cardinality. The cardinality shorthand is
`1` for exactly one, `?` for zero or one, `*` for zero or more, and `+` for one or
more. When its `category:label` pairs satisfy any one configuration, a memory is
valid.

Categories are closed to what appears in the configurations. Labels are open
vocabulary. A label must be lowercase kebab-case. The server always imposes `src`
with exactly one, and `related` with zero or more. Do not list `src` or `related`
in the file.

The `:categories` map describes each category. Each entry gives a `:description`
and a vector of `:examples`. A description is at most 256 characters. Each example
is a lowercase kebab-case token. A described category must appear in a
configuration. The server returns this map on `GET /config`, so the client picks
fitting labels.

Example:

```clojure
{:recall-half-life-days 14
 :freshness-half-life-days 30
 :categories
 {"domain" {:description "the broad subject area"
            :examples ["clojure" "databases"]}}
 :configurations
 [{"domain" "+" "project" "*" "tech" "*"}]}
```

## A good configuration

The section above teaches the format. This section teaches judgment. The example
below is a configuration worth starting from, with the reasoning behind its
shape.

A configuration does more than accept or reject a memory. It shapes how recall
works. A memory declares its reach with `scope` and its kind with `type`. Those
two closed tags let a session load every global fact plus the current project,
with no guess. The choices below follow from that goal.

```clojure
{:recall-half-life-days 14
 :freshness-half-life-days 30
 :categories
 {"scope"    {:description "the fact's reach: cross-project or bound to one project"
              :examples ["global" "project"]}
  "type"     {:description "the kind of fact"
              :examples ["user" "feedback" "project" "knowledge" "reference"]}
  "domain"   {:description "the broad subject area"
              :examples ["clojure" "databases" "git-workflow"]}
  "project"  {:description "the project slug the fact belongs to"
              :examples ["engram" "commensura"]}
  "tech"     {:description "a specific tool, library, or artifact"
              :examples ["datalevin" "reitit" "malli"]}
  "activity" {:description "what the fact is about doing"
              :examples ["debugging" "design" "review"]}
  "status"   {:description "a life-cycle state, when one applies"
              :examples ["shipped" "deferred" "resolved"]}}
 :configurations
 [{"scope"    {:cardinality "1" :one-of ["global"]}
   "type"     {:cardinality "1" :one-of ["user" "feedback" "knowledge" "reference"]}
   "domain"   "+"
   "tech"     "*"
   "activity" "*"
   "status"   "?"}
  {"scope"    {:cardinality "1" :one-of ["project"]}
   "project"  "+"
   "type"     {:cardinality "1" :one-of ["user" "feedback" "project" "knowledge" "reference"]}
   "domain"   "+"
   "tech"     "*"
   "activity" "*"
   "status"   "?"}]}
```

### Scope is mandatory

The `scope` category takes a cardinality of `1`. Every memory declares its reach.
A fact is either global or project-bound, and never neither. The `:one-of` list
holds exactly `global` and `project`. The author decides reach at write time.
Recall then leans on this one tag to separate cross-project facts from project
facts.

### Two configurations

The file holds two configurations. The first fits a global-reach fact. It sets
`scope` to `global` and names no `project` category, so a global fact carries no
project tag. A fact about the user or a general technique belongs here. The
second fits a project-bound fact. It sets `scope` to `project` and asks for at
least one `project` tag, written `"project" "+"`. A fact about one codebase
belongs here. A memory is valid once it satisfies either configuration.

### A closed category

The `type` category closes its labels with `:one-of`. The list names the kinds of
fact. A `user` fact describes the user. A `feedback` fact guides how to work. A
`project` fact is specific to one project. A `knowledge` fact is a reusable
technical truth. A `reference` fact points to an external resource. The global
configuration drops `project` from the list, because a global fact names no
project.

A closed, mandatory `type` turns the kind of fact into a reliable filter. A
session can load every `user` and `feedback` fact at the start. The kind sits on
every memory and comes from a fixed set, so the query never misses one. The
cardinality of `1` keeps the kind mandatory. The closed list keeps the filter
meaningful, because a kind never drifts into a near-synonym.

### Close a label set with care

An open category accepts any lowercase kebab-case label. A closed category
accepts only the labels in its `:one-of` list. When a category's values form a
small fixed set, such as `scope` or `type`, close it. A closed set blocks a
near-duplicate label and keeps recall predictable. When a category's values grow
over time, such as `domain`, `tech`, or `project`, leave it open.

### The two half-lives

The `:recall-half-life-days` value shapes the recent recall count. The example
sets it to 14. A pair recalled today counts as one. That one decays to one half
after 14 days. It decays to one quarter after 28 days. A short half-life favors
recent activity. A long half-life keeps older activity in view. Picture the value
as the width of your current focus. The 14-day example tracks a two-week thread of
work.

The `:freshness-half-life-days` value shapes a memory's freshness. The example
sets it to 30. Freshness measures from the last confirmation of a memory. A
memory is fresh under one half-life. It ages between one and two half-lives. It
is stale beyond two. At 30 days a memory stays fresh for 30 days, ages up to 60
days, then reads as stale. A short half-life asks for frequent reconfirmation. A
long half-life lets a fact rest. Picture the value as your reconfirmation rhythm.
The 30-day example asks for a confirmation about once a month.

## Manage users

Each trusted user gets one line in `authorized_keys`. The admin edits this file by
hand and mounts it. Every line starts with a forced command:

```
command="engram-proxy --user alice",no-pty,no-port-forwarding,no-X11-forwarding,no-agent-forwarding ssh-ed25519 AAAA...key... alice@example
```

The `--user` value is the memory identity. The server stores the memories for
that user under it. The trailing comment is the human-readable owner.

To add a user, append one line with the public key and a `--user` identity. To
remove a user, delete the matching line.

The login user is always `engram`. engram is the one unix account in the
container. A client never logs in as the memory identity. The memory identity
comes from the forced command, not from the SSH login name.

The forced command confines each key. Without it, the key gets an interactive
shell. The restrictions after the command harden the key further.

## authorized_keys permissions

sshd reads `authorized_keys` as the engram user, not as root. The file owner and
mode must let engram read it, and strict mode must accept the owner.

Set the owner to `root:root` with mode 644. Strict mode accepts a root-owned file.
The other-read bit lets engram read it. A `root:root` file with mode 600 fails,
because engram cannot read it. The alternative is to set the owner to uid 1001
with mode 600.

## Backups

engram does not manage backups. An admin who wants backups runs a host cron job.

Back up the data directory behind the `/data` mount. The LMDB database lives at
`/data` inside the container. On the host, back up the directory you
mounted at `/data`. The configuration file and the `authorized_keys` file are
plain text the admin already keeps. A backup of the data directory is enough for
the memories.
