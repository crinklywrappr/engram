# 22: Recall by a nested `:and`/`:or` expression over tags

**What to build:** The `/memories/recall` route accepts a nested boolean expression over category:label pairs. The expression combines pairs with `:and` and `:or`, nested to any depth. A caller asks for memories that match a boolean rule, for example `domain:clojure` and either `tech:datalevin` or `tech:reitit`. This form extends the existing tag body. The tag body and the search string body both stay. The response is the recall NDJSON stream, and it follows the related closure like a tag recall.

**Grill gate:** This ticket needs a grilling before any implementation. The request shape is not settled. The related-closure semantics are not settled. The degenerate cases, an empty expression and a single bare pair, are not settled. The server evaluation against the tag store is not settled. Run a grilling on this ticket first. Do not write code before a grill resolves these points. Fill in the remaining acceptance criteria from the grill outcome.

**Blocked by:** None (can start immediately). The grill gate above, not a ticket, is what holds implementation.

**Status:** needs-info

- [ ] A grilling resolves the request shape, the related-closure semantics, the degenerate cases, and the server evaluation, before any code.
- [ ] The nested `:and`/`:or` form extends the existing tag body, and the tag body and the search string body both stay.
- [ ] `/memories/recall` matches memories against a nested boolean expression over category:label pairs.
- [ ] The response is the recall NDJSON stream and follows the related closure.
- [ ] The grill fills in the remaining acceptance criteria.
