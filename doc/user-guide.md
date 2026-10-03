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
    Port 2222
    User engram
    IdentityFile ~/.ssh/your_engram_key
    ControlMaster auto
    ControlPath ~/.ssh/cm-%r@%h:%p
    ControlPersist 10m
```

The login user is always `engram`, the one account on the server. Your memory
identity comes from the `--user` value in the forced command, not from this login
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
ssh engram GET /config </dev/null
```

A `200` means the connection works. The `</dev/null` matters. The proxy on the
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
