# 16: Serve stale memories ordered by confirm-priority at `/stale`

**What to build:** A `GET /stale` route returns the caller's stale memories, ordered by confirm-priority, highest first. A stale memory sits in the stale band from ticket 14. The confirm-priority of a memory is its staleness times its recent recall count. Staleness is one minus freshness. The recent recall count comes from ticket 13. A memory the caller uses often but rarely confirms sorts to the top. A stale memory the caller never loads sinks to the bottom. The response is an NDJSON stream in the recall memory shape. The response is not truncated. The route filters by user. One user never sees another user's memories.

**Blocked by:** 13 (Record a recall count for each memory) and 14 (Add the per-memory freshness flag and the confirm route).

**Status:** ready-for-agent

- [ ] `GET /stale` returns the caller's stale memories.
- [ ] A stale memory sits in the stale band from ticket 14.
- [ ] The route orders the memories by confirm-priority, highest first.
- [ ] Confirm-priority is staleness times the recent recall count.
- [ ] A stale memory with no recent recalls sorts to the bottom.
- [ ] The response is an NDJSON stream in the recall memory shape.
- [ ] The response is not truncated.
- [ ] Another user's memories never appear in the response.
