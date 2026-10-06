# 13: Record a recall count for each memory

**What to build:** Each memory gains a recall count, the same shape the pair recall count already uses: a lifetime total and a recent decay measure. The recent measure decays from a `last-recalled` timestamp, as the pair measure decays from its last-request. A recall updates the count for every memory it returns, including the memories pulled through the related closure. For each returned memory, the lifetime total gains one. The recent measure decays to now and gains one. The `last-recalled` timestamp moves to now. A search recall updates the count the same way, for every memory it returns. An existing memory starts at a lifetime of zero, a recent of zero, and no `last-recalled` timestamp. The update runs off the read path, through the asynchronous stat writer, the same component that records the pair counts. The write realizes only the memory IDs from the recall stream, through a transducer. The server never holds the full memory maps for the write. The count is stored only. No route reads it yet. A later ticket uses it to weight the staleness aggregate. The `CONTEXT.md` glossary extends the recall-count, lifetime-count, and recent-count terms to cover a memory, not only a category:label pair.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] Each memory stores a lifetime recall total, a recent decay measure, and a `last-recalled` timestamp.
- [ ] A recall updates the count for every memory it returns.
- [ ] The lifetime total gains one, and the recent measure decays to now and gains one.
- [ ] The update runs through the asynchronous stat writer, off the read path.
- [ ] The write realizes only the memory IDs, through a transducer.
- [ ] No response exposes the per-memory recall count.
- [ ] A search recall updates the count like a tag recall.
- [ ] An existing memory starts at a lifetime of 0, a recent of 0.0, and no `last-recalled`.
- [ ] The `CONTEXT.md` glossary extends recall count, lifetime count, and recent count to a memory.
