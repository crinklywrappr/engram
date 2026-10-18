# 04: Split engram-recall into session-start essentials and on-demand references

**What to build:** engram-recall loads at session start through the CLAUDE.md nudge, so its whole body costs tokens every session. Today it holds both the session-start steps and the full route reference. Split it. The `SKILL.md` body keeps only the session-start essentials:

- the transport
- the one-time configuration load
- the index load
- the bucket list
- a basic recall
- how to store a fact

Move the situational material into `references/` files that the model reads on demand. The situational material is the search-then-recall recovery path, the full batch section, the single-memory routes, and the 409 handling. The `SKILL.md` body points to each reference file and names the trigger for reading it. The session-start token cost drops, and no instruction is lost.

**Blocked by:** 01 (so the restructure carries the corrected text).

**Status:** done

- [x] The engram-recall SKILL.md body holds only the session-start essentials: transport, configuration load, index load, bucket list, basic recall, and storing a fact.
- [x] The search recovery path, the batch section, the single-memory routes, and the 409 handling live in `references/` files.
- [x] The SKILL.md body points to each reference file and names the trigger for reading it.
- [x] No instruction from the current skill is dropped in the split.
- [x] Every edited sentence passes the blocking lint hook.
