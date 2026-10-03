# CLAUDE.md nudge

Paste these two lines into the user-level `~/.claude/CLAUDE.md`, in place of the
prior memory section. They cost almost no tokens. The skill loads only in a
session that uses memory.

```markdown
## Memory

Memory lives in engram, reached with the `engram-recall` skill. At session start,
invoke `engram-recall` to load the config and recall relevant memories, and use
it to store atomic facts during work.
```

This also needs a one-time `~/.ssh/config` entry so `ssh engram` resolves:

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

`ControlMaster` reuses one SSH connection for every `ssh engram` call, so only
the first call pays the handshake. `ControlPersist 10m` keeps that master for ten
minutes after the last call, then closes it. When the master is gone or stale,
the next call opens a fresh one on its own. There is no port to manage, because
`ControlMaster` uses the `ControlPath` socket file, not a localhost port.
