# 03: Simplify the batch request to an order-independent grouped map

**What to build:** The batch endpoint takes a simpler request. `POST
/memories/batch` accepts a map with three optional keys: `create`, `update`, and
`delete`. Each key holds a list of payloads. The batch has no global order. This
reshapes the request that ticket 01 shipped. See
`docs/adr/0002-batch-request-grouped-map.md` for the reasoning.

```
{"create": [ {"content":"...","src":"foo","tags":[["domain","clojure"]]} ],
 "update": [ {"id":"<uuid>","tags":[["domain","clojure"]]} ],
 "delete": [ "<uuid>" ]}
```

A `create` payload carries `content`, `src`, and optional `tags` and `related`. An
`update` payload carries `id` and any of `content`, `tags`, `related`. A `delete`
payload is an id string. Malli coercion rejects a malformed request with 400.

Within a group, list order resolves same-target work. Several updates to one id
fold together, and a later entry in the update list wins a same-field tie. A
repeated id in the delete list is deduplicated, the first entry winning, so an
accidental double delete is not an error.

Across groups there is one conflict rule. An id must not appear in both the update
group and the delete group. When it does, the server rejects the whole batch and
names both offending operations.

The rest of ticket 01 holds and does not change. The batch is atomic and
pre-validated (ADR 0001). A create or an update with tags is validated against the
configurations. An update or delete is resolved to one of the caller's own
memories. On success the server returns 200 with the new ids in create order and
an applied count. On any failure the server writes nothing and returns 422 with
only the failing operations. A tag failure carries the current `configurations`.

The 422 identifies each failing operation by `{op, i}`, where `i` is the 0-based
index within that operation's own group list.

**Blocked by:** None. Ticket 01 shipped the endpoint, and this reshapes it.

**Status:** resolved (commit e37a563)

- [ ] `POST /memories/batch` accepts a map with optional `create`, `update`, and
      `delete` lists, validated by malli coercion so a malformed request returns
      400. The ordered `{"ops": [...]}` run list from ticket 01 is removed.
- [ ] All operations pass: the server applies them in one transaction and returns
      200 with the new ids in create order and an applied count.
- [ ] Any operation fails: the server writes nothing and returns 422 with only the
      failing operations, each carrying `{op, i}` (index within its group) and its
      error.
- [ ] An id in both the update group and the delete group rejects the whole batch,
      and the 422 names both the update and the delete operation for that id.
- [ ] Several updates to one id fold cumulatively per field, a later update in the
      list winning a same-field tie.
- [ ] A repeated id in the delete list is tolerated: it is deduplicated and the
      memory is deleted once, with no error.
- [ ] A `create` or `update` with a tag set that matches no configuration fails,
      and that failure entry carries the current `configurations`.
- [ ] An `update` or `delete` of a missing id, or of another user's memory, fails
      as not-found and rejects the whole batch.
- [ ] The single-write routes (`POST /memories`, `PUT /memories/:id`,
      `DELETE /memories/:id`) stay unchanged. The memory-layer builders from
      ticket 01 (`create-tx`, `update-tx`, `->fields`) are reused.
- [ ] Edge cases hold. An empty batch (omitted or empty groups) returns 200 with
      no ids and an applied count of 0. `related` targets need not resolve. There
      is no cap on batch size.
- [ ] The `CONTEXT.md` glossary entry for `Batch` is updated from "an ordered list
      of operations" to the grouped map, and `docs/adr/0002-batch-request-grouped-map.md`
      is recorded.
- [ ] Tests cover the new shape and replace the ticket 01 tests that assumed the
      ordered run list. They exercise a map-shaped batch that all passes. They
      exercise an id in both update and delete, where both are flagged and nothing
      is written. They exercise cumulative updates to one id, a duplicated delete
      id that is tolerated, and a bad-tag create rejected with `configurations`.
      They exercise a missing id, per-user isolation, and an empty batch.
