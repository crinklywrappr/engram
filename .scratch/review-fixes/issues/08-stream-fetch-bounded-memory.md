# 08: Stream the fetch query with bounded memory

**What to build:** The fetch response streams without holding the whole result
set in memory, and it begins producing memories before the traversal finishes.

**Blocked by:** None.

**Status:** resolved

Resolution:

- `memory/query` is a lazy `cons`/`lazy-seq` traversal. It holds only the set of
  seen entity ids, the current frontier, and one pulled memory at a time. It
  never holds all the pulled maps at once. Memory is bounded by the count of
  distinct results, not by the size of the payload.
- It emits each memory as it pulls it, so the NDJSON writer gets the first memory
  before the walk finishes. That is the time-to-first-token behavior the stream
  wants.
- The transitive related-by-src walk dedupes on entity id and terminates. A
  related value that points at a src with no memory yields nothing extra.
- Note for the record: the earlier query was not the memory hog it looked like.
  It also held only ids and pulled one memory at a time. The real gain here is
  incremental production, not lower memory.

- [x] The fetch response streams. Memory is bounded by the id and frontier sets,
      not by the full pulled result.
- [x] The traversal dedupes and terminates on a cycle, a diamond, and a
      self-loop.
- [x] Tests cover the cycle, diamond, self-loop, and dangling-related shapes.
