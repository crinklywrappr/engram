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
ssh engram GET /stats  < /dev/null
echo '{"pairs":[["domain","clojure"]]}' | ssh engram POST /memories/query
echo '{"content":"...","src":"...","tags":[["domain","clojure"]]}' | ssh engram POST /memories
```

The fetch call streams NDJSON: the first line is a header object, and every line
after it is one memory.

## Session start: load the configuration once

Run `ssh engram GET /config` one time per session. It returns
`{"configurations": [ ... ]}`. Each configuration is a map of category to a
cardinality (`1`, `?`, `*`, `+`). A memory you write must satisfy one
configuration. Keep this in mind for the rest of the session.

## Recall: two calls

1. `ssh engram GET /stats` returns your `category:label` counts, each with a
   `lifetime` and a `recent` value. Use them to choose the pairs worth loading.
2. `POST /memories/query` with `{"pairs": [["category","label"], ...]}` returns
   the matching memories plus every memory linked to them through `related`,
   followed transitively. Read every line after the header line.

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
- `related` links to other memories by their `src`. The server follows these
  links transitively on a fetch.
- Labels, `src`, and each `related` value must be lowercase kebab-case tokens: a
  lowercase letter, then lowercase letters, digits, or hyphens. A leading digit,
  uppercase, a dot, and a plus are rejected. Do not create near-duplicate labels,
  for example `cost-analysis` and `my-cost-analysis`. Reuse an existing label.
- Categories are closed to the configuration. `src` and `related` are not categories.

engram keeps no history. To correct a fact, edit it in place with
`PUT /memories/<id>` using the same body shape without `src`. To remove a fact,
delete it with `DELETE /memories/<id>`. A delete returns 200, and a delete of a
memory that is not yours returns 404.

## When a write returns 409

A `POST` or `PUT` that does not match the configuration returns exit code 1 with
`HTTP 409` on stderr. The stdout body contains `{"configurations": [...]}`.
Replace your cached configuration with it, fix the tags, and retry.
