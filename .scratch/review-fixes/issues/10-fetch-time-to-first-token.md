# 10: Consume the fetch stream with time-to-first-token

**What to build:** The engram-recall skill reads the fetch response as it
arrives. It does not wait for the whole stream. Claude can begin using the first
memories sooner.

**Blocked by:** 09 (the proxy must flush early before the client can read early).

**Status:** wontfix

**Decision:** Not doing this. Research (`../research/10-ttft-streaming.md`) found
the `Monitor` tool gives real time-to-first-token: it feeds each stream line back
to Claude as an event, with no polling. But any incremental consumption costs
more tokens. Claude Code re-sends the full conversation each turn. So it pays off
only for a large or slow fetch. Recall is small and sparse, so the token cost is
not worth it. Ticket 09 is dropped together with this one.

Context: until the process exits, a foreground tool call returns nothing. So
Claude cannot stream a subprocess into its context during a single call. That is
a runtime property, not an engram limit. The achievable pattern is a background
run plus incremental reads of the growing output file, which only helps once the
proxy flushes early.

Open questions to settle before implementation:

- How early can a background command's output be read, and does incremental
  reading give a real latency win for the fetch?
- Is a background run plus incremental reads the right pattern, or is there a
  better runtime mechanism?
- What does the skill instruct for the fetch: a simple foreground call, or a
  background call with incremental reads for time-to-first-token?

Acceptance criteria (provisional, to firm up once the questions settle):

- [ ] The skill documents the fetch-consumption pattern that gives the earliest
      useful read.
- [ ] A written finding records the runtime streaming limit and the chosen
      tradeoff.
