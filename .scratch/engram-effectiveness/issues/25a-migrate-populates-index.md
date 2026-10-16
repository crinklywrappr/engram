# 25a: `migrate` populates the memory index

**What to build:** The `migrate` skill creates memory index entries as it imports a project's markdown memories. A `MEMORY.md` is itself an index, so each of its lines maps to one index entry. The entry's title and hook come from the line. The entry's request points at the facts the import minted from that source. The import then carries both the long-term facts and the index that orients a later recall of them.

This is a stub. It needs a grill before implementation, because `migrate` is deeper here than it looks. The grill settles the open points below.

## Open points for the grill

- The request kind for a migrated entry. A `by-ids` request pins the exact minted facts. A `by-tags` request is looser and picks up facts added later. The grill picks one and sets the `required?` default.
- The two-phase apply. The batch creates the facts first and returns their ids. The index entries need those ids, so the entry creation is a second phase, not one atomic step with the batch.
- The per-source grouping. `migrate` tracks which minted ids came from which `MEMORY.md` line, so one entry covers one source.
- The preview. The dry-run preview shows the proposed entries beside the facts, so the user reviews both before any write.

**Blocked by:** 25 (the memory index schema and routes).

**Status:** needs-info

- [ ] A grill settles the request kind, the two-phase apply, the per-source grouping, and the preview.
- [ ] `migrate` creates one index entry per `MEMORY.md` line, titled and hooked from that line.
- [ ] A migrated entry's request points at the facts the import minted from that source.
- [ ] The dry-run preview shows the proposed index entries beside the facts.
- [ ] The grill fills in the remaining acceptance criteria.
