# 11: Search memories by a ranked full-text query over content and src

**What to build:** A new `POST /memories/search` route for discovery. The request body carries a search string, an optional `limit`, and an optional `categories` list. The server runs one ranked full-text query over each memory's content and src. It returns a bounded, ranked candidate list. Search follows no related links. Search recovers a fact that a recall by pairs does not surface. The client reads the candidates. The client then passes the chosen ids to the id-recall (ticket 11a).

The request is a POST body. It matches its sibling `/memories/recall`. So the phrase needs no URL encoding over SSH. The body can also hold ticket 22's boolean structure. For ticket 11 the search value is a plain string only. Datalevin treats it as an OR of its words. The nested boolean grammar is ticket 22.

A schema migration adds full-text indexing to src. The existing content index stays. The migration rebuilds the index with a hyphen-splitting analyzer. A kebab src like `engram-deploy` then becomes searchable by its words, for example `deploy`. The default analyzer does not split on the hyphen. Without this step an src matches only as its whole slug. The migration uses Datalevin `re-index` with `:include-text? true` and `:index-position? true`. An in-place schema flip does not backfill existing src values, and `re-index` requires `:include-text?`. Setting `:index-position?` now lets ticket 22 add phrase search with no second stop-the-world re-index. `re-index` returns a new connection. The Integrant system must swap the old conn for it.

The response is a bounded JSON body `{"results":[ ... ]}`. Response coercion validates it, and Swagger shows it. Each result row carries id, src, content, and a relevance score as a raw double. A row also carries tags projected to the requested categories. A request that names no categories yields a row with no `tags` field. The categories projection never changes which memories match. It only chooses which of a matched memory's pairs appear. It reuses the vocabulary of the `/recalls` categories filter. The `limit` defaults to 20. The server clamps a `limit` above 100 down to 100. The 100 cap is documented in Swagger and in `engram-recall`. A blank or missing search string is a 400. The route filters by user. One user never sees another user's memories. Search returns long-term Memories only. Short-term memories live apart and never appear.

The `CONTEXT.md` glossary gains the `Search` term below. The `engram-recall` skill documents the search form, the 100 cap, and search as the recovery path. Both doc changes fold into this ticket's implementation commit.

Proposed `CONTEXT.md` entry:

> **Search**: A ranked full-text lookup over a memory's content and src. It returns a bounded, ranked candidate list and follows no related links. Search recovers a fact that a recall by pairs does not surface. The client chooses from the candidates and passes their ids to a later recall. _Avoid_: query, grep, find

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] `POST /memories/search` accepts `{search, limit?, categories?}` and rejects a blank or missing search with a 400.
- [ ] A migration adds full-text to src and rebuilds the index via `re-index`.
- [ ] The rebuild uses a hyphen-splitting analyzer, `:include-text? true`, and `:index-position? true`.
- [ ] Existing src values become searchable, and the new conn replaces the old in the system.
- [ ] One ranked query covers content and src, and results are ordered by relevance.
- [ ] Each result carries id, src, content, and a raw-double score.
- [ ] Tags appear only for the requested categories.
- [ ] A request without categories returns no `tags` field.
- [ ] `limit` defaults to 20 and clamps to 100.
- [ ] Search follows no related links and returns long-term Memories only.
- [ ] The response is a bounded JSON `{"results":[...]}`, response-coerced and documented in Swagger.
- [ ] Another user's memories never appear in the response.
- [ ] `CONTEXT.md` gains the `Search` term.
- [ ] `engram-recall` documents the search form, the 100 cap, and search as the recovery path.
