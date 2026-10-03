# 05: Promote recall to a first-class act

**What to build:** engram names the pair-based memory query a recall, end to end. The route, the handler, and the memory function carry the recall name. The private walker that the public function wrapped takes the freed `query` name. The client skill calls the new route.

**Blocked by:** None (can start immediately). This ticket and 03 both edit the client recall skill, so expect a shared touch there.

**Status:** ready-for-agent

- [ ] `POST /memories/query` becomes `POST /memories/recall`.
- [ ] `handler/query-handler` becomes `handler/recall-handler`.
- [ ] `memory/query` becomes `memory/recall`, the public recall function.
- [ ] `memory/query'` becomes `memory/query`, now that the public name is free.
- [ ] The route, handler, and function names match the `Recall` entry in `CONTEXT.md`.
- [ ] The client recall skill calls `POST /memories/recall`.
- [ ] The `QueryBody` and `QueryOut` schemas take the recall name.
- [ ] The docstrings, comments, and section banners say recall, not query.
- [ ] The tests and the README name the recall route and function.
- [ ] The full test suite passes.
