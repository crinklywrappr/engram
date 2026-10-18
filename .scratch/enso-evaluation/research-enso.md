# Research: enso as an http-kit replacement

All claims trace to primary sources. The main source is the `mpenet/enso`
repository, cloned at HEAD commit `8a8dfac`, 2026-10-14. That covers its Java
and Clojure source, its `doc/` files, `deps.edn`, `CHANGELOG.md` and `LICENSE`.
The http-kit source is the other primary source. Each claim cites its file and
line.

## Bottom line

enso clears the streaming bar. It streams a Ring `:body` that is a
`java.io.InputStream` incrementally, with bounded memory and real
backpressure, on HTTP/1.1, HTTP/2 and HTTP/3. It does not buffer the whole
body first. The streaming gate is a GO.

The code path is direct. The Clojure adapter passes an `InputStream` body
straight to the Java writer with no copy (`enso.clj` lines 61-64). The Java
writer reads it in small fixed slices and flushes each one
(`ResponseWriter.java` lines 643-663 for HTTP/1.1, `Http2Writer.java` lines
738-788 for HTTP/2). Memory stays near the buffer size (8 KiB on HTTP/1.1,
one 16 KiB DATA frame plus flow-control credit on HTTP/2).

One caveat for the later benchmark task: enso's published numbers use a
13-byte `Hello, World!` body, not a large stream (`doc/performance.md` line
13). The numbers say nothing about engram's NDJSON stream. The spike must
still measure the real recall path.

## 1. Streaming response body

### How an InputStream body flows

The Clojure adapter classifies the body in `coerce-body`. An `InputStream` is
a "fast path recognised by the Java writer directly, zero adapter cost". The
adapter returns the stream as-is, with no read and no copy.

Source: `src/clj/s_exp/enso.clj` lines 59-64:

```clojure
(cond
  ;; fast paths recognised by the Java writer directly, zero adapter cost
  (or (nil? body) (string? body) (bytes? body)
      (instance? java.io.InputStream body)
      (instance? java.io.File body))
  body
```

The Java core then classifies the body as `BODY_STREAM`.

Source: `src/java/com/s_exp/enso/core/ResponseHead.java` lines 213-215:

```java
} else if (body instanceof InputStream in) {
    bodyKind = BODY_STREAM;
    stream = in;
```

engram's body has no Content-Length. The `frame()` method then leaves
`contentLength = -1`. An `InputStream` has no known length. `knownLength()`
has no `BODY_STREAM` case, so it gives back -1. engram sets no handler
Content-Length either.

Source: `ResponseHead.java` lines 226-249 (`frame`) and 251-275
(`knownLength`, which has cases only for NONE, BYTES, ASCII and FILE).

### HTTP/1.1: incremental chunked writes

With `contentLength < 0` and a streamed kind, the HTTP/1.1 writer uses chunked
transfer encoding.

Source: `src/java/com/s_exp/enso/http1/ResponseWriter.java` lines 356-368, then
the `BODY_STREAM` case lines 445-464:

```java
case ResponseHead.BODY_STREAM -> {
    inlineOnly = false;
    long declared = h.declaredLength();
    if (chunked) {
        writeChunked(h.stream());
    } else if (declared >= 0) {
        ...
    } else {
        bodyBytesWritten = h.stream().transferTo(out);
    }
}
```

`writeChunked` reads the stream in slices no larger than the per-connection
scratch buffer and writes each slice as its own chunk. The buffer is
`ChunkedWriter.DEFAULT_BUFFER_BYTES`, which is 8192 bytes.

Source: `ResponseWriter.java` lines 643-663:

```java
private void writeChunked(InputStream body) throws IOException {
    byte[] chunk = chunkScratch();
    int room = ChunkedWriter.SIZE_LINE_ROOM;
    long total = 0;
    while (true) {
        int n = body.read(chunk, room, chunk.length - room - 2);
        if (n < 0) break;
        if (n == 0) continue;
        int start = ChunkedWriter.frame(chunk, room, n);
        out.write(chunk, start, room + n + 2 - start);
        total += n;
    }
    out.write(CHUNK_END);
    bodyBytesWritten = total;
}
```

`chunkScratch()` allocates one 8 KiB buffer per connection and reuses it
(`ResponseWriter.java` lines 634-641). The loop reads at most one buffer, frames
it, and writes it. It never holds the whole body.

Once pending bytes reach `COALESCE_HIGH_WATER` (32768 bytes), the writer
stream flushes to the socket. A single write of at least `DIRECT_WRITE_BYTES`
(8192 bytes) skips the buffer and goes straight to the transport.

Source: `ResponseWriter.java` lines 66-71 (constants) and the `Output` class
lines 179-214. A direct write "goes straight to the transport after what is
pending" (lines 189-200).

Backpressure on HTTP/1.1 is the kernel socket. When the TCP send buffer is
full, `out.write` blocks. A slow client then slows the `read`/`write` loop.
The server does not read ahead of what it can write.

### HTTP/2: incremental DATA frames with flow control

The HTTP/2 path reads the same `InputStream` in bounded slices and sends each
slice as DATA frames, gated by the stream and connection flow-control windows.

Source: `src/java/com/s_exp/enso/http2/Http2Exchange.java` lines 382-406
(`BODY_STREAM`):

```java
case ResponseHead.BODY_STREAM -> {
    InputStream in = rh.stream();
    rh.disownBody();
    try (in) {
        ...
        long left = declared >= 0 ? declared : inMemory ? in.available() : Long.MAX_VALUE;
        while (left > 0) {
            int r = writer.readInto(s, in, left);
            if (r < 0) break;
            left -= r;
        }
        ...
        writer.finish(s);
    }
}
```

`readInto` reads into a per-stream ring buffer in pieces no larger than one
DATA frame (`DATA_FRAME_MAX`, 16384 bytes), bounded by the send window.

Source: `src/java/com/s_exp/enso/http2/Http2Writer.java` lines 93-113 (frame and
chunk size constants) and 738-788 (`readInto` / `transferInto`, which read
`in.read(ring, pos, room)` in a loop).

Backpressure is the HTTP/2 flow-control window plus `:max-buffered-bytes`. The
option doc states the server holds "streamed HTTP/2 response bytes" against
that budget. Above three quarters of the budget it throttles connections over
their share (`enso.clj` line 406, the `:max-buffered-bytes` doc). So a slow
reader cannot force the server to buffer the whole stream.

### Other body types

- A `seq`/lazy-seq body streams element by element, each element `str`'d and
  written as a chunk, flushed at the end. It is not realized into one string.
  Source: `enso.clj` lines 80-86 (the `(seq? body)` branch). The
  `doc/handlers.md` line 69 says each element is `str`'d and buffered 8 KiB at a
  time.

- A function body `(fn [ChunkedWriter])` is enso's streaming extension for SSE
  and long-poll. It writes and flushes on demand. Source: `enso.clj` lines
  66-74, `doc/handlers.md` lines 90-122, and `ChunkedWriter.java` whole file.
  The writer "Buffers small writes and emits them whenever the buffer fills, so
  memory stays bounded by the buffer size" (`ChunkedWriter.java` lines 18-20).

- A `ring.core.protocols/StreamableResponseBody` works when
  `ring.core.protocols` is on the classpath. Source: `enso.clj` lines 88-99.
  engram does not need this path. engram uses `piped-input-stream`, which
  returns a plain `InputStream` (`BODY_STREAM`).

### Note on ring.util.io/piped-input-stream

engram's body is the `InputStream` from `ring.util.io/piped-input-stream`. That
is a plain `java.io.PipedInputStream`, so it takes the `BODY_STREAM` fast path
above. No ring protocol extension is needed for it. enso reads it in 8 KiB
slices on HTTP/1.1 and 16 KiB slices on HTTP/2. So the producer thread and the
socket writer move in lockstep through the pipe buffer. Memory stays bounded.

## 2. Ring handler compatibility and lifecycle

enso runs an ordinary synchronous Ring handler, `(fn [request] response)`,
returning a Ring response map. This is engram's shape.

Source: `doc/handlers.md` lines 3-6: "A handler is a plain Ring function,
`(fn [request] response)`." And `enso.clj` lines 729-737, where a non-async
handler is wrapped as:

```clojure
(reify RingHandler
  (handle [_ request]
    (->response (handler request))))
```

### Start API

`s-exp.enso/run-server` starts the server and returns an `EnsoServer`. It is
the http-kit `run-server` equivalent.

Source: `enso.clj` lines 707-746. Signature:

```clojure
(defn run-server
  (^EnsoServer [handler] (run-server handler nil))
  (^EnsoServer [handler {:keys [error-handler async] :as opts}]
   ...
   (.start server)
   server))
```

Basic use (`README` / `doc`):

```clojure
(def server (enso/run-server (fn [req] {:status 200 :body "hello"}) {:port 8080}))
```

### Stop API

`s-exp.enso/stop` stops the server. It is the http-kit `server-stop!`
equivalent.

Source: `enso.clj` lines 759-762:

```clojure
(defn stop
  "Stops `server`. In-flight requests complete, open connections close."
  [^EnsoServer server]
  (.close server))
```

`:shutdown-timeout` (default 10000 ms) bounds how long `stop` waits for
in-flight requests before a force close (`enso.clj` lines 387-389). There is
also `s-exp.enso/port`, which returns the actual listening port (useful with
`:port 0`), at `enso.clj` lines 754-757.

### Integrant fit

The start returns a value and the stop takes that value, so an Integrant
component maps cleanly: `init-key` calls `run-server`, `halt-key!` calls
`stop`. The current engram component calls http-kit `run-server` to start and
`server-stop!` to halt (`.scratch/enso-evaluation/issues/01-...md` line 9). The
swap is one call each.

### Async handlers

enso supports Ring 3-arity async handlers with `:async true`, where the handler
is `(fn [request respond raise])`. Source: `enso.clj` lines 587-588 and 690-705,
and `doc/handlers.md` lines 124-140. engram does not need this. engram uses
sync handlers with streaming bodies, which is enso's default and "usually the
simpler choice" (`doc/handlers.md` line 139).

## 3. HTTP/2

enso serves HTTP/2 both ways.

- `:http2 true` serves "h2" over TLS with ALPN. It needs `:ssl-context` (or a
  cert/key file pair). The `enso.clj` lines 464-466 doc states that h2 goes over
  TLS with ALPN, and that cleartext HTTP/2 is `:http2c`.

- `:http2c true` serves cleartext HTTP/2 with prior knowledge (RFC 9113 §3.3)
  on a plain listener next to HTTP/1.1. Source: `enso.clj` lines 467-469.

So h2c cleartext needs no TLS. h2 over TLS needs a TLS context. There is no
`Upgrade: h2c` path. That method is deprecated by RFC 9113 and not offered
(`enso.clj` line 469).

### Relevance to engram's SSH stdio transport

engram runs behind an SSH stdio proxy (`.scratch/enso-evaluation/issues/01-...md`
lines 5-6, 21). HTTP/2 needs either TLS (h2) or a bytestream where the client
speaks the HTTP/2 preface first (h2c). Over a raw stdio pipe, `:http2c` is the
only candidate, and both ends must agree to speak HTTP/2 with prior knowledge.
This is a design change for engram's client and proxy, not a drop-in. The
server side supports it. The transport and client do not yet.

## 4. TLS / SSL

enso terminates TLS itself. It offers two ways to configure it.

- `:ssl-context` takes a `javax.net.ssl.SSLContext`. "when set the TCP listener
  speaks TLS." Source: `enso.clj` lines 426-428.

- `:ssl-cert-path` plus `:ssl-key-path` take PEM files directly. "with
  `:ssl-key-path` the TCP listener speaks TLS, reloading on change." Source:
  `enso.clj` lines 432-437. The key must be unencrypted PKCS8, PKCS1 or SEC1
  (line 437).

enso also supports mutual TLS (`:ssl-ca-path`, `:ssl-need-client-auth`,
`:ssl-want-client-auth`) and hot cert reload (`:ssl-cert-reload-interval`). It
supports a cert provider for rotation (`:ssl-context-provider`), plus ALPN,
cipher, and protocol control. Source: `enso.clj` lines 425-461.

### Compare to http-kit

http-kit's own `run-server` docstring lists no TLS option. Its documented
options are `:ip`, `:port`, `:max-body`, `:max-ws`, `:max-line`,
`:proxy-protocol`, `:server-header` and a few more. None of them covers SSL
contexts, keystores or certificates. Source: http-kit
`src/org/httpkit/server.clj` `run-server` docstring (read via GitHub raw).

So enso gives engram a direct TLS path (PEM files or an `SSLContext` passed to
`run-server`). http-kit needs a separate `with-channel`/`SslContext` setup or
an external terminator. This is a clear win for enso on the SSL assessment.

## 5. Maturity / status

- Status: beta. The README states that the option names and the API are not
  yet stable between releases. Source: `README.md` line 15.

- Version: the latest git tags are `v1.0.0-beta94`, `v1.0.0-beta92`,
  `v1.0.0-beta91`, `v1.0.0-beta89` (from `git ls-remote --tags`). So the latest
  release train is `1.0.0-beta`. The cloned HEAD is commit `8a8dfac`
  (2026-10-14).

- Version discrepancy to flag. The committed `CHANGELOG.md` top entry reads
  `## 1.0.0-alpha85 — 2026-10-12`, while the git tags use the `beta` prefix
  (`v1.0.0-beta94`). So the CHANGELOG in the cloned source lags the tag naming,
  or the two use different prefixes for the same train. Treat the exact version
  string with care and read the published Clojars version before adopting.
  Source: `CHANGELOG.md` line 3 vs the tag list.

- Requirements: JDK 21 or later and Clojure 1.12 or later. Source: `README.md`
  lines 99-101 and `deps.edn` (which pins `org.clojure/clojure 1.12.3`).

- Dependencies: pure Clojure plus its own Java core. `deps.edn` declares only
  `{org.clojure/clojure {:mvn/version "1.12.3"}}` and no server dependency. It
  does not pull in Netty, Jetty, or the JDK `HttpServer`. The README calls it
  "no dependency besides Clojure. The core is written in Java for Ring"
  (`README.md` lines 12-13). HTTP/3 is the one exception: it needs a
  platform-specific native artifact (such as `com.s-exp/enso$linux-amd64`) that
  carries a JNI shim over libquiche (`README.md` lines 29-32). engram does not
  need HTTP/3, so the base artifact alone has no native code.

- License: Mozilla Public License 2.0, Copyright Max Penet. Source: `LICENSE`
  file (MPL 2.0 header) and `README.md` lines 124-129.

- Production notes. The beta API churn is the main adoption risk: the CHANGELOG
  shows a large option rename between `alpha56` and the latest entry
  (`CHANGELOG.md` lines 11-90). enso has a conformance and test story: it claims
  h2spec and Autobahn passes, runs fuzz targets (`fuzz/`), and a soak test rig
  (`test/`, `doc/testing.md`). Virtual threads mean JDK 21 is a hard floor.

## 6. Benchmarks

### What the repo claims vs http-kit

For HTTP/1.1 the README claims enso has "the highest throughput on every
scenario above". It claims enso "allocates 376 bytes per HTTP/1.1 request where
the others allocate 3.3 to 4.1 KB". Source: `README.md` lines 93-96.

The claim traces to `doc/performance.md`, the "Comparison with other servers"
section (lines 142-313). The relevant primary numbers, median of three rounds,
Apple M1 Pro, Temurin 25.0.2, measured 2026-10-14 at revision `21df5c8`:

HTTP/1.1 non-pipelined (`doc/performance.md` lines 194-200):

| Server | req/s | alloc B/req | p99 ms |
|---|---:|---:|---:|
| Ensō | 127,370 | 376 | 1.50 |
| http-kit | 126,709 | 3,705 | 0.96 |

So on plain HTTP/1.1 enso and http-kit are within 0.5% on throughput.
http-kit has the lower p99. enso's edge there is allocation, not speed.

HTTP/1.1 pipelined depth 16 (`doc/performance.md` lines 204-210):

| Server | req/s | alloc B/req |
|---|---:|---:|
| Ensō | 1,476,909 | 234 |
| http-kit | 407,769 | 3,935 |

Here enso is about 3.6x http-kit. This gap is pipelining-specific.

WebSocket echo (`doc/performance.md` lines 257-263): enso 1,044,344 msg/s vs
http-kit 526,876 msg/s, "twice as many messages per second as http-kit, at half
the latency" (lines 265-266).

http-kit has no HTTP/2 or HTTP/3 server, so those tables omit it
(`doc/performance.md` lines 218 and 231-232).

### Methodology credibility

The methodology is unusually transparent for a project self-benchmark:

- Each server runs in its own JVM, same JDK, same flags (`-Xmx1g`), load
  generator in another process. Source: `doc/performance.md` lines 143-153.
- Allocation is measured inside each server JVM via
  `ThreadMXBean#getTotalThreadAllocatedBytes`, not sampled, and counts the
  whole JVM, so it is an upper bound for enso's own code. Source: lines 112-118.
- Standard load tools: wrk for HTTP/1.1, h2load for HTTP/2. Source: lines
  185-190 and 218.
- The repo lists honest caveats: server and client share 8 CPUs, closed-loop
  percentiles are not coordinated-omission corrected, and the WebSocket test
  hits http-kit's fixed listen backlog of 50. Source: lines 278-296.
- It is reproducible: `clojure -M:bench/compare`, with the driver at
  `bench/enso/compare.clj` and each peer under `bench/enso/peer/`. Source:
  lines 298-313 and the `deps.edn` `:bench/*` aliases.

### Two credibility limits for engram

1. The response body is 13 bytes (`Hello, World!`). Source: `doc/performance.md`
   line 13 and 170. None of the published numbers test a large streaming body,
   which is engram's whole workload. The low allocation figure in particular is
   a tiny-response artifact.

2. Server and load generator share one laptop's CPUs, so "A server that needs
   less CPU leaves more for the client" (line 280-282). enso's lower allocation
   helps it here in a way that will not always carry to a dedicated machine.

The repo's numbers are credible for what they measure, a tiny response. They do
not answer whether enso is faster on engram's NDJSON recall stream. The spike
must measure that path directly, as the issue requires.

## Sources

Repository (cloned at HEAD `8a8dfac`, 2026-10-14):

- https://github.com/mpenet/enso
- `src/clj/s_exp/enso.clj` (Ring adapter, run-server, stop, coerce-body,
  options)
- `src/java/com/s_exp/enso/core/ResponseHead.java` (body classification,
  content-length framing)
- `src/java/com/s_exp/enso/http1/ResponseWriter.java` (HTTP/1.1 body writing,
  writeChunked, Output buffer)
- `src/java/com/s_exp/enso/http2/Http2Exchange.java` (HTTP/2 body dispatch)
- `src/java/com/s_exp/enso/http2/Http2Writer.java` (readInto, DATA framing,
  flow control)
- `src/java/com/s_exp/enso/api/StreamingBody.java` (streaming body interface)
- `src/java/com/s_exp/enso/api/ChunkedWriter.java` (buffered chunk writer)
- `src/java/com/s_exp/enso/api/ChunkedOutputStream.java` (OutputStream over the
  writer)
- `src/java/com/s_exp/enso/api/Response.java` (response value type)
- `doc/handlers.md` (handler contract, body types, streaming, async, WebSocket)
- `doc/performance.md` (current numbers and the cross-server comparison)
- `deps.edn` (dependencies, JDK/Clojure, bench aliases)
- `CHANGELOG.md` (version, option renames)
- `README.md` (status, requirements, license, benchmark summary)
- `LICENSE` (Mozilla Public License 2.0)
- git tags via `git ls-remote --tags` (`v1.0.0-beta94` latest)

http-kit (primary source, for comparison):

- https://github.com/http-kit/http-kit
- `src/org/httpkit/server.clj` (run-server options, send! docstring)

engram issue context:

- `/home/crinklywrappr/engram/.scratch/enso-evaluation/issues/01-evaluate-enso-http-kit-replacement.md`
