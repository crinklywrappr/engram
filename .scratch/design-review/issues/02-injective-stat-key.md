# 02: Injective per-user stat key

**What to build:** The per-user stat identity becomes a composite tuple of the user, the category, and the label, with no delimiter. Right now the identity is a single string that joins those three fields with a space. The user id comes from the trusted header, and nothing forbids a space in it. A user id with a space collides two different triples onto one stat row and corrupts the counts. A composite tuple removes the delimiter, so the collision cannot happen by construction. Make (`:stat/user`, `:stat/category`, `:stat/label`) a `:db/tupleAttrs` attribute marked `:db/unique :db.unique/identity`, and drop the joined `:stat/key`. Datalevin supports this. Issue 372 shows a running Datalevin schema that uses `:db/tupleAttrs` with `:db/unique :db.unique/identity`. As a companion guard, hold the user id to the existing `config/Token` shape at the request boundary. Every downstream use of the user id then sees a constrained value. engram is not yet deployed, so the schema changes freely with no data migration. Full findings live in the sibling research note at `research/02-injective-stat-key.md`.

**Blocked by:** None (can start immediately).

**Status:** resolved (commit 1a74e5f)

- [x] The stat identity is a composite `:db/tupleAttrs` over `:stat/user`, `:stat/category`, and `:stat/label`, marked `:db/unique :db.unique/identity`.
- [x] The joined `:stat/key` attribute is gone, and `stat-key` no longer exists.
- [x] Two user ids that differ only by a space keep separate stat rows.
- [x] A fetch upserts the correct row, and repeated fetches of one pair increment the same row.
- [x] The stats read path returns the same lifetime and recent counts as before.
- [x] The user id is held to the `config/Token` shape at the request boundary, and a malformed user id is rejected.
