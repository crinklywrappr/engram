# 01: Coerce the memory id path parameter on PUT and DELETE

**What to build:** `PUT /memories/:id` and `DELETE /memories/:id` validate the
`:id` path segment as a UUID. A non-UUID id returns 400 with a message that names
the bad path parameter, instead of the 500 it returns today. Right now the id is
read raw from the path and reaches `UUID/fromString`, which throws on a malformed
value, so `/memories/not-a-uuid` returns 500. Adding a path schema turns that into
a clean coercion error. This reuses the `IdStr` schema the batch route already
uses, so the id shape lives in one place.

**Blocked by:** None (can start immediately).

**Status:** resolved (commit 98b2fe5)

- [ ] Both routes declare `:parameters {:path [:map [:id IdStr]]}`, reusing the
      existing `IdStr` schema.
- [ ] A non-UUID id on either route returns 400 with a coercion message, not 500.
- [ ] The update and delete handlers read the coerced id from
      `[:parameters :path :id]`, not from `[:path-params :id]`.
- [ ] Behaviour for a valid id is unchanged. A valid id that names no memory
      returns 404, and another user's id returns 404.
- [ ] Tests cover a malformed id returning 400 on both `PUT` and `DELETE`.
