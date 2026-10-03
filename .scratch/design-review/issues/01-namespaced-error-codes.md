# 01: Namespaced keywords for write-failure codes

**What to build:** A write failure carries a namespaced keyword as its internal code, not a bare string. Three failure kinds exist today. A memory has tags that match no acceptable configuration. An id appears in both the update group and the delete group. An id names no memory for this user. Each kind gets one keyword, defined in one place and owned by one namespace. The batch route and the single-write routes read that keyword to decide the response. Over the wire the JSON error body keeps the same short string values it carries today, so no client changes.

**Blocked by:** None (can start immediately).

**Status:** resolved (commit dda8fba)

- [x] Each of the three failure kinds has one namespaced keyword, defined in a single namespace.
- [x] The batch handler decides whether to attach the configurations by keyword equality, not by matching a literal string.
- [x] The JSON error body still carries the short string values `no-configuration`, `conflict`, and `not-found`, so the wire contract is unchanged.
- [x] No two namespaces spell the same code independently. One namespace owns the vocabulary and the others refer to it.
- [x] Tests cover the 409 create rejection, the 422 batch conflict, and the 422 batch not-found, asserting the wire string values.
