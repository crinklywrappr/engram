# 11: Recall by a search string or by tags

**What to build:** The `/memories/recall` route accepts either a vector of tags or a search string. The two do not mix. A request carries one or the other. The server rejects a request that carries both. The server rejects a request that carries neither. The tag form behaves as it does today. A schema migration adds full-text indexing to the `src`. The search form then runs one ranked full-text query over content and src. The query returns entity IDs in relevance order. A search recall returns the matching memories, then follows the related closure like a tag recall. The response is the same NDJSON stream as a tag recall. The response is not truncated. The route filters by user. One user never sees another user's memories. The search form is the recovery path for a fact that the tags did not surface. The `engram-recall` skill documents both forms and names search as the recovery path.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] `/memories/recall` accepts a vector of tags or a search string.
- [ ] The server rejects a request that carries both forms.
- [ ] The server rejects a request that carries neither form.
- [ ] The tag form behaves as it does today.
- [ ] A migration adds full-text indexing to the `src`, and one ranked query covers content and src.
- [ ] A search recall returns the matches and follows the related closure.
- [ ] The response is the same NDJSON stream as a tag recall.
- [ ] Another user's memories never appear in the response.
- [ ] The `engram-recall` skill documents both forms and names search as recovery.
