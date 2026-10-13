# 19: Store and serve short-term memories under a thought-process

**What to build:** The server stores short-term memories and serves create, read, update, delete, and search under a thought-process. A short-term memory is a loose task note. It carries an id, the owning user, a thought-process, free text, a created time, and an updated time. A migration adds the short-term entity, separate from the Memory entity. A thought-process needs no separate creation. The first note under a name establishes it. A short-term memory never joins recall, stats, freshness, conformance, link density, or consolidate.

## Routes

Every route sits under `wrap-user` and filters by user. One user never reads or touches another user's notes.

- `POST /memories/short-term` creates a note. The body carries the thought-process and the text. The server assigns the id and returns it. The server stamps the created time and the updated time to now. The server enforces lowercase kebab-case on the thought-process, the rule it already imposes on a label. An empty text returns 400.
- `GET /memories/short-term/:id` returns one note as JSON. A note that is not the caller's returns 404.
- `PUT /memories/short-term/:id` replaces a note's text. The server stamps the updated time and leaves the created time. A note that is not the caller's returns 404.
- `DELETE /memories/short-term/:id` removes one note. A note that is not the caller's returns 404.
- `POST /memories/short-term/search` lists or streams. A query parameter `thought_process` and the body drive four behaviors, described below.
- `DELETE /memories/short-term?thought_process=xxx` wipes one whole thought-process. The server returns the deleted count. The count can be 0.

## The search route

The body is a map with an optional non-blank `search` term. A present blank term returns 400. The query parameter `thought_process` is optional.

Without a `thought_process`, the route returns JSON. An empty body returns each thought-process with its note count. A non-empty body returns the same map and adds the number of matching memories across all the caller's notes.

With a `thought_process`, the route streams that bucket's notes as NDJSON. An empty body streams every note in the bucket. A non-empty body streams only the full-text matches. The stream orders notes by created time, oldest first, and never truncates. The first line is a header. The header carries the thought-process and the search term.

## Wire shape

A note on the wire carries kebab-case keys: `id`, `thought-process`, `text`, `created-at`, and `updated-at`. The created time and the updated time are ISO-8601 strings. The query parameter stays `thought_process`.

## Atomicity

The update, the single delete, and the wipe resolve the note inside one `:db.fn/call` transaction function. The read and the commit are one atomic unit, so a concurrent delete leaves no half-written note. This mirrors the write path the memory routes use. A create reads nothing before it writes, so it stays a plain transaction.

## Schema

A migration adds the `:short-term/*` entity. It holds `:short-term/id` (uuid, unique identity), `:short-term/user`, `:short-term/process`, `:short-term/text`, `:short-term/created-at`, and `:short-term/updated-at`. The `:short-term/text` attribute is full-text indexed. Adding the full-text attribute causes one re-index on the first boot after the migration. The full-text search scopes to `:short-term/text` on the caller's own notes through a doc-filter, so it never returns a Memory's content or another user's note.

**Blocked by:** None (can start immediately).

**Status:** done

- [x] A migration adds a short-term entity separate from the Memory entity.
- [x] A short-term memory carries an id, the owning user, a thought-process, free text, a created time, and an updated time.
- [x] `:short-term/text` is full-text indexed, and search scopes to the caller's own notes.
- [x] `POST /memories/short-term` creates a note and returns a server-assigned id.
- [x] The server enforces lowercase kebab-case on the thought-process.
- [x] An empty text returns 400 at coercion.
- [x] `GET /memories/short-term/:id` returns one note. A note that is not the caller's returns 404.
- [x] `PUT /memories/short-term/:id` replaces the text and stamps the updated time.
- [x] `DELETE /memories/short-term/:id` removes one note. A note that is not the caller's returns 404.
- [x] `POST /memories/short-term/search` with an empty body and no thought-process returns each thought-process with its note count.
- [x] `POST /memories/short-term/search` with a non-empty body and no thought-process adds the number of matching memories.
- [x] `POST /memories/short-term/search` with a thought-process streams that bucket's notes as NDJSON, oldest first.
- [x] A non-empty body with a thought-process streams only the full-text matches.
- [x] `DELETE /memories/short-term?thought_process=xxx` wipes one thought-process and returns the deleted count.
- [x] The update, the single delete, and the wipe run inside one transaction function.
- [x] No route reaches across thought-processes, and another user's notes never appear.
- [x] A short-term memory never joins recall, stats, freshness, conformance, link density, or consolidate.
