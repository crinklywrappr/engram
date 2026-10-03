# 03: Diagnose slow eval and brainstorm fixes

**What to build:** A written diagnosis of why the project takes a while to eval, plus ranked candidate fixes. This is a spike. It measures where the time goes, names the dominant cost, and records options with their trade-offs. It ships no production code. The fixes become separate tickets later.

Measure the parts that plausibly dominate: cold REPL require of the main namespaces, AOT compilation, and uberjar boot to a healthy `/healthz`. Datalevin is a heavy dependency, so isolate its require cost from the rest.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] The note records timings for a cold require of the main namespaces.
- [ ] The note records the uberjar boot time to a 200 on `/healthz`.
- [ ] The note isolates the Datalevin require cost from the rest.
- [ ] The note names the dominant cost.
- [ ] The note lists ranked candidate fixes, each with its trade-off.
- [ ] The note lands where the repo keeps such notes, and the ticket names that location.
- [ ] No production code changes land in this ticket.
