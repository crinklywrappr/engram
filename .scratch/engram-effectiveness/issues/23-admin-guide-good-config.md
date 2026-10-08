# 23: Document a good configuration in the admin guide and the recall backbone in the user guide

**What to build:** Two pieces of documentation that teach judgment, one for admins and one for users.

The admin-guide piece adds a section to `doc/admin-guide.md`, after "The configuration file". That earlier section teaches the format. This section teaches judgment. The implementing agent reads the live configuration from the running server rather than hand-copying a snapshot. The agent runs `ssh engram GET /config </dev/null` for the categories and configurations. The agent reads the mounted EDN file on the host for the two half-life values. The agent reproduces that live configuration as the worked example.

The prose explains the reasoning behind the shape. It explains why `scope` carries a mandatory cardinality, so every memory declares its reach. It explains the split into two configurations. One configuration fits a global-reach fact with no project. The other fits a project-bound fact that must name a project. It explains a closed `:one-of` category, such as `type`. It explains why an admin closes a category's label set. It explains the chosen recall half-life and the chosen staleness half-life. It explains how each number shapes decay. An admin reads the section and copies the pattern. The admin does not reverse-engineer a shape from the format rules alone.

The user-guide piece adds a section to `doc/user-guide.md` on the session-start recall backbone. This moved here from ticket 12. A user recalls two tags at session start. The first tag is `scope:global`. The second tag is `project:current`, the current project's label. The user reads the project label set from `GET /recalls?categories=project`. The user picks the current-project label and does not guess. The user treats a further tag as a bonus. The user finds bonus tags by scanning `/recalls` with the category filter, for example `?categories=domain,tech`. The user runs a content search as the recovery path for a fact the tags do not surface.

The recall backbone lives in the user guide, not the engram-recall skill. The tags it leans on are admin-defined. A shipped skill cannot assume `scope:global` or `project:current`. The user guide teaches the pattern against the configuration an admin actually mounted.

**Blocked by:** 10 (configuration value sets) and 14 (freshness flag and confirm route) gate the admin-guide half. 08 (recall counts off `/stats`), 11 (recall by tags or search), and 22 (nested boolean recall) gate the user-guide half.

**Status:** ready-for-agent

- [ ] A new section follows "The configuration file" in `doc/admin-guide.md`.
- [ ] The implementing agent reads the live configuration from the running server, not a hand-copied snapshot.
- [ ] The admin section reproduces that live configuration as the worked example.
- [ ] The prose explains why `scope` carries a mandatory cardinality.
- [ ] The prose explains the split into a global-reach configuration and a project-bound configuration.
- [ ] The prose explains a closed `:one-of` category.
- [ ] The prose explains why an admin closes a label set.
- [ ] The prose explains the recall half-life and the staleness half-life.
- [ ] A new section in `doc/user-guide.md` documents the session-start recall backbone.
- [ ] The user section recalls `scope:global` and `project:current` at session start.
- [ ] The user section reads the project label set from `GET /recalls?categories=project` and picks the label rather than guessing.
- [ ] The user section finds bonus tags with the category filter and names content search as the recovery path.
- [ ] Every sentence satisfies the blocking lint hook.
