# 18: Advise admins on a good configuration shape in the admin guide

**What to build:** A new section in `doc/admin-guide.md` shows a known-good configuration and explains why its shape works. The section sits after the "The configuration file" section. That earlier section teaches the format. This section teaches judgment. The implementing agent reads the live configuration from the running server rather than hand-copying a snapshot. The agent runs `ssh engram GET /config </dev/null` for the categories and configurations. The agent reads the mounted EDN file on the host for the two half-life values. The agent reproduces that live configuration in the section as the worked example.

The prose explains the reasoning behind the shape. It explains why `scope` carries a mandatory cardinality, so every memory declares its reach. It explains the split into two configurations. One configuration fits a global-reach fact with no project. The other fits a project-bound fact that must name a project. It explains a closed `:one-of` category, such as `type`. It explains why an admin closes a category's label set. It explains the chosen recall half-life and the chosen staleness half-life, and how each number shapes decay. An admin reads the section and copies the pattern. The admin does not reverse-engineer a shape from the format rules alone.

**Blocked by:** 10 (Let a configuration constrain a category's acceptable values) and 14 (Add the per-memory freshness flag and the confirm route).

**Status:** ready-for-agent

- [ ] A new section follows "The configuration file" in `doc/admin-guide.md`.
- [ ] The implementing agent reads the live configuration from the running server, not a hand-copied snapshot.
- [ ] The section reproduces that live configuration as the worked example.
- [ ] The prose explains why `scope` carries a mandatory cardinality.
- [ ] The prose explains the split into a global-reach configuration and a project-bound configuration.
- [ ] The prose explains a closed `:one-of` category.
- [ ] The prose explains why an admin closes a label set.
- [ ] The prose explains the recall half-life and the staleness half-life.
- [ ] Every sentence satisfies the blocking lint hook.
