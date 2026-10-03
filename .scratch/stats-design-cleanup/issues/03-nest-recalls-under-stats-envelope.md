# 03: Nest recalls under the /stats envelope and update the skill

**What to build:** `GET /stats` returns the recall counts under a `:recalls` key inside the `:stats` envelope. The shape becomes `{:stats {:recalls [ ... ]}}`. This leaves room for future stat types beside recalls. The client skill that reads `/stats` reads the new path.

**Blocked by:** 02, because this uses the renamed `recalls` function and returns a `:recalls` key.

**Status:** ready-for-agent

- [ ] `GET /stats` returns `{:stats {:recalls [row ...]}}`, one entry for each category:label pair.
- [ ] The `StatsOut` response schema nests the recall rows under `:stats` then `:recalls`.
- [ ] The response schema declares every key the client keeps, so response coercion drops nothing.
- [ ] The client skill that reads `/stats` reads `:stats` then `:recalls`.
- [ ] A handler test asserts the nested shape end to end.
