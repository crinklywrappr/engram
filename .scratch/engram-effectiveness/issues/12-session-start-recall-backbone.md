# 12: Document the session-start recall backbone

**What to build:** The `engram-recall` skill recalls two pairs at session start. The first pair is `scope:global`. The second pair is `project:current`, the current project's label. The skill reads the project label set from `GET /recalls?categories=project`. It picks the exact current-project label and does not guess. The skill treats any further tag as a bonus. It finds bonus tags by scanning `/recalls` with the category filter, for example `?categories=domain,tech`. The skill names content search as the recovery path for a fact that does not surface under the pairs. The skill no longer reads the whole recall table at session start.

**Blocked by:** 08 (Move the recall counts off `/stats`) and 11 (Recall by a search string or by tags).

**Status:** ready-for-agent

- [ ] The skill recalls `scope:global` and `project:current` at session start.
- [ ] The skill reads the project label set from `GET /recalls?categories=project`.
- [ ] The skill picks the current-project label from that set rather than guessing.
- [ ] The skill finds bonus tags by scanning `/recalls` with the category filter.
- [ ] The skill names content search as the recovery path.
- [ ] The skill no longer reads the whole recall table at session start.
