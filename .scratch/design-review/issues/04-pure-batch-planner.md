# 04: Extract a pure batch planner

**What to build:** The batch write splits into a pure planning step and a thin effect step. Today one function reads the database, resolves ids, matches tags, folds the updates, builds the transaction data, and writes, all together. The planning half is already a pure function of the database snapshot and the batch. Because the write is welded on, it cannot run without a live connection. This extracts a pure planner. The planner takes a database value and a batch. It returns either a success plan or the failing operations. A success plan carries the new ids and the transaction data. The batch apply function calls the planner, then writes once on success. The one-key wrapper map around the folded update fields is removed while there.

**Blocked by:** 01 (the planner builds the failure entries, so the error-code shape is settled first).

**Status:** resolved (commit 72be581)

- [x] A pure function takes a database value and a batch and returns a result value. It takes no connection argument and performs no write.
- [x] On success the result carries the new create ids in create order and the transaction data.
- [x] On failure the result carries only the failing operations, each with its group name and its index in that group.
- [x] The batch apply function calls the planner and writes once on success, and writes nothing on failure.
- [x] The folded update fields are held as a plain fields map, with no single-key wrapper around them.
- [x] A test exercises the planner against a database value alone and asserts the plan, with no live write in the test.
- [x] The batch route still returns 200 with ids and an applied count on success, and 422 with the failing operations on rejection.
