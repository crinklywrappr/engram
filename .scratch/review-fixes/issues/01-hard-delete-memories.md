# 01: Replace superseded? with hard delete

**What to build:** A user can permanently delete one of their own memories.
engram keeps no hidden history. A correction becomes a delete-and-recreate or an
in-place edit with `PUT`. Because the server keeps no superseded rows, a fetch
never drops a memory without a reason. This folds in the earlier note that a
superseded memory was omitted from results without returning the memory that
replaced it. When no superseded rows exist, that problem goes away.

**Blocked by:** None (can start immediately).

**Status:** resolved (commit c68a9ef)

- [ ] Migration 001 no longer defines `:memory/superseded?`. Edit the initial
      migration directly, because the project is not deployed anywhere yet.
- [ ] The create path no longer accepts or writes a supersede flag.
- [ ] The query no longer filters on a superseded flag.
- [ ] `DELETE /memories/:id` deletes the caller's own memory and returns 200. A
      delete of a memory that belongs to another user returns 404.
- [ ] `memory/delete!` retracts the memory entity and its component tags for the
      given user.
- [ ] Tests cover a delete, a delete of a missing id, and per-user isolation on
      delete. The prior supersede test is removed or replaced.
