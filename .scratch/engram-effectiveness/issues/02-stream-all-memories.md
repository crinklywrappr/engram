# 02: Stream every memory over NDJSON at `GET /memories`

**What to build:** `GET /memories` returns every memory the caller owns. The response is an NDJSON stream. The stream sends one memory per line. A new function in `engram.memory` returns the memories as a lazy sequence. The route streams from the lazy sequence. Server memory stays flat even for a large store. The stream filters by user. One user never sees another user's memories. Each line carries the same memory shape as the recall stream.

**Blocked by:** None (can start immediately).

**Status:** done

- [x] `GET /memories` streams every memory the caller owns, one per line.
- [x] The new `engram.memory` function returns a lazy sequence.
- [x] The route streams from the lazy sequence and never holds the full set in memory.
- [x] Another user's memories never appear in the response.
- [x] Each line carries the same memory shape as the recall stream.
