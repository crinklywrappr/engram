# 25c: Batch short-term notes

**What to build:** A caller creates, updates, and deletes short-term notes in one call. `POST /memories/short-term/batch` takes a grouped map of operations, `{"create":[...], "update":[...], "delete":[...]}`, the same shape as the memory batch (see `docs/adr/0002-batch-request-grouped-map.md`). The batch applies in one atomic transaction and returns the new create ids in create order. migrate uses the create group to stage a project's short-term work in one write. The update and delete groups give the endpoint parity with the single-item routes.

## The operation shapes

- A create carries a thought-process and a text, the single-create shape.
- An update carries a note id and the new text.
- A delete is a note id.

## Repeated ids

The id rules follow the memory batch, with one stricter rule for the update group:

- An id in both the update group and the delete group rejects the whole batch with `conflict`.
- An id that names no note the caller owns rejects with `not-found`.
- A repeated id in the delete group deduplicates, because a delete is idempotent.
- A repeated id in the update group rejects with `duplicate-id`. A second update to one note is a likely mistake, not a patch to stack. Every update op that carries the duplicated id reports the error, by index.

`duplicate-id` is a new code in `engram.errors`. It applies only to the short-term batch and the index batch, not the memory batch. When an id is both repeated in update and present in delete, `conflict` wins.

## What the batch drops

The batch runs no related repair, because a short-term note has no related graph. It does not validate tags, because a short-term note carries no tags a configuration governs. Each operation still validates its own shape: a kebab-case thought-process and a non-empty text. Because duplicates reject, at most one update targets each id, so the batch needs no field-fold. The batch is atomic through one `:db.fn/call` transaction function. A rejected batch returns 422 with a per-operation error list, each `{op, i, code}`.

**Blocked by:** None. Short-term memory already exists, so this can start immediately.

**Status:** done

- [x] `POST /memories/short-term/batch` takes `{"create":[...], "update":[...], "delete":[...]}` and returns `{"ids":[...],"applied":n}` in create order.
- [x] A create carries a thought-process and a text, an update a note id and a text, a delete a note id.
- [x] The batch applies in one atomic transaction, all operations or none, under the user gate.
- [x] An id in both update and delete rejects the batch with `conflict`.
- [x] A repeated id in the update group rejects with `duplicate-id`, flagged on every occurrence by index.
- [x] A repeated id in the delete group deduplicates to one delete.
- [x] An id that names no note the caller owns rejects with `not-found`.
- [x] `duplicate-id` is added to `engram.errors` with an inline note that it applies only to the short-term and index batch, not memories.
- [x] A rejected batch returns 422 with a per-operation error list, each `{op, i, code}`, and writes nothing.
- [x] The batch runs no related repair and does not validate tags. Each op validates its own shape.
- [x] The tests cover create, update, delete, the conflict case, the duplicate-id case, the delete-dedup case, and per-user isolation.
