# 03: Spike: sweep the skill documents

**What to build:** A review pass over the skill documents that finds stale facts, errors, and wordiness, then applies the safe corrections. The spike produces a short findings note so a later writing ticket knows what changed and why.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] The sweep reads `plugin/skills/engram-recall/SKILL.md`, `plugin/skills/migrate/SKILL.md`, and `plugin/skills/CLAUDE-nudge.md`.
- [ ] The sweep flags any fact that no longer matches the running server, the API, or the deploy. Examples are a route name, a status code, or a header.
- [ ] The sweep flags sentences that cut without losing clarity, and sections that repeat guidance the server already enforces.
- [ ] The sweep applies the safe corrections and records the larger judgment calls in a findings note under `.scratch/docs-shoreup/`.
- [ ] Any edited document respects the simple-english lint hook.
