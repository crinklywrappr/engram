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
```
