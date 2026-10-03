# 05: Drop timestamps from the recall row

**What to build:** A recalled memory carries no `created-at` and no `updated-at`. The recall consumer does not use them, so they are pure token cost on every row. Remove them from the pull pattern and from the wire shaping. Update the recall response schemas, the recall skill doc, and the tests to match.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] A recalled memory omits `created-at` and `updated-at`.
- [ ] The pull pattern no longer reads the two timestamp fields.
- [ ] The recall response schemas and the recall skill doc no longer name the timestamps.
- [ ] The full test suite passes.
