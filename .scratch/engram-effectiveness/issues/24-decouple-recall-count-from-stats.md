# 24: Decouple the recall-count model from the stats namespace

**What to build:** This is a stub. It needs a design pass before implementation.

Recall counts once came back from the `/stats` route. Ticket 08 moved them to `/recalls`. The data model and the code still carry the old coupling. The `:stat/*` attributes and the `engram.stats` namespace name the recall counts as "stats". A recall count is its own concept now, served at `/recalls`. Ticket 13 adds a per-memory recall count. That deepens the mismatch.

Reorganize so the recall-count model and its code are named for the recall count. Keep that model separate from the `/stats` aggregate reporting. The `/stats` route reports link density, the conforming fraction, and the freshness aggregates. Those are a different concern from the recall count.

A later design pass settles the exact attribute renames, the namespace split, and any schema or data migration. One known rename is `:stat/last-request`. It comes to parity with `:memory/last-recalled` from ticket 13.

**Blocked by:** 13 (the per-memory recall count lands first, so the reorganization covers both the pair count and the memory count).

**Status:** needs-info

- [ ] A design pass settles the attribute renames and the namespace split.
- [ ] The recall-count model is named for the recall count, not for the `/stats` route.
- [ ] The `/stats` aggregate reporting is separate from the recall-count model.
- [ ] A design pass identifies any schema or data migration.
