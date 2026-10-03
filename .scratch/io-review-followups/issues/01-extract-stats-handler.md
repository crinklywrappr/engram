# 01: Extract a stats-handler for route parity

**What to build:** The `/stats` route delegates to a named handler. Today it builds the recall-count envelope inside an anonymous route function. Every sibling route delegates to a named handler. Extract a `stats-handler` so the route table reads the same way for every route. The response does not change.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] `/stats` calls a named `stats-handler`, not an inline function.
- [ ] The `/stats` response body is the same as before.
- [ ] The full test suite passes.
