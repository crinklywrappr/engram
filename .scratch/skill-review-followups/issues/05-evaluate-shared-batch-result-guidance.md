# 05: Evaluate extracting the shared batch-result guidance

**What to build:** Five skills repeat the same batch-result guidance almost word for word: the 200 success shape, the 422 atomic-failure shape, and the tag-error configuration refresh. This ticket decides whether to lift that guidance into one shared reference that each skill points to, or to keep it inline in each skill. Weigh the drift risk of five copies against the value of a skill that stands alone. Record the decision. When the decision is to extract, do the extraction. When the decision is to keep the copies, record the reason and close the ticket.

## Outcome: keep the copies

The batch-result guidance stays inline in each skill. There is no shared reference.

A skill resolves a reference path against its own directory. So a shared file
needs a fragile `../..` path into the plugin root, or a copy in each skill. The
`../..` path couples every skill to the plugin layout and breaks the skill's
self-containment. A per-skill copy is still one copy per skill, so it saves no
drift. The skills framework puts a `references/` directory inside each skill for
this reason.

The duplicated block is about six lines. Its contract is one server endpoint's
behavior, and that rarely changes. When the contract changes, a grep finds every
copy. So the drift risk is small and the extraction cost is high.

engram-recall already holds its copy in `references/writes.md` from ticket 04. The
other four skills keep their inline copy.

**Blocked by:** 01, 02, 03, 04 (it operates on the final skill text).

**Status:** done

- [x] The ticket records a decision: extract the shared batch-result guidance, or keep it inline.
- [ ] When the decision is to extract, each skill points to one shared reference for the batch-result guidance.
- [x] When the decision is to keep the copies, the ticket records the reason.
- [x] Every edited sentence passes the blocking lint hook.
