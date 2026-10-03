# 08: Coalesce and debounce the stat flush

**What to build:** The stat flush fires on a debounce, so a burst of fetches coalesces into one write. Ticket 07 gives an agent buffer that a prompt flush drains. This work replaces that prompt flush with a scheduled one. A scheduler reschedules a one-shot flush on each fetch and cancels the prior scheduled flush. A maximum wait caps the postponement, so a sustained burst still flushes on time. The scheduler hands the flush to the agent, so the write stays serialized on the one consumer. The buffer keyed by pair means memory stays bounded by the number of distinct pairs, not by call volume. Full findings live in the sibling research note at `research/coalescing-agent-debounce-flush.md`.

**Blocked by:** 07 (the agent buffer and the flush action exist first).

**Status:** resolved (commit e1310c0)

- [x] The flush fires on a debounce, not after every fetch.
- [x] A burst of fetches of one pair coalesces into one write with the summed count.
- [x] Each fetch reschedules the flush and cancels the prior scheduled flush.
- [x] A maximum wait caps the postponement, so a sustained burst still flushes.
- [x] The scheduler triggers the flush only by handing it to the agent, and never writes itself.
- [x] The scheduler is cancelled on halt before the final drain.
- [x] The buffer memory stays bounded by the number of distinct pairs.
