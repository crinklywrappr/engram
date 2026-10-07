---
name: engram-recall
description: >-
  Recall and store durable memory in engram, the per-user memory server. Use at
  session start to load relevant memories, and during work to store atomic facts
  or correct old ones. Triggers: "remember this", "what do you know about me",
  behavioral preferences, project facts worth keeping across sessions.
---

# engram-recall

engram is a per-user memory server. A memory is one atomic fact tagged with
`category:label` pairs. You reach it over SSH: every call is
`ssh engram <METHOD> <PATH>`, with any request body on stdin.

## Transport

`engram-proxy` runs on the server as an SSH forced command. It adds your trusted
user identity, so you never send it. The result comes back like this:

- The response body is on stdout.
- The line `HTTP <code>` is on stderr.
- The exit code is 0 for a 2xx status and non-zero otherwise.

The SSH connection is reused. `~/.ssh/config` sets `ControlMaster`, so the first
`ssh engram` call opens one connection and later calls share it with no new
handshake. Nothing here manages a socket or a port.

Examples:

```bash
ssh engram GET /config < /dev/null
ssh engram GET /recalls < /dev/null
echo '{"tags":[["domain","clojure"]]}' | ssh engram POST /memories/recall/by-tags
echo '{"search":"deploy to the pi","limit":20}' | ssh engram POST /memories/search
echo '{"ids":["<uuid>"]}' | ssh engram POST /memories/recall/by-ids
echo '{"content":"...","src":"...","tags":[["domain","clojure"]]}' | ssh engram POST /memories
```

A recall streams NDJSON: the first line is a header object, and every line after
it is one memory.

## Session start: load the configuration once

Run `ssh engram GET /config < /dev/null` one time per session. It returns
`{"configurations": [ ... ]}`. Each configuration is a map of category to a
cardinality (`1`, `?`, `*`, `+`). A memory you write must satisfy one
configuration. Keep this in mind for the rest of the session.

The cardinality says how many labels of a category a memory carries:

- `1` exactly one
- `?` zero or one
- `*` zero or more
- `+` one or more

A category can instead be a map, `{"cardinality": "?", "one-of": ["global",
"project"]}`. The `one-of` list closes that category's labels to its tokens. Pick
a label from the list for that category.

When the admin describes the categories, the body also carries a `categories`
map. Each entry gives one category a `description` and a vector of `examples`.
Read them to pick labels that match how the admin means each category.

## Recall: load memories plus their links

A recall loads the memories you select plus every memory linked to them through
`related`, followed across hops. You select the memories two ways: by their
category:label pairs, or by a list of ids a search returned.

Each memory line carries `id`, `content`, and `src`. Non-empty `tags` and
`related` appear too.

### Recall by pairs: two calls

1. `ssh engram GET /recalls` returns one row for every category:label pair on your
   memories. The body is `{"recalls": [ ... ]}`. Each entry is a
   positional row `[category, label, count, lifetime, recent]`. The count is how
   many of your memories carry the pair. A pair you never recalled still appears,
   with a lifetime of 0 and a recent of 0.0. Use the count, the lifetime, and the
   recent value to choose the pairs worth loading.
2. `POST /memories/recall/by-tags` with `{"tags": [["category","label"], ...]}`
   returns the matching memories plus their related closure. Read every line after
   the header line.

### Search, then recall by ids: the recovery path

When a recall by pairs does not surface a fact, search for it, then load what you
find. This is a two-step path.

1. `POST /memories/search` runs one ranked full-text query over content and src.
   The body is `{"search": "...", "limit": 20, "categories": ["domain"]}`. Only
   `search` is required. `limit` defaults to 20 and caps at 100. A larger `limit`
   clamps to 100. The response is `{"results": [ ... ]}`, ranked by relevance.
   Each row carries `id`, `src`, `content`, and a `score`. A row carries `tags`
   only for the categories you name. A body that names no categories returns no
   `tags`. Search follows no related links. It is the discovery step: read the
   candidates and choose the ids that matter.
2. `POST /memories/recall/by-ids` with `{"ids": ["<uuid>", ...]}` loads those
   memories in full plus their related closure, as the same NDJSON stream. A
   row carries no score. An id that is not yours, or does not exist, is skipped.
   An empty `ids` list returns a header line and no memories.

## Store a fact

`POST /memories` with a JSON body:

```json
{"content": "one fact stated plainly",
 "src": "kebab-source-id",
 "tags": [["domain","clojure"],["tech","datalevin"]],
 "related": ["another-src"]}
```

Rules:

- One fact states one thing. If a fact needs a list, write one fact per item.
- `src` is the source identity. Atomic facts split from one source share a src.
- `src` is analogous to the filename you assign to markdown memories.
- `related` links to other memories by their `src`. The server follows these
  links transitively on a recall.
- Labels, `src`, and each `related` value must be lowercase kebab-case tokens: a
  lowercase letter, then lowercase letters, digits, or hyphens. A leading digit,
  uppercase, a dot, and a plus are rejected. Do not create near-duplicate labels,
  for example `cost-analysis` and `my-cost-analysis`. Reuse an existing label.
- Categories are closed to the configuration. `src` and `related` are not
  categories. The server always requires exactly one `src` and allows zero or more
  `related`, apart from the configuration.

engram keeps no history. Read a fact first with `GET /memories/<id>`. It returns
the memory in the recall shape, or 404 for an id that is not yours. Read the
current state before you change or remove a fact. To correct a fact, edit it in
place with `PUT /memories/<id>` using the same body shape without `src`. To remove
a fact, delete it with `DELETE /memories/<id>`. A delete returns 200, and a delete
of a memory that is not yours returns 404.

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
