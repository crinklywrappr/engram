# 22: Recall by a nested `:and`/`:or` expression

**What to build:** A nested boolean expression over two surfaces. On `/memories/recall/by-tags` the expression combines category:label pairs with `:and` and `:or`, nested to any depth. A caller asks for memories that match a boolean rule, for example `domain:clojure` and either `tech:datalevin` or `tech:reitit`. This form extends the existing tag body. The tag body stays. On `/memories/search` the expression is the full-text boolean query. Datalevin's search already takes a nested boolean structure, for example `[:and [:or [:not ...]] {:phrase ...}]`. The search half maps straight onto it. The search half is well-scoped by ticket 11. The recall tag half is not.

**Grill gate:** This ticket needs a grilling before any implementation. The recall half is unsettled. The request shape for the tag expression is not settled. The related-closure semantics are not settled. The degenerate cases, an empty expression and a single bare pair, are not settled. The server evaluation against the tag store is not settled. Run a grilling first. Do not write code before a grill resolves these points. The search half rides on Datalevin's native grammar and needs less design. Confirm the search half in the same grill.

**Blocked by:** Ticket 11 scopes the search half. Ticket 11a renames the tag recall route to `/memories/recall/by-tags`. The tag half targets that route, so it follows 11a. The recall half can start after its grill. The grill gate above, not a ticket, is what holds implementation.

**Status:** needs-info

- [ ] A grilling resolves the recall request shape, the related-closure semantics, the degenerate cases, and the server evaluation, before any code.
- [ ] On `/memories/recall/by-tags` the nested `:and`/`:or` form extends the existing tag body, and the tag body stays.
- [ ] `/memories/recall/by-tags` matches memories against a nested boolean expression over category:label pairs.
- [ ] The recall response is the NDJSON stream and follows the related closure.
- [ ] On `/memories/search` the search value accepts Datalevin's nested boolean query structure.
- [ ] The grill fills in the remaining acceptance criteria.
