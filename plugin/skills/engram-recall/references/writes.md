# Change and remove memories, write in batch, and handle 409

This reference covers three things: single-fact change and removal, batch writes,
and the 409 from a write.

## Change or remove one fact

engram keeps no history. Read a fact first with `GET /memories/<id>`. It returns
the memory in the recall shape, or 404 for an id that is not yours. Read the
current state before you change or remove a fact. To correct a fact, edit it in
place with `PUT /memories/<id>` using the same body shape. A correction can change
`src` too. To remove a fact, delete it with `DELETE /memories/<id>`. A delete
returns 200, and a delete of a memory that is not yours returns 404.

## Store or change many at once

To write a set of related facts, or to apply several changes together, send one
batch. Do not send a call per write. `POST /memories/batch` takes a map of
grouped operations:

```json
{"create": [ {"content":"...","src":"a","tags":[["domain","clojure"]]},
             {"content":"...","src":"b","tags":[["domain","clojure"]]} ],
 "update": [ {"id":"<uuid>","tags":[["domain","clojure"]]} ],
 "delete": [ "<uuid>" ]}
```

Each group is optional. A `create` payload has the same shape as a single write.
An `update` payload carries the memory `id` and the fields to change. A `delete`
payload is a memory `id`.

The batch is atomic. The server validates every operation first. If all pass, it
applies them together and returns 200. The body is `{"ids":[...],"applied":n}`.
The `ids` are the new memory ids, in create order.

If any operation fails, the server writes nothing and returns exit code 1 with
`HTTP 422`. The body is `{"errors":[...]}`. Each entry is one failing operation.
It carries the group `op` and the index `i` in that group. A tag error also
carries `configurations`. Replace your cached configuration from it. Fix the
named operations. Resend the whole batch.

One `id` must not appear in both the update group and the delete group. When it
does, the server rejects the batch.

## When a write returns 409

A `POST` or `PUT` that does not match the configuration returns exit code 1 with
`HTTP 409` on stderr. The stdout body contains `{"error", "message",
"configurations"}`. Replace your cached configuration from the `configurations`
value, fix the tags, and retry.
