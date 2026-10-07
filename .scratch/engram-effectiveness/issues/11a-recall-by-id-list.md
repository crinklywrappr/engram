# 11a: Recall by a list of ids, completing the search-then-recall workflow

**What to build:** A recall that selects its matches by a list of ids instead of by category:label pairs. It returns those memories plus the memories they link to through related, followed across hops. A tag recall does the same. This is the load step after a search. The client searches to discover candidates (ticket 11). The client picks the ids that matter. The client recalls those ids to load them in full with their closure.

This ticket expands the meaning of a recall. A recall is no longer tied to one selector. A recall loads the matches plus their related closure. The matches are selected by category:label pairs or by a list of ids. The closure is the invariant that makes it a recall. Search is not a recall, because it follows no closure. Search is the discovery step. It produces the ids an id recall loads.

**The routes.** The two recall forms sit under the recall namespace, one route each:

- `/memories/recall/by-tags` is the current pair recall, renamed from `/memories/recall`. Its body and behavior do not change. The body stays `{"pairs": [["category","label"], ...]}`. The response stays the NDJSON stream with the JSON fallback and the related closure.
- `/memories/recall/by-ids` is new. Its body is `{"ids": ["<uuid>", ...]}`.

This ticket owns the whole restructuring in one change. It renames the pair route to `by-tags`. It adds `by-ids`. It updates every in-repo caller: the `engram-recall` skill, the `migrate` skill, and the tests. There are no external consumers, so there is no deprecated `/memories/recall` alias.

**The id form on the wire.** `by-ids` returns what the pair recall returns. The response is the NDJSON stream with the JSON fallback. Each memory carries the full recall wire shape: id, content, src, and non-empty tags and related. The recall follows the related closure across hops. There is no relevance score and no category projection. Those are search's tools for a lean candidate list. A recall loads the full memories the client already chose.

**The edges of the id form.** An empty `ids` list is a well-formed request. It selects nothing and returns a 200 with an empty stream, the same as an empty pair recall. An id that is not the caller's, or that does not exist, is skipped silently. The response then carries only the caller's own matches. A malformed id that is not a UUID is a 400 at coercion. It reuses the id format the `:id` routes use. Duplicate ids collapse to one memory, the dedup the closure walk already performs.

**The skill.** The `engram-recall` skill folds search into the recall workflow. Search is not a standalone section. The skill reads as one path. A recall by tags stands alone. A search discovers candidate ids, and a recall by ids then loads them with their closure.

**The glossary.** The `Recall` entry expands. The `Search` entry stays and points at the id recall. Proposed `CONTEXT.md` change, to apply with this ticket:

> **Recall**: The act of asking for memories and getting back the matches plus the memories they link to across hops. A recall selects its matches either by category:label pairs or by a list of ids. The id form loads the ids a search returned. _Avoid_: query, request, fetch

> **Search** (amend the final sentence): The client chooses from the candidates and passes their ids to a recall.

**Out of scope.** Recall counts are ticket 13's concern. This ticket does not touch them.

**Depends on and affects:** Ticket 11 (search) produces the ids this consumes. Ticket 22's nested boolean form once aimed at `/memories/recall`. It now targets `/memories/recall/by-tags`. Ticket 22 is amended to match.

**Blocked by:** None (can start immediately). It completes the search-then-recall workflow begun in ticket 11.

**Status:** ready-for-agent

- [ ] `/memories/recall` is renamed to `/memories/recall/by-tags`, with its body and behavior unchanged.
- [ ] The rename updates the in-repo callers and the tests.
- [ ] `/memories/recall/by-ids` accepts `{"ids": [...]}` and returns the matching memories plus their related closure.
- [ ] The id recall returns the same NDJSON stream and JSON fallback as the pair recall.
- [ ] The id recall uses the full recall wire shape, with no score and no category projection.
- [ ] An empty `ids` list returns a 200 empty stream.
- [ ] A foreign or missing id is skipped silently, and a non-UUID id is a 400.
- [ ] Duplicate ids collapse to one memory.
- [ ] Another user's memories never appear in the response.
- [ ] The `engram-recall` skill folds search into the recall workflow, so search reads as the discovery step of a recall.
- [ ] `CONTEXT.md` expands the `Recall` term and amends the `Search` term.
