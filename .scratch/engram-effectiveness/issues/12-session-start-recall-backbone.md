# 12: Document the session-start recall backbone

**What to build:** The `engram-recall` skill recalls two pairs at session start. The first pair is `scope:global`. The second pair is `project:current`, the current project's label. The skill reads the project label set from `GET /recalls?categories=project`. It picks the exact current-project label and does not guess. The skill treats any further tag as a bonus. It finds bonus tags by scanning `/recalls` with the category filter, for example `?categories=domain,tech`. The skill names content search as the recovery path for a fact that does not surface under the pairs. The skill no longer reads the whole recall table at session start.

**Blocked by:** 08 (Move the recall counts off `/stats`) and 11 (Recall by a search string or by tags).

**Status:** wontfix

- [ ] The skill recalls `scope:global` and `project:current` at session start.
- [ ] The skill reads the project label set from `GET /recalls?categories=project`.
- [ ] The skill picks the current-project label from that set rather than guessing.
- [ ] The skill finds bonus tags by scanning `/recalls` with the category filter.
- [ ] The skill names content search as the recovery path.
- [ ] The skill no longer reads the whole recall table at session start.

## Comments

The session-start recall backbone cannot live in the engram-recall skill. The tags it leans on are admin-defined. An admin defines those categories. Without that configuration, `scope:global` and `project:current` do not exist. A shipped skill cannot assume them. So this belongs in user documentation, not skill logic.

The concept is not abandoned. It moves to ticket 23. Ticket 23 now documents the recall backbone in the user guide and keeps the good-configuration advice in the admin guide.
