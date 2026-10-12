# 18d: `/recalls` lists only a pair a live memory carries

**What to build:** Every category:label pair the `/recalls` route lists must sit on at least one of the caller's current memories. A pair whose last memory was deleted or retagged must not appear, and must not return with a stale recall count. Behind the route, a `:stat/*` row for that pair stays in the store. A stat entity is never retracted, so an unused pair leaves a stale row.

engram keeps the route driven by live memories, so a stale stat row never shows. Or it reconciles the stat rows, so the store carries no dead pair. A reproduction comes first. The current `pair-rows` query is driven by live memories. So the first step is to reproduce a dead pair on the route, or to record that only the stored stat row goes stale.

**Result:** The route was already correct, so no route code changed. `pair-rows` is driven by live memories, so `/recalls` lists a pair that a live memory carries, and no other. The reproduction test shows a deleted or retagged pair drops from the catalog. The orphaned `:stat/*` row stays in the store and never surfaces, through the route or through `/stats`. The orphaned stat rows stay ignored. Ticket 24 can clean them during its recall-count rework. The deliverable is the regression test `recalls-excludes-a-pair-no-live-memory-carries`.

**Blocked by:** None (can start immediately). Related to 18c (stat-writer gating) and 24 (decouple recall-count from stats).

**Status:** done

- [x] `GET /recalls` lists only a pair that at least one of the caller's live memories carries.
- [x] Deleting the last memory that carries a pair removes that pair from `/recalls`.
- [x] Retagging away the last use of a pair removes that pair from `/recalls`.
- [x] An orphaned `:stat/*` row for an unused pair is cleaned or stays ignored, and never returns as a count.
- [x] A test covers the delete-last-use case and the retag-last-use case.
