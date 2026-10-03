# 01: Stats reports every pair with a memory count

**What to build:** `/stats` returns one row for every category:label pair on the memories of the caller. A pair with zero recalls appears too. Each row carries the memory count, the lifetime recall count, and the recent decayed count. This closes the cold start, where a never-recalled pair was invisible and the client had no pairs to choose from on a fresh store.

The row becomes a five-element positional tuple: `[category, label, count, lifetime, recent]`. The count is the number of memories that carry the pair for that user. A pair with no recall shows a lifetime of 0 and a recent of 0.0.

**Blocked by:** None (can start immediately).

- [ ] `/stats` lists a pair that sits on a memory but was never recalled. The row shows a count of at least 1, a lifetime of 0, and a recent of 0.0.
- [ ] A recalled pair shows its real lifetime and its decayed recent value.
- [ ] The count equals the number of memories that carry the pair for that user.
- [ ] One user never sees the pairs of another user.
- [ ] The `StatsOut` response schema names the five-element tuple.
- [ ] The engram-recall skill doc and `CONTEXT.md` state the new column order.
- [ ] Tests cover a cold pair, a recalled pair, and the count.
