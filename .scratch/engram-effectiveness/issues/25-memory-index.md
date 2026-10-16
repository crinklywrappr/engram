# 25: A structured per-user memory index

**What to build:** engram keeps a per-user memory index. It is a curated set of index entries that a session loads whole at session start. It orients recall before any memory loads, so Claude leans on a maintained map rather than a guess. The index ties long-term and short-term memory together, because one entry can carry a request against either. The index is the per-user analogue to a `MEMORY.md` file. See `CONTEXT.md` for the "Memory index", "Index entry", and "Lapse" glossary terms.

## The index entry

An entry carries a server-minted id, a kebab-case title, a free-text hook, and one or more requests. The title is the scannable handle. The hook is a one-line note on the entry's relevance. A request is a stored recall body of one of three kinds:

1. A `by-tags` request, a `:match` expression (ticket 22).
2. A `by-ids` request, a list of memory ids.
3. A `thought-process` request, a thought-process name (the `short-term/search` query parameter).

There is no full-text search request. Each request carries a `required?` flag, default false.

## Liveness and lapse

A required request is a liveness probe. An entry lapses once any one required request returns no memory. engram removes a lapsed entry. An entry with no required request stays until Claude deletes it.

Every request kind is an index-backed existence check, so a probe is fast. A `by-tags` probe folds over the tag store and short-circuits at the first match. A `by-ids` probe asks whether any listed id still resolves to the caller. A `thought-process` probe asks whether the bucket holds a note. A probe runs in existence mode, so it asks only whether the request yields at least one memory.

## Routes

The index is an `:index/*` entity, like `:short-term/*`, private to its user. Every route sits under `wrap-user` and filters by user.

- `GET /index` reads the index. It is the garbage-collection point. It probes every required request in existence mode, removes the lapsed entries, and returns the live entries. The probe and the removal run inside one `:db.fn/call` transaction, so they are atomic and a concurrent write cannot strand a half-reaped entry.
- `POST /index` creates an entry. The server assigns the id and returns it.
- `PUT /index/:id` replaces an entry. A `:id` that is not the caller's returns 404.
- `DELETE /index/:id` removes one entry. A `:id` that is not the caller's returns 404.

Each request stores as a component entity that carries its kind, its payload, and its `required?` flag. The payload stores as an opaque JSON string that the handler parses to probe or to hand back. An arbitrary-depth `:match` tree needs no shredding into datoms.

## The `engram-recall` skill

The skill loads the index as step 2, after the config load in step 1. The skill still teaches the search and recall mechanics. The skill reads the titles and the hooks, then runs the requests it judges relevant as normal recall calls. The skill creates or updates entries during work, so the index records what matters for a later session. This is the session-start recall backbone, now shippable, because it leans on the per-user index rather than admin-defined tags.

## Scope

This ticket delivers the index schema, the four routes, and the `engram-recall` step-2 integration. It does not touch `migrate`. Ticket 25a carries the `migrate` index-population. The user guide in ticket 23 covers the recall backbone, so ticket 23 wants a revisit once the index ships.

**Blocked by:** 22 (the `:match` expression the `by-tags` request stores). The design also draws on the session-start recall backbone work in 12 and 23.

**Status:** ready-for-agent

- [ ] A migration adds an `:index/*` entity, separate from the Memory and the short-term entities.
- [ ] An index entry carries an id, the owning user, a kebab-case title, a hook, and one or more requests.
- [ ] A request is a `by-tags` match, a `by-ids` list, or a `thought-process` name, each with a `required?` flag.
- [ ] `POST /index` creates an entry and returns a server-assigned id.
- [ ] `PUT /index/:id` replaces an entry, and `DELETE /index/:id` removes one, each 404 for a non-caller id.
- [ ] `GET /index` returns the caller's live entries.
- [ ] `GET /index` probes every required request in existence mode and removes a lapsed entry, inside one transaction.
- [ ] An entry with no required request survives a `GET /index`.
- [ ] Every route filters by user, so one user never reads or touches another user's index.
- [ ] The `engram-recall` skill loads the index as step 2, after the config load, and keeps the search and recall guidance.
