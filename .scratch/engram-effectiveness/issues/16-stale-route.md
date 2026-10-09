# 16: Serve stale memories ordered by confirm-priority at /memories/stale

**What to build:** A `GET /memories/stale` route returns the caller's stale memories, ordered by confirm-priority, highest first. The route is a static path, so it resolves ahead of `/memories/:id`, in the same family as `/memories/nonconforming`. The route filters by user. One user never sees another user's memories.

A stale memory sits in the stale band from ticket 14. The band is stale at an age of two freshness half-lives or beyond, measured from the last confirmation.

The confirm-priority of a memory ranks it for reconfirmation, highest first. It is the staleness times the recent recall count. The staleness is one minus the freshness value. The recent recall count is the per-memory decayed count from ticket 13, projected to now by the recall half-life. A fact the caller loads often but leaves unconfirmed rises to the top. A stale fact the caller never loads scores zero and sinks to the bottom. The recent recall count dominates the order. Every candidate is already stale, so the staleness sits in a narrow band. The staleness acts only as a gentle tilt toward the older facts. A tie breaks by `src` then id, so repeated calls return the same order.

The response is an NDJSON stream in the recall wire shape, the shape `->wire` builds: id, content, src, the freshness band, and any tags and related. Every returned memory carries `"freshness":"stale"`. The stream carries no confirm-priority and no score, because the order already expresses the priority. The response is not truncated. A client that wants only the top facts reads the start of the stream and closes the connection.

The route builds the response in two phases, because Datalevin cannot sort by confirm-priority. A memory carries no stored priority. The priority needs the freshness decay and the recent-count projection against the current clock.

Phase one is a datalog query over the caller's candidate memories. It returns only the cheap sort columns: the entity id, the three freshness timestamps, and the two recall columns. It also returns `src` and the memory id for the tiebreak. The query also pre-filters the candidates. The cutoff is `now` minus two freshness half-lives. The query keeps a memory whose `created-at` is at or before the cutoff.

In Clojure the route then works over those rows against one captured clock. It resolves each candidate's effective last-confirmed. It drops the memories outside the stale band. It computes the confirm-priority and sorts the survivors.

Phase two takes the sorted entity ids. It builds a lazy sequence that pulls each full wire memory on demand. The NDJSON writer streams that sequence over the open database snapshot. Only the cheap sort columns are realized in full. The content, tags, and related of the whole stale set never sit in memory at once.

The `created-at` pre-filter is a harmless approximation. It drops no stale memory. The ticket-14 invariant is `created-at <= updated-at <= last-confirmed`. So `created-at` is the floor of the effective last-confirmed. A stale memory has an effective last-confirmed at or before the cutoff. Its `created-at` is therefore also at or before the cutoff, so the filter keeps it. The filter keys on `created-at` rather than `last-confirmed`. A memory that predates the field has no `last-confirmed`, but every memory has a `created-at`. The cost is a few false positives. A fact created long ago but confirmed recently passes the filter, and then the stale test in Clojure drops it.

The route uses two admin half-lives, both from the loaded config. The freshness half-life shapes the staleness and the stale band. The recall half-life shapes the recent recall count. The route reuses `freshness/value` and the stats recent-count projection rather than recompute either.

The per-memory age resolution, `(or last-confirmed updated-at created-at)`, lives today in both `memory`'s wire path and `stats`'s aggregate pass. This route is the third caller, so the resolution is extracted into one shared place and reused here.

**Blocked by:** 13 (Record a per-memory recall count) and 14 (Add the per-memory freshness band and the confirm route). Both are done.

**Status:** done

- [x] `GET /memories/stale` returns the caller's stale memories, as a static path ahead of `/memories/:id`.
- [x] A stale memory sits in the stale band from ticket 14.
- [x] The route orders the memories by confirm-priority, highest first.
- [x] confirm-priority is staleness times the recent recall count, where staleness is one minus freshness.
- [x] The recent recall count is the per-memory decayed count projected to now by the recall half-life.
- [x] A stale memory with no recent recalls scores zero and sorts to the bottom.
- [x] A tie breaks by `src` then id, so the order is deterministic.
- [x] The response is a not-truncated NDJSON stream in the recall wire shape, with no score.
- [x] Phase one is a datalog query that returns only the sort columns, pre-filtered by `created-at`.
- [x] The `created-at` pre-filter drops no stale memory, because `created-at` is the floor of the effective last-confirmed.
- [x] Phase two lazily pulls each full wire memory in sorted order, so the full set never sits in memory at once.
- [x] Another user's memories never appear in the response.
- [x] `CONTEXT.md` defines Confirm priority and Staleness.
- [x] The shared age resolution is extracted into one place and reused, not duplicated a third time.
