# Use engram from Claude Code

This document helps a Claude Code user install the engram skill and connect to a
running engram server. engram is a per-user memory server reached over SSH. When
you finish, Claude loads and stores your memories through the `engram-recall`
skill.

## Prerequisites

You need two things before you start:

- An SSH key pair. The private key stays on your machine.
- A server account. The admin adds your public key to the server
  `authorized_keys` with a `--user` identity, which names your memory store.

Send your public key to the admin. The admin sends back the server host name, the
SSH port, and your `--user` identity.

## Add the SSH host

Add this block to your `~/.ssh/config`, so `ssh engram` resolves:

```
Host engram
    HostName your-server-host
    Port your-server-port
    User engram
    IdentityFile ~/.ssh/your_engram_key
    ControlMaster auto
    ControlPath ~/.ssh/cm-%r@%h:%p
    ControlPersist 10m
```

The login user is always `engram`, the one account on the server. Your memory
identity comes from the `--user` value provided by the admin, not from this login
name.

The `Control` settings reuse one SSH connection. The first `ssh engram` call opens
a connection. Later calls share it with no new handshake. `ControlPersist 10m`
keeps that connection for ten minutes after the last call. There is no port or
socket for you to manage.

## Install the skill

Add the engram marketplace, then install the plugin:

```
/plugin marketplace add crinklywrappr/engram-skills
/plugin install engram@engram
```

Confirm that the `engram-recall` and `migrate` skills load in a new session.

## Test the connection

Run one call to confirm the server answers:

```bash
ssh engram GET /config < /dev/null
```

A `200` means the connection works. The `< /dev/null` matters. The proxy on the
server reads a request body from standard input. A bodyless call like `GET` needs
empty input, or the call waits for input and looks stuck.

## Load memory at session start (optional)

When you mention memory, the installed skill triggers on its own. To make Claude
load memories at the start of every session, add two lines to your user-level
`~/.claude/CLAUDE.md`:

```markdown
## Memory

Memory lives in engram, reached with the `engram-recall` skill. At session start,
invoke `engram-recall` to load the configuration and recall relevant memories, and
use it to store atomic facts during work.
```

This step is optional. Without it, the skill still triggers on a memory request.
With it, Claude loads memory at session start, ahead of your first request.

## The session-start recall backbone

A session starts with two recalls. These two tags cover the memories that matter
before your first request.

The first tag is `scope:global`. It pulls every cross-project fact: who you are,
how you work, and reusable technical knowledge. These facts apply on any project.

The second tag names the current project, such as `project:engram`. It pulls the
facts bound to the project in front of you.

Read the project label set first. Run
`ssh engram GET "/recalls?categories=project" </dev/null`. The response lists
every project label you hold memories for. Pick the label of the current project.
Do not guess a label. A wrong guess recalls nothing.

Treat any further tag as a bonus. Scan another category to find one. Run
`ssh engram GET "/recalls?categories=domain,tech" </dev/null`. The response lists
the labels in use for those categories. Pick a label that fits your task and
recall it.

A tag does not always surface the fact you want. Run a content search as the
recovery path. `POST /memories/search` with a search string finds a memory by its
words.
