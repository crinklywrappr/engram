# 02: Rename the stat writer to the recall family

**What to build:** The stat writer names its parts after the recall count, the domain term settled in `CONTEXT.md`. The rename reaches state keys, parameters, function names, docstrings, and tests. This stays internal. No wire shape changes and no behavior changes.

**Blocked by:** 01, because this operates on the split layout. `CONTEXT.md` already carries the `Recall count` term.

**Status:** ready-for-agent

- [ ] `plan-fetch` becomes `plan-recalls`.
- [ ] The `:buffer` state and its parameters become `pending-recalls`.
- [ ] `existing` becomes `extant-recalls`, the stored recalls it reads for a pair.
- [ ] `write-buffer` becomes `flush-buffer`, a verb that matches its action.
- [ ] The `stats` read function becomes `recalls`, called as `stats/recalls`.
- [ ] The full test suite passes with no behavior change.
