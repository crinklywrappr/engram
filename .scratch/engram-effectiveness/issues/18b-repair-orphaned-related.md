# 18b: Repair orphaned `related` after a delete or a `src` change

**What to build:** A delete or a `src` change repairs the related space in the same transaction, and only for a src the write empties. An empty src is one that no memory holds after the write. The repair prevents a dangling link, so no `related` value points at a src with no memory. A `related` link names a src, so a referrer tracks the src name. A member that leaves a src with other memories present just stops being reachable, and no repair runs.

Three write paths can empty a src: a single delete, a single update that changes `src`, and a batch that holds either. A content-only, tags-only, or related-only update triggers nothing. A bare confirm triggers nothing. A rename that leaves the old src populated triggers nothing, so a src swap or a rename cycle makes no edit.

The repair acts only on an emptied src, for the owning user:

- A delete removes the last memory at a src. engram removes that src from every memory whose `related` names it.
- A rename moves the last memory away from a src. engram replaces that src with the src the memory moved to, on every memory whose `related` names it.

A batch reflects the net effect of the whole request. A src deleted and recreated in one batch is not empty, so it draws no repair.

**Blocked by:** 18a (Allow a `src` correction). Done.

**Status:** done

- [x] A single delete that empties a src removes it from every referrer, in the delete transaction.
- [x] A single update that empties a src replaces it with the new src on every referrer, in the update transaction.
- [x] A rename that leaves the old src populated makes no related edit.
- [x] A batch that empties a src repairs the referrers in the one batch transaction.
- [x] A batch that deletes a src and recreates it leaves referrers intact.
- [x] A batch src swap, two renames that trade srcs, makes no related edit.
- [x] engram repairs the related space for the owning user only.
- [x] A content-only, tags-only, or related-only update makes no repair, and a bare confirm makes none.
- [x] A reader never sees a dangling related, because the repair commits with the write.
- [x] Tests cover the delete, the rename, the no-op rename, the swap, the batch, and the per-user cases.
