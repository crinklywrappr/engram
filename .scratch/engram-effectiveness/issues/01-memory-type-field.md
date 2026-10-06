# 01: Add the memory type field, required and closed on writes

**What to build:** A memory carries a type. The type says what kind of fact the memory holds. The closed value set is `user`, `feedback`, `project`, and `reference`. A fifth value, `unknown`, marks a legacy memory from before this field. A new migration adds the `:memory/type` attribute. The migration sets every existing memory to `unknown`. CREATE requires the type. CREATE, UPDATE, and BATCH accept only the four real values. The server rejects a write that sends `unknown`. The server rejects a write that sends any value outside the set. The server rejects a CREATE that omits the type. The closed set is a fixed server rule in application code. Datalevin cannot enforce the set at the schema level. The set is not part of the admin category configuration. The `engram-recall` and `migrate` skills describe the field, the required-on-create rule, and the four legal values.

**Blocked by:** None (can start immediately).

**Status:** wontfix

- [ ] A new migration adds `:memory/type` and sets every existing memory to `unknown`.
- [ ] CREATE rejects a request that omits the type.
- [ ] CREATE, UPDATE, and BATCH reject a type outside the four legal values.
- [ ] CREATE, UPDATE, and BATCH reject the legacy value `unknown`.
- [ ] A valid type persists and appears on a read.
- [ ] The wire schemas for CREATE, UPDATE, and BATCH name the type field.
- [ ] The `engram-recall` skill documents the field and the four legal values.
- [ ] The `migrate` skill documents the field and the four legal values.
- [ ] The edits pass the blocking simple-english lint hook.

## Comments

Marked wontfix on 2026-10-04. Ticket 10 lets a configuration close a category's label set with `:one-of`. So type becomes an ordinary category, not a dedicated field. The admin owns the type category and its values. The server needs no new attribute, no migration, and no hand-rolled enum code. The `/conform` skill classifies the existing memories. The type documentation folds into the ticket 10 skill update.
