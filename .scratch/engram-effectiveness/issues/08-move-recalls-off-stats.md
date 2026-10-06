# 08: Move the recall counts off `/stats`

**What to build:** `/stats` no longer returns the recall counts. The recall counts now come only from `/recalls`. The `engram-recall` skill points recall call one at `/recalls`. The `migrate` skill reads its label vocabulary from `/recalls`. Every document that describes the recall counts under `/stats` now names `/recalls`. The `/stats` response schema drops the recalls shape.

**Blocked by:** 07 (Serve the recall counts from a `/recalls` route).

**Status:** ready-for-agent

- [ ] `/stats` no longer carries the recall counts.
- [ ] The `engram-recall` skill reads the recall counts from `/recalls`.
- [ ] The `migrate` skill reads its label vocabulary from `/recalls`.
- [ ] Every document that referenced the recall counts under `/stats` now names `/recalls`.
- [ ] The `/stats` response schema drops the recalls shape.
