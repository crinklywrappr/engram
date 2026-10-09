# 15: Report freshness aggregates on `/stats`

**What to build:** `/stats` reports three freshness aggregates across the caller's memories. The first is the plain mean freshness. It weights every memory equally. The second is the use-weighted mean freshness. It weights each memory by its recent recall count. The facts the caller loads most shape this number most. The third is the hot-and-stale fraction. This is the share of memories that are both hot and stale. A hot memory has a recent recall count at or above 0.5. That is about one recall within the last half-life, or more. A stale memory sits in the stale band from ticket 14. Each of the three values is a single decimal truncated to four decimal places. A higher mean means fresher. A higher hot-and-stale fraction means more of the store is used but overdue. When no memory has a recent count, the use-weighted mean falls back to the plain mean. The computation covers only the caller's own memories. The three aggregates and the conforming fraction from ticket 09 share one pass over the memories.

**Blocked by:** 13 (Record a recall count for each memory) and 14 (Add the per-memory freshness flag and the confirm route).

**Status:** done

- [x] `/stats` reports the plain mean freshness, with every memory weighted equally.
- [x] `/stats` reports the use-weighted mean freshness, weighted by the recent recall count.
- [x] `/stats` reports the hot-and-stale fraction.
- [x] A hot memory has a recent recall count at or above 0.5.
- [x] A stale memory sits in the stale band from ticket 14.
- [x] Each value is a single decimal truncated to four decimal places.
- [x] When no memory has a recent count, the use-weighted mean falls back to the plain mean.
- [x] The computation covers only the caller's own memories.
- [x] The three aggregates and the conforming fraction share one pass.
- [x] The `/stats` response schema names the three new fields.
