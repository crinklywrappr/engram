# 11a: Recall a chosen set of memories by id, with their related closure

**What to build:** A recall that takes a list of memory ids. It returns those memories plus the memories they link to through related, followed across hops, like a tag recall. This is the load step after a search. The client picks ids from the ranked search candidates (ticket 11). The client then asks for them in full with their closure.

**Needs a grill:** Several shape points are open. Run a grilling before any code:

- The transport: a new input on `POST /memories/recall`, or a new route.
- The streaming: NDJSON, because the closure can grow large.
- The projection: whether the response carries the same categories projection as search.
- The degenerate cases: an empty id list, and an id that is not the caller's.

**Blocked by:** None (can start immediately). It completes the search-then-expand pattern begun in ticket 11.

**Status:** needs-info

- [ ] A grilling resolves the transport, the streaming, the projection, and the degenerate cases, before any code.
- [ ] The route takes a list of memory ids and returns those memories plus their related closure.
- [ ] The closure follows related across hops, like a tag recall.
- [ ] The route filters by user, and another user's memories never appear.
- [ ] The grill fills in the remaining acceptance criteria.
