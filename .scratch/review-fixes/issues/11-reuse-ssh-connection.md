# 11: Reuse the SSH connection with ControlMaster

**What to build:** Repeated `ssh engram …` calls share one persistent SSH
connection, so there is no per-request handshake. This is client-side SSH
configuration only. It keeps engram-proxy and the trusted-header identity
unchanged, and it uses no localhost port.

**Blocked by:** None (can start immediately).

**Status:** resolved (commit 127b7a0)

Design notes:

- Use SSH `ControlMaster auto`, a `ControlPath` socket, and `ControlPersist` (for
  example 10m) on the `engram` host in the client `~/.ssh/config`. The first call
  opens the master. Later calls attach to the socket and skip the handshake.
- Recovery is automatic. When the master socket is dead or stale, the next call
  opens a fresh connection and replaces the socket. There is no port to pick,
  because `ControlMaster` uses a Unix-domain socket file, not a localhost port.
- This adds no local HTTP endpoint. The only consumer is the skill, which calls
  `ssh engram METHOD PATH`.

- [ ] The `engram` host in the client `~/.ssh/config` sets `ControlMaster auto`,
      a `ControlPath` socket, and `ControlPersist`.
- [ ] After the first call, a later `ssh engram …` reuses the master with no new
      handshake. Timing the first call against a second one is a simple way to
      show it.
- [ ] When the master is gone, the next call reconnects on its own. The skill
      does not manage a port and does not clean up a socket.
- [ ] The engram-recall skill and `CLAUDE-nudge.md` document these lines.
- [ ] engram-proxy and the forced-command identity are unchanged.
