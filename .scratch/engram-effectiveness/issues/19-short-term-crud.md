# 19: Store and serve short-term memories under a thought-process

**What to build:** The server stores short-term memories and serves create, read, update, and delete under a thought-process. A short-term memory is a loose task note. It carries an id, free text, and a created time. A migration adds the short-term entity, separate from the Memory entity. The entity holds the owning user, the thought-process, the text, and the created time. A thought-process needs no separate creation. The first note under a name establishes it. A short-term memory never joins recall, stats, freshness, conformance, link density, or consolidate.

A `POST /memories/short-term` creates a note. The body carries the thought-process and the text. The server assigns the id and returns it. The server enforces lowercase kebab-case on the thought-process, the rule it already imposes on a label. A `GET /memories/short-term` with no thought-process returns the caller's thought-processes, each with a note count, as a small JSON map. A `GET /memories/short-term` with a thought-process streams that bucket's notes as NDJSON, one note per line. A `PUT /memories/short-term/:id` replaces a note's text. A `DELETE /memories/short-term/:id` removes one note. A `DELETE /memories/short-term` with a thought-process and no id wipes that whole thought-process.

Every route filters by user. One user never reads or touches another user's notes. No single call reaches across two thought-processes.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] A migration adds a short-term entity separate from the Memory entity.
- [ ] A short-term memory carries an id, the owning user, a thought-process, free text, and a created time.
- [ ] `POST` creates a note under a thought-process and returns a server-assigned id.
- [ ] The server enforces lowercase kebab-case on the thought-process.
- [ ] `GET` with no thought-process returns the caller's thought-processes, each with a note count.
- [ ] `GET` with a thought-process streams that bucket's notes as NDJSON.
- [ ] `PUT` replaces a note's text by id.
- [ ] `DELETE` by id removes one note, and `DELETE` with a thought-process and no id wipes that bucket.
- [ ] No route reaches across thought-processes, and another user's notes never appear.
- [ ] A short-term memory never joins recall, stats, freshness, conformance, link density, or consolidate.
