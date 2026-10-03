# 07: Make the recall stats write non-blocking

**What to build:** A fetch returns its memories without waiting on the stats write. Today the recall handler writes the stats before it builds the response, so a fetch waits on that write. Because the call is synchronous, a failure in that write can fail the whole fetch. Two concurrent fetches of one pair can also lose an increment. Each reads the same old counts and writes an absolute value. This work moves the stats write to a single consumer, a Clojure agent whose value is a pending buffer keyed by pair. A request thread folds one fetch into the buffer with a non-blocking send and returns. A flush drains the buffer through the ticket-06 planner in one transaction. The agent applies its actions one at a time, so no two writes interleave and the increment race is closed. A flush that throws keeps the buffer for the next flush and never touches the fetch. On halt the consumer drains the buffer before the connection closes. The flush fires promptly here, and ticket 08 puts it on a debounce to coalesce bursts. The decay uses a clock captured at flush time, a bounded drift recorded as a known limitation.

**Blocked by:** 06 (the pure planner the flush calls).

**Status:** resolved (commit ea24349)

- [x] A fetch returns its memories without waiting for the stats write to finish.
- [x] The stats write runs off the request thread through one agent consumer.
- [x] The agent value is a pending buffer keyed by (user, category, label) with a count per pair.
- [x] A flush drains the buffer in one transaction through the ticket-06 planner.
- [x] Two concurrent fetches of one pair never lose an increment.
- [x] A flush failure keeps the counts for the next flush and never fails the fetch.
- [x] The consumer starts after the connection and drains the buffer on halt before the connection closes.
- [x] The decay-clock drift is documented as a known, bounded limitation.
