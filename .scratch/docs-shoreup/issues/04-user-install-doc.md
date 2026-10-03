# 04: User install doc

**What to build:** A document under `doc/` that lets a Claude Code user install the engram skill and reach a running server. The user reads it and knows the prerequisites. The user sets up the SSH key pair and the `~/.ssh/config` entry, installs the plugin through `/plugin`, and confirms the connection.

**Blocked by:** 02 (the plugin packaging decides the install flow the doc describes).

**Status:** ready-for-agent

- [ ] The doc states the prerequisites: an SSH key pair, and a public key the admin already added to `authorized_keys` with a `--user` identity.
- [ ] The doc gives the `~/.ssh/config` host entry. The entry sets `HostName`, `Port 2222`, `User engram`, the `IdentityFile`, and the `ControlMaster`, `ControlPath`, and `ControlPersist` settings for connection reuse.
- [ ] The doc gives the install flow through `/plugin`: add the `crinklywrappr/engram-skills` marketplace, install `engram@engram`, and confirm the skills load.
- [ ] The doc shows the one connection test, `ssh engram GET /config </dev/null`, and names the empty-stdin requirement.
- [ ] The doc explains the two-line user-level `CLAUDE.md` nudge that fires the skill at session start. It marks the nudge optional, because the installed skill triggers reactively on its own.
- [ ] The source file `plugin/skills/CLAUDE-nudge.md` moves into this doc. The published plugin then ships only the two skills.
- [ ] The doc respects the simple-english lint hook.
