# 09: Make engram-proxy flush the response incrementally

**What to build:** The first NDJSON line leaves engram-proxy as soon as the
server produces it. It does not wait on the output buffer or the final flush. The
proxy still never holds the whole response body in memory.

**Blocked by:** None (can start immediately).

**Status:** wontfix

**Decision:** Not doing this. An early per-line flush matters only to a client
that consumes the fetch as a stream. That consumption is ticket 10, which we are
not doing. No non-Claude consumer is planned. See
`../research/10-ttft-streaming.md`.

Finding: the proxy already streams. It reads the server body with http-kit
`:as :stream` and copies it in small chunks, so it does not hold the whole body
in memory. The gap is flush timing. `System/out` buffers, and the proxy flushes
once at the end. So the first bytes can wait for the buffer to fill or for the
stream to close.

- [ ] The proxy flushes after each chunk or line, so the first memory reaches the
      client without waiting for the whole stream.
- [ ] The streaming copy stays. The proxy never holds the whole response body in
      memory.
- [ ] A test or a manual check shows that a first line reaches the client before
      the server closes the stream. A slow server that emits a line, pauses, then
      emits another is one way to show it.
- [ ] `GET /config` and `GET /stats` still return their JSON unchanged.
