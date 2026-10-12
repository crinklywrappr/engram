# 18c: Serialize writes so a plan reads and commits atomically

**What to build:** A mutating request plans its write from a database snapshot and then commits, as two steps. A concurrent write can land between the snapshot and the commit, so the plan goes stale. The related repair then drops a link to a revived src, or misses a src a concurrent delete just emptied. engram moves the plan inside a Datalevin transaction function, a `[:db.fn/call f]` entry. The read and the commit then run as one atomic unit, and no concurrent writer interleaves.

This covers more than the four handler writes. The confirm route and the per-memory recall writer both stamp a memory entity a concurrent delete can remove. On a lost race each one resurrects a ghost. The confirm re-asserts `:memory/last-confirmed` on a retracted eid. The recall writer upserts `:memory/recall-decayed` by `:memory/id`, so a memory with no `:memory/user` reappears. So create, update, delete, batch, the confirm route, and the per-memory recall writer all plan inside a transaction function. The tag recall writer stays as it is. Its `:stat/*` entities are disjoint, never deleted, and written by one consumer, so it has no race.

Keep `engram.stats.writer` generic and shared. The strategy's `->writes` carries the skip-absent behavior, which drops a recall count for a memory gone by commit time. The shared writer machinery stays generic. The two strategies already differ at `->writes`, so the special behavior has a home.

A prototype measured the race. Eight threads each ran fifty increments through a read-then-transact, and the counter reached 51 of 400. The same work through a transaction function reached 400 of 400. So the transaction function makes the read atomic with the commit, and the plain read-then-transact loses writes under load.

The transaction function returns only tx-data, but engram needs no side channel for the response. It mints each memory id as a UUID before the write, so it knows the created ids without the transact report. On a successful batch the applied count is the creates plus the updates plus the deletes from the request. So the handler returns the ids and the count from what it already holds. The function's database work stays the existence lookups and the repair.

A validation failure throws one `ex-info` inside the function. A batch gathers every failing op first, then throws the whole errors vector, so the 422 stays complete. The throw aborts the transaction, persists nothing, and propagates out of `transact!` with its ex-data intact. The handler wraps `transact!` in a try and branches on the ex-data for the 409 or the 422.

A full map does not break atomicity. LMDB raises a map-full condition, Datalevin closes the write transaction so nothing persists, grows the map, and re-applies the key-value write alone. The function runs once, so a prototype recorded one invocation per `transact!` across a forced grow. No retry re-runs the function on the default connection. These guarantees hold on the default connection. When engram turns on write-ahead logging (WAL) later, test the one-invocation guarantee again.

18c closes the concurrency race alone. It does not close the caller-authored boundary from 18b, where a batch creates a memory related to a src the same batch empties. That memory is not a pre-existing referrer, so the repair does not reach it. Whether to widen the repair stays a separate decision.

The reads now run while the write lock is held, so the lock covers the repair queries too. This is the price of atomicity, and it is acceptable at a personal-memory scale.

The research note at `.scratch/engram-effectiveness/research/18c-surfacing-tx-results.md` records the source citations and the probes.

**Blocked by:** None (can start immediately). Gates the dangling-free guarantee of 18b.

**Status:** done

- [x] create, update, delete, batch, the confirm route, and the per-memory recall writer plan inside a transaction function.
- [x] The tag recall writer stays unchanged, and a note records why it has no race.
- [x] The strategy `->writes` carries the skip-absent behavior, and `engram.stats.writer` stays generic.
- [x] A lost race against a delete resurrects no ghost memory, for the confirm route and the per-memory recall writer.
- [x] The handler returns the created ids from the minted UUIDs and the applied count from the request, with no report-parsing.
- [x] A batch gathers every failing op and throws one `ex-info`, so the 422 lists them all.
- [x] A concurrency test drives engram's real racing operations, not only a counter, and shows no corruption.
- [x] The related repair reads the store and commits atomically, so a concurrent create or delete cannot leave a stale repair.
- [x] A note records the one-invocation guarantee for the default connection, and the need to test it again under WAL.
