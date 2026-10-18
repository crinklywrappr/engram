# 01: Evaluate enso as the http-kit server replacement

**What to build:** A spike that decides whether [enso](https://github.com/mpenet/enso) replaces http-kit as the engram web server. enso is a newer, pure-Clojure HTTP server, still in beta, that offers HTTP/2 and that wins http-kit in published micro-benchmarks. The spike swaps enso in behind the server component. It confirms engram still serves every route, including the streaming recall path. It benchmarks enso against http-kit on engram's real recall workload. It judges whether enso opens a path to HTTP/2 and SSL. It ends in a written go/no-go recommendation.

This is server-side only. The SSH proxy (`bin/engram-proxy`) uses the http-kit *client* under babashka. That client stays. This spike does not touch it.

## Why the swap point is small

http-kit's server lives in one place: the `:engram.web/server` Integrant component in `src/engram/system.clj`. It calls `run-server` to start and `server-stop!` to halt. The engram handler is a plain Ring handler. So enso has to do two things: run a Ring handler, and stop cleanly under Integrant.

## The one risky unknown

The recall route streams NDJSON. The response body is a standard Ring `piped-input-stream`, not an http-kit async channel. The stream can be arbitrarily large, so the server must flush it incrementally and hold constant memory. The spike must confirm enso serves a streaming `InputStream` body this way. If enso buffers the whole body before it writes, that is a blocking finding and the spike recommends no-go on those grounds alone.

## What to measure

Benchmark enso against http-kit on engram's real recall path, not a synthetic echo route. Drive a recall that returns a stream of memories plus the transitive related closure. Report latency and throughput for both servers on the same workload and the same machine. State the memory profile of the streaming body under each server. A raw micro-benchmark number from the enso project is not enough. The question is whether enso is faster for engram.

## What to assess

- HTTP/2: does enso serve HTTP/2, and what must engram change to use it over the SSH stdio transport.
- SSL: does enso give engram a cleaner path to SSL than http-kit does. Record what that path looks like. engram does not need to take it now.

## The deliverable

A findings file in this feature directory that records the benchmark numbers, the streaming-body result, the HTTP/2 and SSL assessment, and a plain go/no-go recommendation. The prototype code stays on a branch and does not merge unless the recommendation is go.

## Prior research

A background research agent studied enso against its primary sources. The findings live in `.scratch/enso-evaluation/research-enso.md`. Read that file first. It confirms enso streams an `InputStream` body incrementally, so the streaming gate passes on paper. It records the Ring handler API, the start and stop functions, the HTTP/2 and TLS stories, and the beta maturity status. The published enso benchmarks all use a tiny body, so they do not answer engram's streaming question. This spike must still measure the real recall path.

## Outcome: go

The spike recommends enso, and engram adopted it on master. Two servers ran the same handler over the same store. Serial recall latency matched within noise. datalevin query and JSON serialization are over 95% of each request, so the server choice did not move latency. The allocation profile confirmed it. datalevin is 72 to 86% of per-recall allocation, and the server layer is under 4%. enso streams the NDJSON `InputStream` body incrementally, so the streaming gate passed. enso serves HTTP/2 and terminates TLS itself. http-kit does neither. The HTTP/2 flexibility decided it. The benchmark code and the flamegraphs live on the `enso-eval-bench` branch.

**Blocked by:** None (can start immediately).

**Status:** done

- [x] enso serves every engram route behind the `:engram.web/server` component, with Integrant start and clean halt.
- [x] The NDJSON recall path streams through enso with incremental flush and constant server memory, and the finding is recorded either way.
- [x] The full test suite passes against the enso-backed server.
- [x] A benchmark compares enso and http-kit on engram's real recall workload, on one machine. It reports latency, throughput, and the streaming memory profile for each.
- [x] The findings file assesses enso's HTTP/2 support and what engram must change to use it.
- [x] The findings file assesses whether enso gives engram a cleaner path to SSL than http-kit.
- [x] The spike ends in a written go/no-go recommendation, backed by the numbers.
- [x] The prototype stays on a branch and does not merge unless the recommendation is go.
