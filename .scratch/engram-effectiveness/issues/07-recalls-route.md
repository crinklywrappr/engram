# 07: Serve the recall counts from a `/recalls` route

**What to build:** A new `GET /recalls` route returns the caller's recall-count rows. Each row carries the category, the label, the count of memories with the pair, the lifetime recall count, and the decayed recent count. The route reuses the current recall-count computation. The body is a bare `{"recalls": [...]}` map. It drops the `{"stats": ...}` wrapper. The row shape matches what `/stats` returns today. A client changes only the path and unwraps one level. The route accepts an optional `categories` parameter, for example `/recalls?categories=domain,tech`. When the parameter is present, only rows whose category is in the list return. When it is absent, every row returns. This lets a caller pull one category, such as `project`, without reading the whole table. The route leaves `/stats` unchanged for now.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] `GET /recalls` returns the caller's recall-count rows.
- [ ] Each row carries category, label, count, lifetime, and recent.
- [ ] The body is a bare `{"recalls": [...]}` map without the `{"stats": ...}` wrapper.
- [ ] The route reuses the current recall-count computation.
- [ ] One user never sees another user's rows.
- [ ] The response schema names the recalls shape.
- [ ] `GET /recalls` accepts an optional `categories` parameter.
- [ ] A category filter returns only rows whose category is in the list.
- [ ] No category filter returns every row.
