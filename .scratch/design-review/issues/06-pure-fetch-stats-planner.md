# 06: Extract a pure fetch-stats planner

**What to build:** The recall stats update splits into a pure planning step and a thin write. This mirrors ticket 04 for the stats path. Today one function reads the database, computes each pair's decayed weight, builds the transaction, and writes, all together. The decay math is a pure function of the database snapshot, the pairs, and the current time. Because the write is welded on, it cannot run without a live connection. This extracts a pure planner. The planner takes a database value, the user, the half-life, a collection of pairs with a coalesced count each, and the flush time. It returns one batched transaction for all the pairs. For each pair it reads the current row and decays the stored weight to the flush time. It then adds the count to the lifetime and to the decayed weight. The record function transacts the result.

**Blocked by:** None (can start immediately).

**Status:** resolved (commit 050f230)

- [x] A pure function takes a database value, a collection of pairs with counts, and the flush time. It returns one batched transaction.
- [x] For each pair the planner decays the stored weight to the flush time and adds the count to the lifetime.
- [x] The planner takes no connection argument and performs no write.
- [x] The record function calls the planner and transacts the result.
- [x] A test exercises the planner against a database value alone and asserts the counts. The test performs no live write.
- [x] A pair with a count of one records the same lifetime and decayed values as before.
