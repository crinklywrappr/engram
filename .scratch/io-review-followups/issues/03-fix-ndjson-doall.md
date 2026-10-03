# 03: Fix the missing doall so an NDJSON recall streams past 32 memories

**What to build:** An NDJSON recall streams every matched memory and every related memory. Today the stream realizes the recall sequence on a background thread that the piped stream starts. Past the first 32-element chunk the stream breaks. Force the recall sequence in the request thread. Then stream the realized result. This holds the realized recall in memory, which stays bounded by the memory graph of one user. Integrant owns the connection for the whole process, so the force is safe.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] A recall whose match-plus-related result exceeds 32 memories streams all of them over NDJSON.
- [ ] A regression test seeds more than 32 memories and counts the NDJSON lines.
- [ ] The full test suite passes.
