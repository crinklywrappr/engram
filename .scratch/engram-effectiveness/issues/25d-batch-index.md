# 25d: Batch index entries

**What to build:** A caller creates, updates, and deletes index entries in one call. `POST /index/batch` takes a grouped map of operations, `{"create":[...], "update":[...], "delete":[...]}`, the same shape as the memory batch (see `docs/adr/0002-batch-request-grouped-map.md`). The batch applies in one atomic transaction and returns the new create ids in create order. migrate uses the create group to write a project's index entries in one call. The memories and the short-term notes exist first. The update and delete groups give the endpoint parity with the single-item routes.

## The operation shapes

- A create carries an entry body: a title, a hook, and one or more requests.
- An update carries an entry id and a full replacement entry body.
- A delete is an entry id.

An index update is a full replace, the `PUT /index/:id` semantics, not a partial patch.

## Repeated ids

The id rules follow the memory batch, with one stricter rule for the update group:

- An id in both the update group and the delete group rejects the whole batch with `conflict`.
- An id that names no entry the caller owns rejects with `not-found`.
- A repeated id in the delete group deduplicates, because a delete is idempotent.
- A repeated id in the update group rejects with `duplicate-id`. A second replace of one entry is a likely mistake, not a patch to stack. Every update op that carries the duplicated id reports the error, by index.

`duplicate-id` is the shared code 25c adds to `engram.errors`. It applies only to the short-term batch and the index batch, not the memory batch. Whichever batch lands first adds the code. When an id is both repeated in update and present in delete, `conflict` wins.

## What the batch drops

The batch runs no related repair, because an index entry has no related graph. It does not validate tags, because an index entry carries no tags. Each operation still validates its own body shape by the entry schema, so a malformed request rejects. Because duplicates reject, at most one update targets each id, so the batch needs no fold. The batch is atomic through one `:db.fn/call` transaction function. A rejected batch returns 422 with a per-operation error list, each `{op, i, code}`.

The batch create and the single `POST /index` share one body schema, so the two validate identically.

**Blocked by:** 25 (the index routes) and 25b (the `by-src` request kind the entries carry).

**Status:** done

- [x] `POST /index/batch` takes `{"create":[...], "update":[...], "delete":[...]}` and returns `{"ids":[...],"applied":n}` in create order.
- [x] A create carries an entry body, an update an entry id and a full replacement body, a delete an entry id.
- [x] An index update is a full replace, not a partial patch.
- [x] The batch applies in one atomic transaction, all operations or none, under the user gate.
- [x] An id in both update and delete rejects the batch with `conflict`.
- [x] A repeated id in the update group rejects with `duplicate-id`, flagged on every occurrence by index.
- [x] A repeated id in the delete group deduplicates to one delete.
- [x] An id that names no entry the caller owns rejects with `not-found`.
- [x] A rejected batch returns 422 with a per-operation error list, each `{op, i, code}`, and writes nothing.
- [x] The batch runs no related repair and does not validate tags. Each op validates its own body shape by the entry schema.
- [x] The tests cover create, update, delete, the conflict case, the duplicate-id case, the delete-dedup case, and per-user isolation.
