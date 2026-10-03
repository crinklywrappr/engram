# 06: Shorten the recall wire keys

**What to build:** A recalled memory uses short keys on the wire. Long keys repeat on every line of the NDJSON stream, so each one multiplies by the row count. Choose a short key for each recall field, and apply it in the wire shaping and the header line. Update the recall response schemas, the glossary, the README, the recall skill doc, and the tests. Record each short key against its full sense, so the client contract stays clear.

**Blocked by:** 05 (Drop timestamps from the recall row).

**Status:** wontdo

Closed wontdo on 2026-09-29. This ticket shortens the JSON key names. We misread the rank-6 idea as shortening the memory id. Short keys are not wanted.

- [ ] Each recalled memory field uses its short key on the wire.
- [ ] The NDJSON header line uses the same short keys.
- [ ] The glossary, the README, and the recall skill doc map each short key to its full sense.
- [ ] The full test suite passes.
