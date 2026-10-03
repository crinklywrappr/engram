# 01: Split the stats namespace into core and writer

**What to build:** `engram.stats` holds only the functional core. A new `engram.stats.writer` holds the operational consumer. The writer namespace requires the core namespace. The core namespace never requires the writer. Behavior does not change and the full test suite stays green.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] `engram.stats` keeps `decay-factor`, `plan-fetch`, `by-user`, and the `stats` read projection.
- [ ] `engram.stats.writer` holds the agent, both executors, `writer`, `record!`, `drain!`, `schedule-flush!`, `write-buffer`, `absorb`, and the `closed?` flag.
- [ ] `engram.stats.writer` requires `engram.stats`. The core namespace has no reference back to the writer.
- [ ] System wiring, the handler, and the tests point at the new namespace names.
- [ ] The full test suite passes with no behavior change.
