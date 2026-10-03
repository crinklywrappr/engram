# coalescing agent debounce flush research

> Home of this note: the repo keeps design-review tickets under
> `.scratch/design-review/issues/`. Findings live in a sibling `research/`
> folder. Two notes already sit there. This note follows the same precedent and
> lives at
> `.scratch/design-review/research/coalescing-agent-debounce-flush.md`.

Every claim below traces to a primary source. The sources are five. They are the
official Clojure agents reference. They are the JDK 21 `java.util.concurrent`
Javadoc for `ScheduledExecutorService`, `ScheduledFuture`, and `Future`. They
are the Datalevin 1.0.1 source and docs. They are the Integrant README. They are
the repo code. Each claim cites a URL or a repo file path plus an exact
identifier. Anything I cannot trace to a primary source is marked unverified.

## Problem statement

The decision is made. The stat write becomes a Clojure agent whose value is an
in-memory coalescing buffer. A request thread folds one fetch into the buffer
with a cheap in-memory action. A debounced flush drains the whole buffer to the
database in one batched transaction and resets the buffer.

Today `query-handler` calls `stats/record-fetch!` on the request thread. Source:
`src/engram/handler.clj`, `query-handler`, line 191. That call is a two-step
read-modify-write. Source: `src/engram/stats.clj`, `record-fetch!`. Two prior
notes set the ground. The atomicity note found the lost-update race and the
decay-clock caveat. Source:
`.scratch/design-review/research/record-fetch-decay-atomicity.md`. The
consumer-and-batching note recommended a single consumer and named the deferred
batching idea now pulled forward. Source:
`.scratch/design-review/research/stat-write-consumer-and-batching.md`.

The buffer is keyed by the (user, category, label) triple. That triple is the
schema's unique identity `:stat/user+category+label`. Source:
`resources/migrations/001-schema.edn`. This note calls that triple a "pair" to
match the task wording, though it holds three fields.

## 1. Agent action dispatch: send versus send-off

The Clojure agents reference splits the two dispatch forms by workload. It ties
`send` to actions that are CPU limited. It ties `send-off` to actions that block
on IO or that can otherwise tie up a thread. Source:
https://clojure.org/reference/agents. The reference states that `send` uses a
fixed thread pool sized to the processors, and that `send-off` uses an expanding
thread pool. Source: https://clojure.org/reference/agents.

The in-memory fold is a cheap `update-in` on a map. It does no blocking IO. So
the fold action fits `send`. The database flush does blocking IO to LMDB. So the
flush action fits `send-off`. Section 3b confirms the scheduler hands the flush
to the agent by `send-off`.

Actions to one agent are applied serially and in order. The reference states,
"At any point in time, at most one action for each Agent is being executed.
Actions dispatched to an agent from another single agent or thread will occur in
the order they were sent." Source: https://clojure.org/reference/agents. So the
agent runs the folds and the flush one at a time, never concurrently.

This serialization closes the lost-update race for the flush read-modify-write.
The flush reads each pair's current row, adds the coalesced count, and writes.
No other action on this agent runs while the flush runs. So no fold and no other
flush can interleave inside that read-modify-write. The atomicity note reached
the same conclusion for a single consumer. Source:
`.scratch/design-review/research/record-fetch-decay-atomicity.md`, Candidate
solution A.

## 2. Agent error handling

The reference documents two error modes. Source:
https://clojure.org/reference/agents. In `:fail` mode, an exception thrown by an
action gets cached in the agent. The agent then holds that error. Every later
`send` or `send-off` throws at once, until a caller clears the error with
`restart-agent`. Source: https://clojure.org/reference/agents. In `:continue`
mode, the agent ignores the error and keeps processing later actions. When a
`set-error-handler!` is set, the agent calls the handler on each error. Source:
https://clojure.org/reference/agents.

`agent-error` returns the cached exception of a `:fail`-mode agent. It returns
nil for an agent with no error. `restart-agent` clears a cached error and sets a
new agent value. Source: https://clojure.org/reference/agents.

The flush action must not drop the buffer on a failed database write. The design
that satisfies this is reset-on-success only. The flush action reads the buffer
snapshot it was handed, builds the tx-data, and transacts. On a successful
transact, the action returns a new agent value with those flushed pairs removed.
On a thrown transact, the action does not remove them. So the pending counts
survive, and the next flush retries them.

There are two clean ways to hold the "keep on failure" rule.

**With :continue mode.** The flush action catches the transact exception inside
itself. On success it returns the drained buffer. On failure it logs and returns
the agent value unchanged, so the pending counts stay. This keeps the agent
running. The failure never reaches the request thread, because the fold and the
flush both ran off-thread. This matches ticket 07's failure isolation. Source:
`.scratch/design-review/issues/07-non-blocking-fetch-stats-write.md`.

**With :fail mode.** The flush action lets the exception propagate. The agent
caches the error and stops. The action never returned a new value, so the agent
keeps its old value, which still holds the pending counts. A caller must
`restart-agent` with that same value to resume. This needs an external restart
step, so it is less self-healing than `:continue`.

The recommendation is `:continue` with a logging `set-error-handler!`. Add a
try/catch inside the flush action. On failure the return value stays the
un-drained buffer. This keeps the agent alive, keeps the counts, and isolates the
failure. The consumer-and-batching note recommended the same `:continue` choice.
Source:
`.scratch/design-review/research/stat-write-consumer-and-batching.md`, Part 1,
Option 1.

One subtlety on the interaction with the error mode. In `:continue` mode the fold
actions keep landing in the buffer while a flush is failing. The buffer keeps
growing by pair, not by call volume (section 7). So a run of failed flushes stays
bounded in memory by the number of distinct pairs. The next successful flush
drains the whole accumulated buffer at once.

## 3. The debounce mechanism

There is no JDK debounce primitive. Unverified: I found no single Javadoc method
named "debounce" in `java.util.concurrent`. A debounce is built from
`ScheduledExecutorService` and `ScheduledFuture`.

### Shape (a): fixed periodic flush

A `ScheduledExecutorService` runs a flush on a fixed schedule. Two methods exist.

`scheduleAtFixedRate` submits a periodic action. The Javadoc text is quoted
verbatim below. Source:
https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ScheduledExecutorService.html,
`scheduleAtFixedRate`.

```text
Submits a periodic action that becomes enabled first after the given initial
delay, and subsequently with the given period; that is, executions will commence
after initialDelay, then initialDelay + period, then initialDelay + 2 * period,
and so on. If any execution of this task takes longer than its period, then
subsequent executions may start late, but will not concurrently execute.
```

`scheduleWithFixedDelay` submits a periodic action. The Javadoc text is quoted
verbatim below. Source:
https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ScheduledExecutorService.html,
`scheduleWithFixedDelay`.

```text
Submits a periodic action that becomes enabled first after the given initial
delay, and subsequently with the given delay between the termination of one
execution and the commencement of the next.
```

The difference from the Javadoc is the reference point. `scheduleAtFixedRate`
measures the period from the task's scheduled start times, apart from run
duration. `scheduleWithFixedDelay` measures the delay from the end of one run to
the start of the next. So a slow run pushes the next start later. The flush is
`send-off` to the agent and returns fast (section 3b), so the two behave almost
alike here. `scheduleWithFixedDelay` is the safer pick. A slow tick never lets scheduled
ticks pile up.

A fixed periodic flush is not a true debounce. It flushes on a clock, whether or
not new events arrived. Under sparse load it flushes empty windows, which is
cheap but wasteful.

### Shape (b): true debounce with reschedule and cancel

A true debounce reschedules a single one-shot flush on each new event and cancels
the prior scheduled flush. The one-shot is `schedule`. The Javadoc text is quoted
verbatim below. Source:
https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ScheduledExecutorService.html,
`schedule`.

```text
Submits a one-shot task that becomes enabled after the given delay.
Returns: a ScheduledFuture representing pending completion of the task and whose
get() method will return null upon completion.
```

A `ScheduledFuture` is "A delayed result-bearing action that can be cancelled."
It extends `Delayed` and `Future`. Source:
https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ScheduledFuture.html.
The cancel comes from `Future.cancel(boolean mayInterruptIfRunning)`. The Javadoc
text is quoted verbatim below. Source:
https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Future.html,
`cancel`.

```text
Attempts to cancel execution of this task. This method has no effect if the task
is already completed or cancelled, or could not be cancelled for some other
reason. Otherwise, if this task has not started when cancel is called, this task
should never run.
```

So on each new event the debounce cancels the pending one-shot future
and schedules a fresh one-shot at the full delay from now.

### Starvation risk of a pure debounce

A pure debounce postpones the flush every time a new event arrives inside the
delay window. Under sustained load, each new fold resets the timer, so the flush
never fires. This is starvation of the flush.

The memory stays bounded even so. The buffer is keyed by pair (section 7). A
postponed flush holds more count per pair, not more entries. So a starved flush
does not blow up memory. It does delay durability, and it widens the decay clock
drift from section 4. So a pure debounce trades staleness against database load
with no upper bound on staleness.

### Recommendation for this design: debounce with a maximum wait

A debounce with a maximum wait fixes the starvation while keeping the
event-driven behavior. The rule is: reschedule the one-shot on each event, but
never let the flush be postponed past a hard ceiling from the first pending event.
When the ceiling is reached, flush regardless of new events.

The mechanism uses the same primitives. The per-event one-shot is a `schedule`
call whose prior `ScheduledFuture` is cancelled. A second, non-cancelled
`scheduleWithFixedDelay` or a separately-tracked ceiling `schedule` guarantees the
maximum wait. Both are on
https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ScheduledExecutorService.html.

The stated load justifies this shape. The load is 1 to 10 users with sparse
calls. Source:
`.scratch/design-review/research/stat-write-consumer-and-batching.md`, Part 2.
Under sparse calls, the debounce almost always fires on the short delay, because
new events rarely arrive inside the window. The maximum wait only ever engages
under a rare burst. So the common path is one flush per idle gap, and the ceiling
is a cheap insurance against a burst starving the flush. For this load a simple
fixed `scheduleWithFixedDelay` (shape a) is also acceptable, because empty flushes
are near-free. The debounce-with-max-wait is the more precise default that also
survives a future load increase.

## 3b. Who triggers the flush

The scheduler thread must not do the database write itself. When the scheduled
task fires, it does a `send-off` of the flush action to the agent. The flush then
runs on the agent's thread, serialized with the folds.

```clojure
;; SKETCH: the scheduled task body
(fn scheduled-tick []
  (send-off stats-agent flush-action))
```

The write must not run on the scheduler thread for two sourced reasons.

First, serialization. Actions to one agent run one at a time and in order.
Source: https://clojure.org/reference/agents. That serialization is what closes
the lost-update race (section 1). A write on the scheduler thread runs outside
the agent, so it can interleave with a fold or with a prior flush. That
reintroduces the race.

Second, correct dispatch pool. The flush blocks on LMDB IO. `send-off` uses the
IO-workload pool. Source: https://clojure.org/reference/agents. The scheduler
thread is a scarce timer thread. A blocking write on it can delay the next
scheduled tick. So the scheduler stays a trigger only. It hands the work to the
agent.

## 4. Coalescing and decay correctness

When N fetches of one pair coalesce and flush at time T, the per-fetch arrival
times are lost. The flush has only the coalesced count and the flush instant T.

The decay math the flush must use is: read the current row, decay the stored
`:stat/decayed` weight forward to T, then add the coalesced count N. In the
current per-fetch code the update is decay-to-now then add one. Source:
`src/engram/stats.clj`, `record-fetch!`. The coalesced form generalizes that to
add N at one instant.

```
;; per-fetch (today): for each of N fetches at its own time t_i
;;   w <- w * decay(t_i - last) + 1 ; last <- t_i
;; coalesced (flush at T): once
;;   w <- w * decay(T - last) + N   ; last <- T
```

This differs from applying each fetch at its own arrival time in one way. The
coalesced form treats all N fetches as arriving together at T. The per-fetch form
decays the running weight a little between each arrival. Each added 1.0 then
itself decays over the tail of the window. So the coalesced weight is a touch
higher than the true per-fetch weight. The earlier arrivals do not get decayed
across the window.

The error is bounded and small. `decay-factor` uses a half-life measured in days.
Source: `src/engram/stats.clj`, `decay-factor`, and the schema note's
"exponential-decay recent count" in `resources/migrations/001-schema.edn`. The
per-fetch decay across a flush window is `0.5^(window / half-life)`. Under sparse
load the window is seconds and the half-life is days. So `window / half-life` is
on the order of 1e-4 or smaller, and the decay factor across the window is within
about 1e-4 of 1.0. The relative error on the N added units is bounded by roughly
that factor. So the coalesced weight is within a tiny fraction of the true
per-fetch weight. The atomicity note recorded this same clock caveat as bounded
and small under the stated load. Source:
`.scratch/design-review/research/record-fetch-decay-atomicity.md`, Question 5.

What the buffer must hold per pair. At minimum it holds a count. That count is the
N added at flush. The buffer does not need a first-seen or a last-seen timestamp.
The reason is that the flush uses the row's stored `:stat/last-request` as the
decay origin and uses the flush instant T as the target. So the decay origin comes
from the database, not from the buffer, and the target is T, not a per-event time.
A last-seen in the buffer matters in one case only. That case is a flush that
places the added weight at the last arrival instead of at T. That refinement is
not needed for an approximate signal under a days-long half-life. So a plain
count per pair is enough. Unverified: whether adding a last-seen timestamp
measurably improves the signal is a design judgment, not a sourced fact.

## 5. Batched transaction

`datalevin.core/transact!` applies one tx-data vector as a single write
transaction. Its docstring says it applies a transaction to the underlying
Datalog database synchronously. Its examples pass a single vector that holds many
datoms and many entity maps as one transaction. Source: `datalevin/core.clj`,
def `transact!`, arglist `[conn tx-data]`, the multi-datom and multi-map examples.
`datalevin.core/transact!` delegates to `datalevin.conn/transact!`. Source:
`datalevin/conn.clj`, `(defn transact! ([conn tx-data] ...))`. The atomicity note
established that one `transact!` call runs under the single-writer LMDB lock.
Source:
`.scratch/design-review/research/record-fetch-decay-atomicity.md`, Question 1,
citing `datalevin.conn/with-transaction`. So one flush of many pairs is one lock
acquisition and one commit.

Each map in the batch upserts its own row by the unique identity tuple. The schema
declares `:stat/user+category+label` as `:db.unique/identity`. Source:
`resources/migrations/001-schema.edn`. So one `transact!` upserts many distinct
rows in one write.

The flush builds the tx-data the same way the current code does, generalized to N.
It reads each pending pair's current row, decays the stored weight to T, adds the
coalesced count, and emits the upsert datoms. This read-plus-plan is exactly the
pure computation ticket 06 extracts. Source:
`.scratch/design-review/issues/06-pure-fetch-stats-planner.md`. Ticket 06's
planner takes a database value, the user, the half-life, the pairs, and the
current time, and returns tx-data. So the flush calls the ticket-06 planner rather
than duplicating the decay math. Ticket 06 needs one change. The "pairs" it plans
over now carry a per-pair count, not a bare list of pairs (section 8).

## 6. Integrant lifecycle

Two new components join the system. One is the stat agent that holds the buffer.
One is the scheduler that triggers the flush. Both must start after
`:engram.db/conn` (in practice after `:engram.db/migrated`, which reopens and
becomes the live connection). Source: `src/engram/system.clj`. Each new component
takes the connection by `ig/ref`, so Integrant orders it after the connection.

Integrant halts in reverse dependency order. The Integrant README states, "Like
Component, `halt!` shuts down the system in reverse dependency order." Source:
https://github.com/weavejester/integrant/blob/master/README.md. So the agent and
the scheduler, which depend on the connection, halt before the connection halts.
Their `halt-key!` runs while the connection is still open. This is the
drain-before-close ordering requirement. The drain flushes the buffer while LMDB
is still open, then the connection's `halt-key!` closes LMDB. Source:
`src/engram/system.clj`, `ig/halt-key! :engram.db/conn`.

On halt the order of steps inside the components is:

1. Cancel the scheduler, so no new tick fires. `ScheduledFuture.cancel` stops a
   pending one-shot. Source:
   https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Future.html,
   `cancel`. The scheduler is a `ScheduledExecutorService`, so shut it down and
   wait for it. `shutdown` starts an orderly shutdown and `awaitTermination`
   blocks until tasks finish or the timeout elapses. Source:
   https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ExecutorService.html,
   `shutdown` and `awaitTermination`. (The `ScheduledExecutorService` Javadoc
   lists these as inherited from `ExecutorService`.)

2. Send a final flush to the agent, then wait for it. The `await` Javadoc text is
   quoted verbatim below. Source: https://clojure.org/reference/agents, "Block
   waiting for an Agent."

   ```text
   Blocks the current thread (indefinitely!) until all actions dispatched thus
   far, from this thread or agent, to the agent(s) have occurred.
   ```

   So `halt-key!` does a `send-off` of the flush action, then an `await` on the
   agent. The `await` drains the pending write before it returns.

3. If `shutdown-agents` is used, call it only at full process exit, not in a
   per-component halt. The reference states, "Use shutdown-agents to terminate
   these threads and allow orderly shutdown." Source:
   https://clojure.org/reference/agents. `shutdown-agents` is process-global, so a
   per-component `halt-key!` uses `await` for the drain and leaves
   `shutdown-agents` for the top-level exit.

The exact one-call waits are: `await` for the agent, and `shutdown` plus
`awaitTermination` for the scheduler. The consumer-and-batching note named the same
waits. Source:
`.scratch/design-review/research/stat-write-consumer-and-batching.md`, Part 1.

## 7. Durability and memory bounds

Unflushed counts live in memory. A crash before a flush loses them. This is
acceptable. These stats are approximate popularity signals, not a ledger. The
schema note calls the recent count an "exponential-decay recent count" and the
lifetime a "monotonic lifetime count." Source:
`resources/migrations/001-schema.edn`. A lost flush drops a few increments from an
approximate signal. It does not corrupt any user data and does not break recall.

Keying the buffer by pair bounds its memory by the number of distinct pairs,
independent of call volume. Ten thousand fetches of one pair are one map entry
with a count of ten thousand. This is the property that makes a postponed flush
safe on memory (section 3). A starved or slow flush grows the counts, not the
entry set. So the buffer's memory ceiling is the count of distinct
(user, category, label) triples the process has seen since the last flush.

## Recommendation

Implement the stat write as a Clojure agent whose value is a map keyed by
(user, category, label). The buffer contents per pair are a single coalesced
count. No first-seen or last-seen timestamp is needed. The flush uses the row's
stored `:stat/last-request` as the decay origin and the flush instant as the
target (section 4).

The fold is a `send` of a cheap `update-in` that increments the pair's count. The
flush is a `send-off` of an action. That action reads each pending row and calls
the ticket-06 planner to decay-to-now and add the count. It transacts the whole
batch in one `transact!`. It resets only the flushed pairs on success. Set the
agent to
`:continue` error mode with a logging `set-error-handler!`. Wrap the transact in a
try/catch so the action returns the un-drained buffer on failure. So a failed
write keeps the counts for the next flush (section 2).

The debounce shape is a debounce with a maximum wait. On each fold, cancel the
prior one-shot `ScheduledFuture` and `schedule` a fresh one at the short delay. A
separate ceiling guarantees a flush no later than a hard maximum from the first
pending event, which defeats starvation under a burst (section 3). For the stated
1-to-10-user sparse load, a plain `scheduleWithFixedDelay` is an acceptable
simpler fallback. The scheduled task only ever does `send-off` of the flush to the
agent, never the write itself (section 3b).

How this reshapes the tickets:

- **Ticket 06 (pure planner).** Keep it pure and connection-free. Change its input
  from a bare list of pairs to pairs-with-counts, so the planner adds N per pair
  instead of 1. The flush and any direct caller both use this one planner. Source:
  `.scratch/design-review/issues/06-pure-fetch-stats-planner.md`.
- **Ticket 07 (non-blocking write).** The agent fold satisfies 07. The request
  thread does a `send` and returns at once, so the fetch never waits on the write.
  A write failure stays on the agent under `:continue`, so it never fails the
  fetch. Source:
  `.scratch/design-review/issues/07-non-blocking-fetch-stats-write.md`.
- **New ticket (coalescing agent and debounce).** It depends on 07. It adds the
  agent buffer component, the scheduler component, the fold and flush actions, the
  debounce-with-max-wait, and the Integrant drain-before-close. It states the decay
  clock caveat from section 4 as a documented, bounded limitation. It states the
  crash-loses-buffer durability tradeoff from section 7 as accepted.

## Conceptual code sketch

The following is a SKETCH. It shows shapes, not final code. Prose notes sit
between the fenced blocks, and the code stays free of comments.

Buffer shape. The agent value is a map keyed by `[user category label]` to a
count. The `:continue` error mode keeps the agent alive after a failed write.

```clojure
(def stats-agent
  (agent {}
         :error-mode :continue
         :error-handler (fn [_a e] (log-error e "stat flush failed, counts kept"))))
```

Fold action. It is cheap and in-memory. The producer uses `send`, which fits a
CPU-bound action, not `send-off`.

```clojure
(defn fold-action [buf user pairs]
  (reduce (fn [m [c l]] (update m [user c l] (fnil inc 0))) buf pairs))
```

Producer, on the request thread. `(send stats-agent fold-action user pairs)`
returns at once, so the fetch never waits.

Flush action. It does blocking IO, so the trigger uses `send-off`. It resets on
success only. `plan-tx` is the ticket-06 pure planner, taking pairs-with-counts.
One `transact!` of the whole batch is one write transaction and one lock. On a
thrown transact the action does not return the drained buffer, so the counts
stay for the next flush.

```clojure
(defn flush-action [buf conn half-life-days now-ms]
  (if (empty? buf)
    buf
    (let [tx (plan-tx (d/db conn) half-life-days now-ms buf)]
      (d/transact! conn tx)
      {})))
```

NOTE. A real implementation keeps counts that arrive during the flush. It
snapshots the keys it flushed. It dissoc-es only those keys on success, rather
than resetting to `{}`.

Debounce with a maximum wait, using a `ScheduledExecutorService`. The scheduled
task only ever `send-off`s the flush to the agent. It never writes itself.

```clojure
(defn trigger-flush [conn cfg]
  (send-off stats-agent flush-action conn (:half-life-days cfg)
            (System/currentTimeMillis)))
```

On each fold event, cancel the pending short-delay one-shot, then schedule a
fresh one. A separate ceiling one-shot is not cancelled. It fires no later than a
hard maximum from the first pending event. So a sustained burst cannot postpone
the flush forever.

```clojure
(when @pending (.cancel ^ScheduledFuture @pending false))
(reset! pending (.schedule scheduler #(trigger-flush conn cfg) short-ms MILLISECONDS))
```

Integrant halt. It runs before `:engram.db/conn` halts, in reverse order. The
`await` drains the final flush before the connection closes.

```clojure
(.shutdown scheduler)
(.awaitTermination scheduler grace-ms MILLISECONDS)
(send-off stats-agent flush-action conn half-life (System/currentTimeMillis))
(await stats-agent)
```

## Unverified

- No end-to-end engram test yet proves the agent-buffer-plus-debounce drains
  cleanly under `halt!` in this repo. The drain claim rests on the Integrant, the
  agents-reference, and the JDK primary sources, not on a repo test.
- The claim that a seconds-scale flush window keeps the decay drift within about
  1e-4 is a bounded-magnitude argument over the stated load, not a measured
  figure.
- The claim that a plain count per pair (no last-seen timestamp) loses no
  meaningful signal is a design judgment under a days-long half-life, not a sourced
  fact.
- I found no JDK `java.util.concurrent` method named "debounce." The absence is
  from a search of the `ScheduledExecutorService` Javadoc, not from an exhaustive
  proof.

## Sources

- Clojure agents reference: `send` versus `send-off` by workload, the fixed
  versus expanding pools, one-action-at-a-time serial ordering, `:fail` versus
  `:continue` error modes, `agent-error`, `restart-agent`, `set-error-handler!`,
  `await`, `shutdown-agents`: https://clojure.org/reference/agents
- JDK 21 `ScheduledExecutorService`: `schedule` one-shot returning a
  `ScheduledFuture`, `scheduleAtFixedRate`, `scheduleWithFixedDelay`, and the
  fixed-rate versus fixed-delay difference:
  https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ScheduledExecutorService.html
- JDK 21 `ScheduledFuture`: "A delayed result-bearing action that can be
  cancelled," extends `Delayed` and `Future`:
  https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ScheduledFuture.html
- JDK 21 `Future`: `cancel(boolean mayInterruptIfRunning)` semantics and return
  value:
  https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Future.html
- JDK 21 `ExecutorService`: `shutdown` and `awaitTermination`:
  https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ExecutorService.html
- Integrant README: reverse-dependency-order halt, `init-key`, `halt-key!`,
  `ig/ref`: https://github.com/weavejester/integrant/blob/master/README.md
- Datalevin 1.0.1 source: `datalevin/core.clj`, def `transact!`, arglist
  `[conn tx-data]`, one-vector multi-datom and multi-map examples. Also
  `datalevin/conn.clj`, `(defn transact! ([conn tx-data] ...))`, the delegate.
- Prior notes:
  - `.scratch/design-review/research/record-fetch-decay-atomicity.md`
    (single-writer lock, lost-update race, decay clock caveat).
  - `.scratch/design-review/research/stat-write-consumer-and-batching.md`
    (single consumer, `:continue` error mode, one `transact!` covers many pairs).
- Repo files cited:
  - `src/engram/stats.clj` (`record-fetch!`, `existing`, `stats`, `decay-factor`).
  - `src/engram/handler.clj` (`query-handler`, line 191).
  - `src/engram/system.clj` (`:engram.db/conn`, `:engram.db/migrated`,
    `init-key`, `halt-key!`).
  - `resources/migrations/001-schema.edn` (`:stat/*`, the composite tuple
    `:stat/user+category+label`, unique identity, upsert).
  - `deps.edn` (the dependency set, no core.async).
  - `.scratch/design-review/issues/06-pure-fetch-stats-planner.md`.
  - `.scratch/design-review/issues/07-non-blocking-fetch-stats-write.md`.
</content>
</invoke>
