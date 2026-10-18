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
echo '{"match":["domain","clojure"]}' | ssh engram POST /memories/recall/by-tags
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

## Session start: load the index

Run `ssh engram GET /index < /dev/null` one time per session, after the
configuration load. It returns `{"entries": [ ... ]}`. An entry is one item in
the index. Each entry carries a `title`, a `hook`, and one or more
`requests`. The title is a short handle. The hook is a one-line note on the
entry's relevance.

Read the titles and the hooks first. For an entry that fits the session, run its
requests as normal recall calls. A request names how to load its memories:

- A `by-tags` request carries a match expression. Send it as `{"match":
  <payload>}` to `POST /memories/recall/by-tags`.
- A `by-src` request carries a list of source names. Send it as `{"srcs":
  <payload>}` to `POST /memories/recall/by-src`.
- A `thought-process` request carries a bucket name. Load it from `POST
  /memories/short-term/search?thought_process=<payload>`.

The index reaps a stale entry on its own. A request can carry `"required": true`.
That marks it a liveness probe. When a required request matches no memory, `GET
/index` removes the whole entry. So an entry stays only while its required
requests still resolve.

Build the index as you work. When a recall pays off, create an entry. A later
session then finds it fast:

```bash
echo '{"title":"engram-work","hook":"the engram memory server","requests":[{"kind":"by-tags","payload":{"and":[["project","engram"]]},"required":true}]}' | ssh engram POST /index
```

The server mints the id and returns it. Replace an entry with `PUT /index/<id>`
and the same body. Remove one with `DELETE /index/<id>`.

## Short-term memory

A short-term memory is a loose task note, not an atomic fact. It holds one
passing thought during a task. It lives apart from a long-term memory, and it
never joins a recall. A thought-process is the bucket that holds these notes for
one task. You name a thought-process in lowercase kebab-case. A good name
describes the project and the task.

### Session start: list the buckets

Run `ssh engram POST /memories/short-term/search < /dev/null` one time at session
start. The empty body and the missing `thought_process` list the buckets. The
body comes back as `{"thought-processes": {"<name>": <count>}}`, one entry per
bucket with its note count. Show the buckets to the user. Resume the current
task's bucket by its name. When you continue another session's task, adopt its
bucket by its name.

### During work: stage notes

Stage a passing thought as a short-term memory under the task's thought-process.
One note holds one thought. Create a note:

```bash
echo '{"thought-process":"engram-decompress","text":"the wipe waits on a 200"}' | ssh engram POST /memories/short-term
```

The server assigns the id and returns it. Read, change, and remove a note by id:

```bash
ssh engram GET /memories/short-term/<id> < /dev/null
echo '{"text":"the corrected thought"}' | ssh engram PUT /memories/short-term/<id>
ssh engram DELETE /memories/short-term/<id> < /dev/null
```

Search within a bucket:

```bash
echo '{"search":"wipe"}' | ssh engram POST '/memories/short-term/search?thought_process=engram-decompress'
```

An empty body with a `thought_process` streams the whole bucket as NDJSON, oldest
first. A non-empty body streams only the full-text matches. Each note line
carries `id`, `thought-process`, `text`, `created-at`, and `updated-at`.

### End of task: decompress

The user runs `/decompress` to close out the task's thought-process. It promotes
the worthwhile notes to long-term facts, archives the whole bucket, and wipes it.
When a task ends, suggest `/decompress` to the user.

## Recall: load memories plus their links

A recall loads the memories you select plus every memory linked to them through
`related`, followed across hops. You select the memories by category:label pairs
(the basic recall below) or by source names. A search returns ids you then recall
by id. Read `references/search-recovery.md` in this skill's directory for that recovery path.

Each memory line carries `id`, `content`, and `src`. Non-empty `tags` and
`related` appear too.

### Recall by pairs: two calls

1. `ssh engram GET /recalls < /dev/null` returns one row for every category:label pair on your
   memories. The body is `{"recalls": [ ... ]}`. Each entry is a
   positional row `[category, label, count, lifetime, recent]`. The count is how
   many of your memories carry the pair. A pair you never recalled still appears,
   with a lifetime of 0 and a recent of 0.0. Use the count, the lifetime, and the
   recent value to choose the pairs worth loading.
2. `POST /memories/recall/by-tags` with `{"match": <expression>}` returns the
   matching memories plus their related closure. Read every line after the header
   line. A match expression is a nested boolean rule over category:label pairs. A
   leaf is one pair, for example `["domain","clojure"]`. An operator is a one-key
   object: `{"and": [ ... ]}`, `{"or": [ ... ]}`, or `{"not": <expression>}`. The
   `and` and `or` operators take one or more children. The `not` operator takes
   one child. A bare leaf is a whole expression, so `{"match": ["domain","clojure"]}`
   recalls one pair. For any of several pairs, use `{"match": {"or": [["a","b"],
   ["c","d"]]}}`. To exclude a pair, use `{"not": ["tech","sqlite"]}` inside an
   `and`.

### Recall by source

`POST /memories/recall/by-src` with `{"srcs": ["<name>", ...]}` loads every memory
under those source names, plus their related closure, as the same NDJSON stream.
A src names a source, so one src loads every atomic fact split from it. A src that
is not yours contributes nothing. An empty `srcs` list returns a header line and
no memories. The index uses this call to pin a source by name.

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

To change or remove one fact by id, or to write many facts in one batch, read
`references/writes.md`. That file sits in this skill's directory. engram keeps no
history, so read a fact before you change it. A write that matches no
configuration returns 409, and that reference covers the 409 too.
