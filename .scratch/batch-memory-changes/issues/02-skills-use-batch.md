# 02: Teach the skills to batch writes

**What to build:** The client skills use the batch endpoint instead of a write
per fact. The migrate skill, in its apply phase, sends its distilled facts as one
batch call and reads the per-operation results from the response. Because the
batch is atomic, a tag mismatch fails the whole batch and names the bad facts.
The skill fixes those facts against the returned configuration. The skill then
resends the whole batch. The engram-recall skill gains a short rule: a set of related writes
goes in one batch, not a call per write.

**Blocked by:** 03 (the simplified batch request), which builds on the endpoint
shipped in 01. The skills must use the final grouped-map shape.

**Status:** resolved (commit 203b291)

- [ ] The migrate skill's apply phase sends one `POST /memories/batch` with all
      distilled facts as `create` operations, instead of a `POST /memories` per
      fact.
- [ ] The migrate skill reads the per-operation results, and on a 409 it fixes
      the named facts against the returned configuration and resends the whole
      batch.
- [ ] The engram-recall skill states that a set of related writes goes in one
      batch call.
