# 18b: Repair orphaned `related` after a delete or a `src` change

**What to build:** A delete, or a `src` change, repairs the related space in the same operation. No `related` value is left pointing at a src that no longer has a memory. The repair runs inside the same transaction as the write, so a reader never sees a dangling link. engram repairs the related space for the owning user only, because a related link resolves within one user.

Three write paths trigger the repair: a single delete, a single update that changes `src`, and a batch that holds either. A content-only, tags-only, or related-only update triggers nothing, because it cannot orphan a src. A bare confirm triggers nothing.

The repair is one of three actions, chosen from the current graph:

- A `src` changes from an old value to a new value, and some memory still carries old. engram adds new beside old on every memory whose `related` names old.
- A `src` changes from an old value to a new value, and no memory still carries old. engram replaces old with new on every memory whose `related` names old.
- A delete leaves a src with no memory. engram removes that src from every memory whose `related` names it.

A batch can delete a src and recreate it in one request. The batch repair reflects the net effect of the whole batch, so a src that survives the batch is not treated as orphaned.

**Blocked by:** 18a (Allow a `src` correction).

**Status:** ready-for-agent

- [ ] A single delete repairs the related space in the same transaction as the delete.
- [ ] A single update that changes `src` repairs the related space in the same transaction as the update.
- [ ] A batch that deletes a memory or changes a `src` repairs the related space in the same batch transaction.
- [ ] The batch repair reflects the net effect of the batch, so a src recreated in the same batch is not treated as orphaned.
- [ ] engram repairs the related space for the owning user only.
- [ ] A `src` change to new, with old still present, adds new beside old on every memory whose `related` names old.
- [ ] A `src` change to new, with old gone, replaces old with new on every memory whose `related` names old.
- [ ] A delete that leaves a src with no memory removes that src from every memory whose `related` names it.
- [ ] A content-only, tags-only, or related-only update does no repair, and a bare confirm does none.
- [ ] A reader never sees a dangling related, because the repair commits with the write.
- [ ] Tests cover the add, replace, and delete actions across the single delete, the single update, and the batch path.
