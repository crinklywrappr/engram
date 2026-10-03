# stat-write single consumer and batching research

> Home of this note: the repo keeps design-review tickets under
> `.scratch/design-review/issues/`. Findings live in a sibling `research/`
> folder. Two notes already sit there. This note follows the same precedent and
> lives at
> `.scratch/design-review/research/stat-write-consumer-and-batching.md`.

Every claim below traces to a primary source. The sources are six. They are the
official Clojure agent reference. They are the core.async API docs. They are the
JDK 21 `java.util.concurrent` Javadoc. They are the Datalevin 1.0.1 source. They
are the Integrant README. They are the repo code. Each claim cites a URL or a
repo file path plus an exact identifier.

## Problem statement

The prior note recommends Solution A, a serialized single-consumer for stat
writes. See
`.scratch/design-review/research/record-fetch-decay-atomicity.md`. Request
threads hand off a fetch event. One consumer performs the read-modify-write
off-thread, serialized. This removes the lost-update race by construction and it
fits ticket 07's three requirements. See
`.scratch/design-review/issues/07-non-blocking-fetch-stats-write.md`.

Today `query-handler` calls `stats/record-fetch!` synchronously on the request
thread. See `src/engram/handler.clj`, `query-handler`, line 191. The write is a
two-step read-modify-write in `src/engram/stats.clj`, `record-fetch!`. The
system wires one shared `:engram.db/conn` (reopened as `:engram.db/migrated`)
and one http-kit server through Integrant. See `src/engram/system.clj`.

This note has two parts. Part 1 compares the consumer mechanisms. Part 2
compares the collector, batching, and debounce layer.

The dependency set is fixed. `deps.edn` pins Clojure 1.12.5, Datalevin 1.0.1,
Integrant 1.0.1, http-kit 2.8.0, reitit, malli, jsonista, and telemere.
core.async is NOT present. So a core.async option adds a new dependency. An
agent, a `future`, a `BlockingQueue`, or an `ExecutorService` adds no
dependency, because each is Clojure core or the JDK.

## Part 1: the consumer mechanism

The consumer must accept a hand-off from a request thread without blocking that
thread. It must run one write at a time. An Integrant component must start it and
drain it on stop. A stat write failure must never affect the fetch.

### Option 1: Clojure agent (send-off)

Ordering. The Clojure agent reference states, "At any point in time, at most one
action for each Agent is being executed. Actions dispatched to an agent from
another single agent or thread will occur in the order they were sent." Source:
https://clojure.org/reference/agents. So one agent serializes its actions and
preserves order per sending thread.

Hand-off. `send-off` dispatches an action and returns at once. The reference
splits the two dispatch forms by workload. It ties `send` to actions that are CPU
limited. It ties `send-off` to actions that block on IO. Source:
https://clojure.org/reference/agents. A stat write does blocking IO to LMDB, so
`send-off` is the correct dispatch, not `send`.

Backpressure. An agent has no bound on its pending action queue. Under a fast
producer and a slow consumer, the queue grows without a built-in limit. This is
the unbounded-growth risk. No primary source documents a bound, because the
agent queue is unbounded by design.

Error handling. The reference states that an exception thrown by an action gets
cached in the agent itself. It adds a second rule. When an agent holds a cached
error, every later interaction throws at once, until a caller clears the error.
Source: https://clojure.org/reference/agents. So a bare agent can wedge after one
failed write. The fix is `set-error-mode!` to `:continue` with a
`set-error-handler!`. In `:continue` mode the agent keeps processing after a
failed action. The failure stays isolated in the handler and never reaches the
request thread, because `send-off` already returned. This meets ticket 07's
failure isolation.

Shutdown and drain. `await` blocks the caller until the agent finishes all
actions the calling thread dispatched so far. Source:
https://clojure.org/reference/agents, "Block waiting for an Agent," `await` and
`await-for`. So an Integrant `halt-key!` can call `await` on the agent to drain
pending writes before it closes the connection. Agents also hold JVM threads. The
reference states, "Use shutdown-agents to terminate these threads and allow
shutdown." Source: https://clojure.org/reference/agents. Note that
`shutdown-agents` is process-global, so a per-component halt uses `await` and
leaves `shutdown-agents` for full process exit.

Testability. An agent is easy to test. A test can `send-off` a work item and then
`await` the agent to force the write, with no timing guess. `agent-error` reads a
cached failure.

### Option 2: BlockingQueue drained by one consumer thread, or a single-thread ExecutorService

Two shapes exist. Both are JDK, so both add no dependency.

Shape 2a, an explicit `java.util.concurrent.BlockingQueue` with one dedicated
consumer thread that loops on `take`.

Shape 2b, an `ExecutorService` from `Executors.newSingleThreadExecutor()`, where
each submitted task is one stat write.

Ordering. `Executors.newSingleThreadExecutor()` states, "Tasks are guaranteed to
execute sequentially, and no more than one task will be active at any given
time." Source:
https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Executors.html,
`newSingleThreadExecutor()`. A `LinkedBlockingQueue` and an `ArrayBlockingQueue`
both order elements FIFO. `LinkedBlockingQueue` states, "This queue orders
elements FIFO (first-in-first-out)." Source:
https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/LinkedBlockingQueue.html.
`ArrayBlockingQueue` states the same FIFO wording. Source:
https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ArrayBlockingQueue.html.
So one consumer over a FIFO queue runs writes one at a time in arrival order.

Hand-off and backpressure. `BlockingQueue` supports a non-blocking insert. The
Javadoc describes `offer(e)` on a queue with room. It inserts the element at once
and returns `true`. On a full queue it returns `false` and does not block. Source:
https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/BlockingQueue.html.
So a request thread calls `offer` and never blocks. A bounded queue gives
backpressure by construction. Per the Javadoc, `ArrayBlockingQueue` is a bounded
blocking queue backed by an array, and its capacity cannot change after creation.
Source:
https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ArrayBlockingQueue.html.
Per the Javadoc, `LinkedBlockingQueue` is an optionally-bounded blocking queue.
When a caller omits the bound, its capacity defaults to `Integer.MAX_VALUE`.
Source:
https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/LinkedBlockingQueue.html.
So a bound is a constructor argument. An unbounded queue can grow like the agent.
The single-thread `ExecutorService` from `newSingleThreadExecutor()` sits on an
unbounded queue by default. Source:
https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Executors.html.
For a hard bound, an explicit `ArrayBlockingQueue` or a bounded
`LinkedBlockingQueue` with an `offer` supplies it. A dropped `offer` on a full
queue loses one stat event, which is acceptable for approximate popularity
signals.

Error handling. Failure isolation is the consumer loop's job. The consumer body
wraps each write in a try/catch and logs. The request thread already returned
after `offer`, so a write failure never reaches the fetch. For shape 2b, an
uncaught task exception can end silently inside a `submit` future, so the task
body itself must catch and log.

Shutdown and drain. `ExecutorService` gives a documented two-phase drain. Per the
Javadoc, `shutdown()` starts an orderly shutdown. It runs the already-submitted
tasks and accepts no new task. `awaitTermination` blocks until the tasks finish,
the timeout elapses, or the thread gets interrupted. `shutdownNow()` tries to
stop the running tasks. It halts the waiting tasks and returns a list of the
tasks that awaited execution. Source:
https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ExecutorService.html.
So an Integrant `halt-key!` calls `shutdown()` then `awaitTermination` with a
bounded timeout, which drains the already-submitted writes before the connection
closes. Shape 2a needs a hand-written poison-pill or interrupt to end the loop,
then a join. So shape 2b has cleaner, sourced drain semantics than shape 2a.

Testability. Both shapes test well. A test can submit a task and call
`awaitTermination` to force completion, with no timing guess. This mirrors the
agent's `await`.

### Option 3: core.async channel with one consumer loop

Ordering. A channel with one consumer `go` or `thread` loop processes items in
put order, because a channel is FIFO. One consumer takes one item at a time, so
writes serialize.

Hand-off and buffers. `chan` creates a channel with an optional buffer. Source:
https://clojure.github.io/core.async/clojure.core.async.html, `chan`. A
non-blocking put exists. Per the API docs, `offer!` makes an immediate put into a
port, never blocks, and returns true on a successful put. Source:
https://clojure.github.io/core.async/clojure.core.async.html, `offer!`. So a
request thread uses `offer!` and never blocks. Buffer choice sets the overflow
rule. Per the API docs, a full `dropping-buffer` completes the put but drops the
value with no transfer. Per the API docs, a full `sliding-buffer` completes the
put, buffers the value, and drops the oldest buffered element. Source:
https://clojure.github.io/core.async/clojure.core.async.html, `dropping-buffer`
and `sliding-buffer`. A plain fixed buffer applies backpressure to a blocking
put. With `offer!`, a full fixed buffer instead returns false and drops the
event. A `dropping-buffer` drops the newest event on overflow, which suits an
approximate signal. So a fixed or dropping buffer bounds memory.

Blocking IO rule. A stat write is blocking IO. core.async's rule is that blocking
ops do not belong inside a `go` block. The README documents a check flag,
`clojure.core.async.go-checking`. The flag throws on a core.async blocking op
(`>!!`, `<!!`, `alts!!`) inside a `go` block. Source:
https://github.com/clojure/core.async/blob/master/README.md. The idiomatic split
is that a `go` block does the channel choreography and a `thread` does blocking
work. Per the API docs, `thread` runs the body in another thread and returns a
channel that receives the body result on completion, then closes. Source:
https://clojure.github.io/core.async/clojure.core.async.html, `thread`. So a
`thread` loop is the consumer for a blocking LMDB write, not a `go` loop. A `go`
loop can still orchestrate and offload the write to `thread`.

Error handling. Failure isolation is the loop's job, same as Option 2. The
consumer body wraps the write in a try/catch. The request thread already returned
after `offer!`. A thrown write does not propagate to the fetch.

Shutdown and drain. Per the API docs, `close!` closes a channel so it accepts no
more puts. The buffered data stays available to take until a taker exhausts it,
after which a take returns nil. Source:
https://clojure.github.io/core.async/clojure.core.async.html, `close!`. So an
Integrant `halt-key!` closes the channel, then the consumer loop drains buffered
items until a take returns nil, then exits. The halt then blocks on the loop's
result channel to check the drain finished before it closes the connection.
This is a clean drain, but the coordination is hand-written.

Testability. core.async tests well with a synchronous read of the consumer's
result channel. The added cost is the new dependency and the go-versus-thread
discipline.

### Comparison

| Mechanism | New dep | Bounded memory | Integrant drain | Failure isolation | Testability |
|---|---|---|---|---|---|
| Agent (`send-off`) | No (Clojure core) | No bound, unbounded queue | `await` drains, sourced | `:continue` error-mode isolates | High, `await` forces |
| BlockingQueue + one thread | No (JDK) | Yes, bounded ctor arg | hand-written pill or interrupt | try/catch in loop | High |
| Single-thread ExecutorService | No (JDK) | Default unbounded queue | `shutdown` + `awaitTermination`, sourced | try/catch in task body | High, `awaitTermination` forces |
| core.async channel + `thread` loop | Yes (adds core.async) | Yes, dropping/fixed buffer | `close!` then drain, sourced | try/catch in loop | High |

Ranked summary. The two strongest fits are the agent and the single-thread
`ExecutorService`. Both sit on the classpath today. Both give a sourced, one-call
drain (`await` for the agent, `shutdown` plus `awaitTermination` for the
executor). Both isolate a write failure. The agent wins on the smallest amount of
new code. Ordering, serialization, and off-thread dispatch come for free from
`send-off` and the agent contract. Its one weakness is the unbounded queue and the
wedge-on-error default. Both are fixable with `set-error-mode!` `:continue` and a
size guard. For a strict memory bound or the cleanest documented drain, the
`ExecutorService` wins. core.async is a strong tool. It adds a dependency for a
job the JDK and Clojure core already cover, so it ranks last on the
no-new-dependency test. The `BlockingQueue`-plus-thread shape is sound. It needs
the most hand-written lifecycle code (poison pill and join), so it ranks below
the executor.

## Part 2: collector, batching, and debounce layer

A collector sits between the producer and the consumer to reduce database load.
Under the stated load of 1 to 10 users with sparse calls, the database is not
under pressure today. So the batching layer is an optimization, not a
correctness fix.

### Coalescing per (user, category, label)

The idea. Fold many fetch events for the same triple into one write. Accumulate
the increment. Compute the decay once at flush.

Decay semantics change. The current code decays the prior weight to `now-ms` and
adds one per event. See `src/engram/stats.clj`, `record-fetch!`, and
`decay-factor`. Coalescing treats all folded events as arriving at flush time. So
N events in one flush window add N to the decayed weight at one instant, rather
than at N slightly different instants. The drift is the spread of arrival times
inside one flush window, measured against the decay half-life.

Is the drift bounded and acceptable. Yes, under the stated load. The half-life is
measured in days. The schema note in `resources/migrations/001-schema.edn`
describes an exponential-decay recent count. A flush window of seconds is a tiny
fraction of a half-life of days. So the decay error from treating a window's
events as simultaneous is negligible. The prior note reaches the same conclusion
about the elapsed-time clock caveat. That note calls the caveat bounded and small
under the stated load. Source:
`.scratch/design-review/research/record-fetch-decay-atomicity.md`, Question 5. A
longer flush window widens this drift. So the window length is the knob that
trades database load against decay precision.

### Batching across pairs in one transaction

The idea. Accumulate tx-data for many triples and transact once, so one lock
acquisition covers many pairs.

Proof from Datalevin source. `datalevin.core/transact!` has arglists
`([conn tx-data] [conn tx-data tx-meta])`. The docstring says it applies a
transaction to the underlying Datalog database synchronously. Its examples pass a
single vector that holds many datoms and many entity maps as one transaction.
Source: `datalevin/core.clj`, def `transact!`, arglist `[conn
tx-data]`, examples at the multi-datom and multi-map forms (a single `transact!`
call with `[[:db/add -1 :name ...] [:db/add -1 :likes ...] ...]` and with
`[{:db/id -1 ...} {:db/id 296 ...}]`). `datalevin.core/transact!` delegates to
`datalevin.conn/transact!`. Source: `datalevin/conn.clj`, `(defn transact!
([conn tx-data] ...) ([conn tx-data tx-meta] ...))`. The prior note establishes
that one `transact!` call runs under the single-writer LMDB lock. Source:
`.scratch/design-review/research/record-fetch-decay-atomicity.md`, Question 1,
citing `datalevin.conn/with-transaction`. So one `transact!` with tx-data for
many triples takes the write lock once and commits all pairs together. This
proves the batch-across-pairs claim from a primary source.

Note on the composite tuple. The schema declares
`:stat/user+category+label` as a `:db.unique/identity` tuple. See
`resources/migrations/001-schema.edn`. So each map in the batch upserts its own
row by that identity, and one `transact!` can upsert many distinct rows in one
write.

### Flush triggers: size, time, or both

Three triggers exist. Flush by size threshold. Flush by time interval, which is a
debounce. Flush by both, whichever comes first. A size trigger bounds memory and
worst-case staleness under bursts. When a sparse load never reaches the size
threshold, a time trigger still bounds staleness. Both together is the common
choice.

Timer options. Two first-class timers exist.

A `ScheduledExecutorService` schedules a periodic flush. It is JDK, so it adds no
dependency. It fits the same lifecycle as the single-thread consumer executor.
`ExecutorService` shutdown drains it. Source:
https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ExecutorService.html.
The `Executors` factory that creates it is on the same page as
`newSingleThreadExecutor`. Source:
https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Executors.html.

A core.async `timeout` gives a debounce inside a `go` loop. Per the API docs,
`timeout` returns a channel that closes after a set number of milliseconds.
Source: https://clojure.github.io/core.async/clojure.core.async.html, `timeout`. A
consumer `go` loop can `alts!` over the work channel and a `timeout` channel. It
flushes on the earlier of a size fill or a time tick. This adds the core.async
dependency.

Interaction with the decay clock caveat. A longer flush interval widens the
elapsed-time drift from the prior note. See
`.scratch/design-review/research/record-fetch-decay-atomicity.md`, Question 5.
The decay uses a `now-ms` captured at compute time, and coalescing pins all
window events to the flush instant. So a longer debounce means events wait
longer before the decay clock reads, which widens the gap between true arrival
and recorded arrival. Under a half-life in days and a flush interval in seconds,
this gap stays negligible. So the interval is safe to tune for load as long as it
stays far below the half-life.

### Durability tradeoff

Buffered increments live in memory. A crash before flush loses them. The exact
consequence for these stats is small. The counts are approximate popularity
signals, not a ledger. The schema note calls the recent count an
"exponential-decay recent count" and the lifetime a "monotonic lifetime count."
See `resources/migrations/001-schema.edn`. A lost flush drops a few increments
from an approximate signal, which does not corrupt any user data and does not
break recall. So the durability cost is acceptable for this data.

Graceful drain on halt. Integrant halts in reverse dependency order. The
Integrant README states, "Like Component, `halt!` shuts down the system in
reverse dependency order." Source:
https://github.com/weavejester/integrant/blob/master/README.md. So a consumer or
collector component that depends on `:engram.db/conn` (via `ig/ref`) halts before
the connection halts. Its `halt-key!` runs first and can drain the buffer with a
final flush while the connection is still open. Only after that does the
connection's `halt-key!` close LMDB. See `src/engram/system.clj`,
`ig/halt-key! :engram.db/conn`. So a clean shutdown loses nothing, because the
drain flushes before the connection closes. Only a hard crash loses the buffer.

## Recommendation

Use a single-thread consumer for the stat write, and make it the Clojure agent by
default, with the single-thread `ExecutorService` as the close alternative. Both
are already on the classpath, so neither adds a dependency. The agent gives
ordering, serialization, and off-thread dispatch from `send-off` for the least
new code, and `await` in `halt-key!` drains it before the connection closes. Set
the agent to `:continue` error-mode with an error handler, so a failed write logs
and the consumer keeps running and never touches the fetch. If a strict memory
bound or a documented two-phase drain is preferred over minimal code, choose the
`ExecutorService` with `shutdown` plus `awaitTermination`. Do NOT add core.async,
because the JDK and Clojure core already cover this job.

Do NOT add the batching layer now. Add it later. Under 1 to 10 users with sparse
calls the database is not under load, so batching is a premature optimization
today. The single consumer already serializes writes and already removes the
race, which is the actual goal. Batching earns its place only after a future load
profile shows write pressure.

This shapes the new ticket that depends on 07 as follows. The new ticket names
the agent as the default consumer and the single-thread executor as the
alternative. It requires `:continue` error-mode and a try/catch so a write
failure is isolated, which satisfies ticket 07. It requires a drain in
`halt-key!`. The drain is `await` for the agent, or `shutdown` plus
`awaitTermination` for the executor. It orders the consumer component after
`:engram.db/conn`, so the Integrant reverse-order halt drains before the
connection closes. It states the decay clock caveat from the prior note as a
documented, bounded limitation. It records batching and coalescing as a deferred
follow-up with the proven Datalevin fact that one `transact!` covers many pairs
in one lock.

## Unverified

- No end-to-end engram test yet proves an agent or executor consumer drains
  cleanly under `halt!` in this repo. The drain claim rests on the Integrant,
  agent, and executor primary sources, not on a repo test.
- The exact overflow behavior of a full agent queue under a real burst in this
  process is not measured. The unbounded-queue risk is from the absence of a
  documented bound, not from an observed failure.
- The claim that a seconds-scale flush interval keeps the decay drift negligible
  is a bounded-magnitude argument over the stated load, not a measured figure.

## Sources

- Clojure agents reference, send-off, one-action-at-a-time ordering, error
  caching, `set-error-mode!` `:continue`, `await`, `shutdown-agents`:
  https://clojure.org/reference/agents
- core.async API, `chan`, `dropping-buffer`, `sliding-buffer`, `thread`, `go`,
  `close!`, `timeout`, `offer!`, `put!`:
  https://clojure.github.io/core.async/clojure.core.async.html
- core.async README, the go-checking rule that blocking ops do not belong in a go
  block: https://github.com/clojure/core.async/blob/master/README.md
- JDK 21 `BlockingQueue`, thread-safety, `offer` non-blocking, capacity bound:
  https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/BlockingQueue.html
- JDK 21 `LinkedBlockingQueue`, FIFO, optionally-bounded, default
  `Integer.MAX_VALUE`:
  https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/LinkedBlockingQueue.html
- JDK 21 `ArrayBlockingQueue`, bounded array-backed FIFO, fixed capacity:
  https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ArrayBlockingQueue.html
- JDK 21 `ExecutorService`, `shutdown`, `shutdownNow`, `awaitTermination`:
  https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ExecutorService.html
- JDK 21 `Executors`, `newSingleThreadExecutor` sequential guarantee:
  https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Executors.html
- Integrant README, `init-key`, `halt-key!`, reverse-dependency-order halt,
  `ig/ref`: https://github.com/weavejester/integrant/blob/master/README.md
- Datalevin 1.0.1 source (unpacked from
  `~/.m2/repository/datalevin/datalevin/1.0.1/datalevin-1.0.1.jar`):
  - `datalevin/core.clj`, def `transact!`, arglist `[conn tx-data]`, docstring
    and multi-datom and multi-map examples applied as one transaction.
  - `datalevin/conn.clj`, `(defn transact! ([conn tx-data] ...))`, the delegate.
- Prior note, decay atomicity, single-writer lock, decay clock caveat:
  `.scratch/design-review/research/record-fetch-decay-atomicity.md`
- Repo files cited:
  - `src/engram/stats.clj` (`record-fetch!`, `decay-factor`, `stats`).
  - `src/engram/handler.clj` (`query-handler`, line 191).
  - `src/engram/system.clj` (`:engram.db/conn`, `:engram.web/server`,
    `init-key`, `halt-key!`).
  - `resources/migrations/001-schema.edn` (`:stat/*`, the composite tuple
    `:stat/user+category+label`).
  - `deps.edn` (the dependency set, no core.async).
  - `.scratch/design-review/issues/06-pure-fetch-stats-planner.md`.
  - `.scratch/design-review/issues/07-non-blocking-fetch-stats-write.md`.
