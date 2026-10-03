# 01: Batch endpoint for mixed create, update, and delete

**What to build:** A client applies an ordered list of memory changes in one
atomic call. `POST /memories/batch` takes a body `{"ops": [...]}`. The `ops` value
is an ordered list of `[verb, payloads]` pairs in grouped-run form. The verb is
`create`, `update`, or `delete`. A verb can appear again later in the list. Runs
keep their order and can repeat:

```
{"ops":
 [ ["create", [ {"content":"...","src":"foo","tags":[["domain","clojure"]]},
                {"content":"...","src":"foo"} ]],
   ["update", [ {"id":"<uuid>","tags":[["domain","clojure"]]} ]],
   ["delete", [ "<uuid>" ]] ]}
```

A `create` payload carries `content`, `src`, and optional `tags` and `related`. An
`update` payload carries `id` and any of `content`, `tags`, `related`. A `delete`
payload is an id string. Malli coercion rejects a malformed operation with 400
before the rest runs.

The batch is atomic and pre-validated. The server first validates every `create`
and `update` against the admin configurations. It then resolves every `update`
and `delete` to one of the caller's own memories, against the batch's starting
state. If all operations pass, the server applies them in one transaction and
returns 200. If any operation fails, the server writes nothing and returns 422.
See `docs/adr/0001-atomic-batch-writes.md` for why the batch is all-or-nothing.

Ordering is significant. The batch applies as a sequence of state transitions in
one transaction. The result matches the same operations sent as separate
sequential calls. Two updates to one id are cumulative per field. An update of
`content` followed by an update of `tags` on the same id keeps both changes.
"Later wins" breaks only a same-field tie. A delete is terminal. If an earlier
operation deletes an id, a later operation on that id fails pre-flight. The whole
batch is then rejected.

The response is lean and indexed. On 200 the body is a flat list of the new ids in
create order, plus an `applied` count. An `update` and a `delete` echo nothing,
because the client already holds those ids. On 422 the body reports only the
failing operations. Each failure carries its error and a flat running index `i`.
The index `i` is 0-based across all payloads in listed order, after the runs are
flattened. A tag error also carries the current `configurations`, so the client
refreshes its cached configuration from that entry.

**Blocked by:** None (can start immediately).

**Status:** resolved (commit ac9c7e6)

- [ ] `POST /memories/batch` accepts `{"ops": [...]}`, an ordered list of
      `[verb, payloads]` grouped-run pairs, validated by malli coercion so a
      malformed shape returns 400.
- [ ] All operations pass: the server applies them in one transaction and returns
      200 with the new ids in create order and an `applied` count.
- [ ] Any operation fails validation or resolution: the server writes nothing and
      returns 422 with only the failing operations, each carrying its flat running
      index `i` and its error.
- [ ] A `create` or `update` with a tag set that matches no configuration fails,
      and that failure entry carries the current `configurations`.
- [ ] An `update` or `delete` of a missing id, or of another user's memory, fails
      as not-found for that operation and rejects the whole batch.
- [ ] Ordering holds: two updates to one id are cumulative per field, and a delete
      is terminal, so a later operation on a deleted id rejects the whole batch.
- [ ] A memory-layer `apply-batch!` function resolves and applies the operation
      list for one user in a single transaction, reproducing the sequential
      per-field semantics above.
- [ ] The single-write routes (`POST /memories`, `PUT /memories/:id`,
      `DELETE /memories/:id`) stay unchanged. The batch route is additive.
- [ ] Edge cases hold. An empty batch returns 200 with no ids and `applied` 0.
      `related` targets need not resolve, unchanged from the single-write path.
      There is no cap on batch size.
- [ ] Tests cover a mixed batch that all passes, a batch with one bad-tag `create`
      (nothing written), an `update` and a `delete` of a missing id, per-user
      isolation, cumulative updates to one id, a delete-then-touch rejection, and
      an empty batch.
