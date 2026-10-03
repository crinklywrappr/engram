# 04: Strip empty and null fields from responses

**What to build:** A JSON response omits an empty collection and a null value. A memory with no tags, no related links, and no timestamps serializes without those keys. Set the `:strip-nils` option on the NDJSON object mapper and on the JSON fallback mapper. The JSON fallback mapper serves every JSON route. Make sure that the batch body and the error bodies still carry the keys the client needs.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] A recalled memory with no tags and no related links omits those keys.
- [ ] The NDJSON mapper and the JSON fallback mapper both strip empty and null fields.
- [ ] The batch response and the error responses still carry every key the client needs.
- [ ] The full test suite passes.
