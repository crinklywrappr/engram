# 13: Record a recall count for each memory

**What to build:** Each memory gains its own recall count, the same shape the tag recall count uses. The count is a lifetime total, a recent decay measure, and a `last-recalled` timestamp. The recent measure decays from `last-recalled`, by the recall half-life, the same half-life the tag count uses.

Three attributes hold the count on the memory entity. They are `:memory/recall-lifetime` (long), `:memory/recall-decayed` (double), and `:memory/last-recalled` (instant). Migration 003 adds them. The migration is additive. It adds no full-text attribute, so it triggers no re-index.

A recall updates the count for every memory it returns, including the memories pulled through the related closure. A recall is a call to `/memories/recall/by-tags` or `/memories/recall/by-ids`. For each returned memory, the lifetime gains one, the recent measure decays to now and gains one, and `last-recalled` moves to now. A search does not update the count. A search is discovery, not a load. Only `/memories/recall/*` updates recall counts.

The update runs off the read path, through an asynchronous stat writer. This is a second writer, separate from the tag writer. The two write different entities. The `writer` function is generalized to serve both. A protocol `RecallCountWritable` carries the two behaviors that differ by kind. `->pending` folds recorded ids into the pending map. `->writes` turns the pending map into transaction data. Two records implement it. `TagRecallCount` serves the tag count and `MemoryRecallCount` serves the memory count. The `writer` takes one of these records as its strategy. The system wires two instances, `:engram.stats/tag-writer` and `:engram.stats/mem-writer`, each built from the same `writer` function. The current `record!` becomes `record-pairs!`. A new `record-memories!` records memory ids. One generic `drain!` serves both writers.

The write realizes only the memory ids from the recall stream. The handler taps each delivered memory's id as it streams. It hands the id collection to the memory writer once at the end. The server never holds the full memory maps for the write. A client that disconnects partway still counts the memories already delivered.

The count write sets only the three recall attributes. It never touches `:memory/updated-at`. A recall is not an edit. The recall wire shape is built from an explicit pull pattern that names only id, content, src, related, and tags. The three recall attributes are not in that pattern, so no response exposes the count. The count is stored only. No route reads it. Ticket 15 and ticket 16 read it later.

An existing memory starts at a lifetime of zero and a recent of zero, with no `last-recalled`. There is no backfill. The three attributes are absent until a memory's first recall.

The glossary extends the recall-count, lifetime-count, and recent-count terms to cover a memory, not only a tag. Each definition names both subjects. One line records that only a recall under `/memories/recall/*` increments the count.

The protocol leaves room to grow. A later kind of counted read can add a third record without touching the writer.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] Migration 003 adds `:memory/recall-lifetime`, `:memory/recall-decayed`, and `:memory/last-recalled`.
- [ ] A recall by tags and a recall by ids each update the count for every returned memory, closure included.
- [ ] A search does not update the count.
- [ ] For each returned memory, the lifetime gains one and `last-recalled` moves to now.
- [ ] The recent measure decays to now by the recall half-life, then gains one.
- [ ] The update runs through an asynchronous memory writer, off the read path.
- [ ] The `RecallCountWritable` protocol carries `->pending` and `->writes`, with `TagRecallCount` and `MemoryRecallCount` records.
- [ ] The system wires `:engram.stats/tag-writer` and `:engram.stats/mem-writer` from the same `writer` function.
- [ ] `record!` becomes `record-pairs!`, a new `record-memories!` records ids, and one `drain!` serves both.
- [ ] The handler taps delivered ids through a transducer and records them once, never holding the full maps.
- [ ] The count write never touches `:memory/updated-at`.
- [ ] No response exposes the per-memory count.
- [ ] An existing memory starts at a lifetime of 0, a recent of 0.0, and no `last-recalled`, with no backfill.
- [ ] The glossary extends recall count, lifetime count, and recent count to a memory, and records that only `/memories/recall/*` increments.
- [ ] The tests query the database directly to verify the stored count.
