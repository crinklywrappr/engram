# Clojure agent dispatch and lifecycle, applied to the engram stat-writer

This note builds a primary-source picture of how Clojure agents dispatch actions
and manage thread pools. It then connects that picture to engram's stat-writer
in `src/engram/stats.clj`.

All claims trace to the Clojure agents reference, the Clojure API docs, or the
Clojure source. The two source files are `clojure/core.clj` and
`java/clojure/lang/Agent.java` in the `clojure/clojure` repo. See the sources
list at the end.

## 1. send vs send-off vs send-via

### Dispatch semantics

All three functions dispatch an action to an agent and return the agent at once.
The action runs later on some thread. The new agent state becomes the value of
`(apply action-fn state args)`. This is the docstring of `send` and `send-off` in
`core.clj`.

`send` and `send-off` both call `send-via`. They only differ in the executor they
pass. Here is `core.clj`:

```clojure
(defn send
  [^clojure.lang.Agent a f & args]
  (apply send-via clojure.lang.Agent/pooledExecutor a f args))

(defn send-off
  [^clojure.lang.Agent a f & args]
  (apply send-via clojure.lang.Agent/soloExecutor a f args))

(defn send-via
  [executor ^clojure.lang.Agent a f & args]
  (.dispatch a (binding [*agent* a] (binding-conveyor-fn f)) args executor))
```

So `send-via` takes the executor as its first argument. `send` binds it to
`Agent/pooledExecutor`. `send-off` binds it to `Agent/soloExecutor`. The chosen
executor is carried into `Agent.dispatch` and stored on the `Action`.

### Which thread pool each uses

The two shared pools are static fields in `Agent.java`:

```java
volatile public static ExecutorService pooledExecutor =
    Executors.newFixedThreadPool(2 + Runtime.getRuntime().availableProcessors(),
        createThreadFactory("clojure-agent-send-pool-%d", sendThreadPoolCounter));

volatile public static ExecutorService soloExecutor = Executors.newCachedThreadPool(
    createThreadFactory("clojure-agent-send-off-pool-%d", sendOffThreadPoolCounter));
```

`send` uses `pooledExecutor`. That is a fixed-size pool. Its size is
`2 + Runtime.getRuntime().availableProcessors()`. The size is bounded by the
processor count and does not grow. This pool is for actions that do not block.
The reference states send is for CPU-limited actions.

`send-off` uses `soloExecutor`. That is a cached thread pool from
`Executors.newCachedThreadPool`. A cached pool creates new threads on demand and
reuses idle ones. It is effectively unbounded. This pool is for actions that can
block, for example IO. The reference states send-off is for actions that can
block on IO.

`send-via` uses the executor you supply. The choice is local to that one call.
It does not touch the two shared pools.

### The starvation rule

The `send` pool has a fixed thread count near the processor count. A blocking
action holds one of those threads for the whole block. Enough concurrent blocking
actions can occupy every thread in the pool. Then other `send` actions across the
whole JVM wait. So blocking or IO work must use `send-off` or `send-via` with a
suitable executor. The reference expresses the intent with its CPU-versus-IO
split. The exact "starves the fixed pool" mechanism follows from the pool being a
fixed-size pool. That mechanism is a direct reading of the pool type, marked as a
reasoned inference below.

### Serial, in-order application per agent

At most one action for each agent runs at a time. The reference says this
(quoted verbatim):

```text
At any point in time, at most one action for each Agent is being executed.
Actions dispatched to an agent from another single agent or thread will occur
in the order they were sent, potentially interleaved with actions dispatched to
the same agent from other sources.
```

The source backs this. Each agent holds an `ActionQueue` in an atomic field.
When `Action.doRun` pops the finished action, and more actions remain, it
executes the next one:

```java
next = new ActionQueue(prior.q.pop(), error)
...
if(error == null && next.q.count() > 0) ((Action) next.q.peek()).execute()
```

This chaining is per agent and does not depend on the pool. So actions to one
agent apply serially and in order. The dispatch function does not change this.
The pool that ran them does not change this either.

## 2. set-agent-send-executor! and set-agent-send-off-executor!

Both functions replace one of the two shared static pool fields. Here is
`core.clj`:

```clojure
(defn set-agent-send-executor!
  "Sets the ExecutorService to be used by send"
  [executor]
  (set! clojure.lang.Agent/pooledExecutor executor))

(defn set-agent-send-off-executor!
  "Sets the ExecutorService to be used by send-off"
  [executor]
  (set! clojure.lang.Agent/soloExecutor executor))
```

`set-agent-send-executor!` overwrites `Agent/pooledExecutor`, the field `send`
reads. `set-agent-send-off-executor!` overwrites `Agent/soloExecutor`, the field
`send-off` reads. Both fields are `public static` in `Agent.java`, so one field
is shared across the whole JVM.

The scope of the change is global. After the call, every later `send` in the JVM
uses the new pool. The change affects every agent, not one agent. It is not
scoped to a component or a thread.

The tradeoff against `send-via` is locality. `send-via` sets the executor for one
dispatch. It keeps the choice local to that call site and to that action. It
changes no global state and affects no other agent. If you want isolation for one
agent's work, `send-via` is the local tool. The two setter functions are the
global, process-wide tool.

## 3. release-pending-sends

Sends made from inside a running agent action are held until that action
finishes. Sends made from inside an STM transaction are held until that
transaction commits. This keeps the sends consistent with the state change that
caused them. The `dispatchAction` code in `Agent.java` shows the hold:

```java
static void dispatchAction(Action action){
  LockingTransaction trans = LockingTransaction.getRunning();
  if(trans != null)
    trans.enqueue(action);
  else if(nested.get() != null){
    nested.set(nested.get().cons(action));
  }
  else
    action.agent.enqueue(action);
}
```

If a transaction runs, the action is queued on the transaction. If instead the
thread is inside an action (the `nested` thread-local holds a vector), the action
is added to that vector. Otherwise the action is enqueued to its agent at once.

`release-pending-sends` dispatches the held nested sends early and returns their
count. Here is `core.clj`:

```clojure
(defn release-pending-sends
  "Normally, actions sent directly or indirectly during another action
  are held until the action completes (changes the agent's state). This
  function can be used to dispatch any pending sent actions immediately."
  [] (clojure.lang.Agent/releasePendingSends))
```

The `Agent.java` method drains the `nested` thread-local, enqueues each held
action to its agent, resets the buffer, and returns the count:

```java
static public int releasePendingSends(){
  IPersistentVector sends = nested.get();
  if(sends == null) return 0;
  for(int i=0;i<sends.count();i++){
    Action a = (Action) sends.valAt(i);
    a.agent.enqueue(a);
  }
  nested.set(PersistentVector.EMPTY);
  return sends.count();
}
```

The exact condition where it matters is this. The current thread must be inside
an agent action and hold nested pending sends. When `nested` holds nothing, it
returns 0 and does nothing useful. Note the transaction branch
in `dispatchAction` uses `trans.enqueue`, not `nested`. So `release-pending-sends`
acts on nested action sends. It does not force-release a running transaction's
queued sends.

## 4. shutdown-agents

`shutdown-agents` shuts down the two shared agent thread pools. Here is
`core.clj`:

```clojure
(defn shutdown-agents
  "Initiates a shutdown of the thread pools that back the agent
  system. Running actions will complete, but no new actions will be
  accepted"
  [] (. clojure.lang.Agent shutdown))
```

The `Agent.java` method shuts down both pools:

```java
public static void shutdown(){
    soloExecutor.shutdown();
    pooledExecutor.shutdown();
}
```

`ExecutorService.shutdown` lets already-submitted tasks finish and then stops the
threads. So pending agent actions still run first. After the pools stop, no new
agent actions can dispatch onto them.

The pool threads are not daemon threads. Non-daemon threads keep the JVM alive.
The reference states this: "Note that use of Agents starts a pool of non-daemon
background threads that will prevent shutdown of the JVM. Use shutdown-agents to
terminate these threads and allow shutdown."

The action is process-wide and irreversible. It stops the shared pools for the
whole JVM. A restart of one component cannot bring them back. The practical rule
follows. Call `shutdown-agents` once at process exit. Never call it during a
component restart that expects agents to keep working.

Note on "no new actions" and error state. A failed agent error state is the only
case where the `dispatch` method throws. After `shutdown`, a fresh `send`
reaches `dispatch`, builds an `Action`, and calls `dispatchAction`, which enqueues
to the agent. The pool then rejects the runnable because it is shut down. So the
failure surfaces at pool execution, not at the `dispatch` call. This is a reading
of the `dispatch` and `execute` source, marked as a reasoned inference below.

## Engram implications

The stat-writer is in `src/engram/stats.clj`. One agent is the single
serialization point. Its value is a map with the pending buffer, the scheduled
flush future, and the window start. Relevant functions are `writer`, `record!`,
`absorb`, `write-buffer`, `schedule-flush!`, and `drain!`.

The dispatch shape is this. `record!` calls `send` on `absorb`. `absorb` folds a
fetch into the buffer, cancels the prior scheduled flush, and reschedules one via
`schedule-flush!`. The scheduler is a separate `ScheduledExecutorService`. When
the debounce elapses, the scheduled task calls `send-off` on `write-buffer`, which
reads the db and calls `d/transact!`.

### Is send correct for absorb?

Yes. `absorb` folds into a persistent map and calls
`ScheduledExecutorService.schedule` through `schedule-flush!`. A map update is
CPU work. `.schedule` only enqueues a delayed task and returns a future at once.
Neither step blocks on IO. So the work fits the `send` fixed pool and does not
starve it. This matches the send-pool rule in section 1. `send` is correct here.

One caveat on the fixed pool. `absorb` runs on the shared `pooledExecutor` for the
whole JVM. Engram has one stat agent, so contention is low today. If other code in
the process starts issuing blocking `send` actions, the shared fixed pool can
still stall. This is a shared-pool property, not an `absorb` defect.

### Is send-off correct for the flush write-buffer?

Yes. `write-buffer` reads the db with `d/db`, plans the transaction, and calls
`d/transact!`. `d/transact!` is a blocking LMDB write. Blocking IO belongs on
`send-off`, which uses the unbounded cached `soloExecutor`. This matches the rule
in section 1. `send-off` is correct here and keeps the blocking write off the
`send` fixed pool.

### Would send-via with a dedicated single-thread executor fit the flush better?

It is a reasonable and arguably better fit. Here are the tradeoffs.

Keeping `send-off` (current):

- Simple. No extra executor to create or to close.
- The write shares the process-wide `soloExecutor` cached pool.
- Any other `send-off` in the process shares the same pool with the flush.
- The cached pool is effectively unbounded. A storm of unrelated `send-off` work
  can spawn many threads, and the flush competes with all of it.

Moving the flush to `send-via` a dedicated single-thread executor:

- Isolation. The stat IO runs on its own thread, away from every other
  `send-off` in the process.
- Bounded threads. A single-thread executor caps the flush at one thread.
- The flush is already serialized by the agent, so one thread loses no
  concurrency for this workload.
- Cost. You must create the executor and close it on halt.
- The shape is already familiar. `writer` already builds a
  `ScheduledExecutorService` for the debounce with a named daemon thread factory.
  A second single-thread executor for the flush follows the same pattern.

A single-thread executor pairs well with the agent here. The agent already forces
serial flushes, so a one-thread pool matches the real concurrency and bounds the
resource. The main new duty is lifecycle. The new executor must close on halt,
next to the existing scheduler shutdown in `drain!`.

### Does engram need shutdown-agents?

Consider the two shared agent pools first. The stat agent uses `send` and
`send-off`, so it touches both `pooledExecutor` and `soloExecutor`. Both pools run
non-daemon threads. Per section 4, non-daemon threads keep the JVM alive.

Now separate two cases.

Integrant `halt!` during development. Here the JVM keeps running. A REPL calls
`ig/halt!` and then does more work. Calling `shutdown-agents` here is wrong. It is
process-wide and irreversible. It kills the shared pools for the whole REPL and
breaks every later agent use. The stat-writer `halt-key!` must not call
`shutdown-agents`.

Real process exit. In `src/engram/main.clj`, `-main` blocks on `@(promise)` and a
shutdown hook calls `ig/halt!`. On a real exit, the non-daemon agent pool threads
can hold the JVM open after the hook finishes. So a single `shutdown-agents` at
true process exit helps the JVM exit cleanly.

The safe placement is the shutdown hook or the very end of `-main`, after
`ig/halt!` drains the writer. It must run once, at process exit, and not
inside any `halt-key!`. Note that engram's own debounce scheduler already uses
daemon threads (`.setDaemon true` in `writer`). So the scheduler does not block
exit. Only the agent pools do.

Caveat on ordering. `shutdown-agents` must come after `drain!` completes. `drain!`
itself uses `send` and `send-off` and then `await`. If `shutdown-agents` ran
first, the dead pool rejects the final flush dispatch. So the order
is halt the system (which drains), then `shutdown-agents`.

### Lost-fold or lifecycle hazards in the current drain!

Here is the current `drain!`:

```clojure
(defn drain!
  [{:keys [scheduler] ag :agent :as writer}]
  (send ag (fn [state]
             (when-let [^ScheduledFuture t (:timer state)] (.cancel t false))
             (assoc state :timer nil)))
  (await ag)
  (.shutdown ^ScheduledExecutorService scheduler)
  (send-off ag write-buffer writer)
  (await ag)
  nil)
```

The sequence is: send a cancel action, `await`, shut down the debounce
scheduler, `send-off` the final flush, and `await` again. This is sound in the
normal path. The agent serializes every action, so the cancel and the final
flush cannot interleave with a queued `absorb`. A few hazards remain.

Hazard 1: a scheduled flush already in flight on the agent. `schedule-flush!`
hands the flush to the agent with `send-off`. If the debounce fired just before
`drain!` runs, a `write-buffer` action can already sit in the agent queue. The
first `drain!` `await` waits for the queue to empty, so that flush completes
first. This is safe. It only means the buffer can be empty by the time the final
`send-off` runs, and `write-buffer` handles an empty buffer.

Hazard 2: a fold that folds after the scheduler shutdown. `schedule-flush!`
catches `RejectedExecutionException` and returns `nil`. So an `absorb` that runs
after `.shutdown` still folds its count into the buffer and sets `:timer nil`.
The final `send-off` in `drain!` then flushes that count. This is why `drain!`
does the scheduler shutdown between the two agent phases. The order is correct
and prevents a lost fold from a late scheduled flush.

Hazard 3: a `record!` that arrives after `drain!`. `drain!` does not stop
`record!`. Take a `send` on `absorb` that lands after the final `await`. It folds
a count into the buffer, then tries to schedule onto the dead scheduler and gets
`nil`. That count now sits in the buffer with no flush behind it. It is a lost
fold. In practice engram calls `drain!` from `halt-key!`. The web server halts
before the writer in the Integrant order, so new requests stop first. When a
caller uses the writer after halt, the hazard is real. This is a reasoned
inference from the halt ordering, marked unverified against a specific ordering
guarantee.

Hazard 4: the shared pool at shutdown. `drain!` uses `send` and `send-off`, which
run on the two shared agent pools. If a real process exit calls `shutdown-agents`
before `drain!` finishes, the pool rejects the final flush. So `shutdown-agents`
must run only after `drain!` returns, per section 4.

## Recommendation

Keep `send` for `absorb`. It is CPU work with a non-blocking `.schedule` call. It
belongs on the `send` fixed pool. No change is needed.

Move the flush from `send-off` to `send-via` a dedicated single-thread executor.
The flush is blocking LMDB IO. A dedicated single-thread executor isolates that IO
from every other `send-off` in the process and bounds it to one thread. The agent
already serializes flushes, so one thread loses no concurrency. Engram already
builds a `ScheduledExecutorService` with a named daemon thread factory, so the
shape is familiar. Add the executor to `writer`. Replace the flush `send-off`
calls with `send-via` this executor. Close it in `drain!` next to the scheduler
shutdown. This is a recommendation, not a correctness fix. The current `send-off`
is correct today.

Add `shutdown-agents` at real process exit only. Put it in `-main` or the
shutdown hook, after `ig/halt!` returns. Never put it in a `halt-key!`. The agent
pools run non-daemon threads that can hold the JVM open after halt. One call at
true exit lets the JVM exit cleanly. A development `ig/halt!` in a REPL must not
trigger it.

Consider closing the `record!`-after-`drain!` gap (Hazard 3). One option is a
closed flag on the writer that `record!` checks, so a late fetch is dropped or
logged rather than silently buffered. This is optional and depends on whether the
halt ordering already guarantees no caller uses the writer after halt.

## Sources

- Clojure agents reference: https://clojure.org/reference/agents
  - send is for CPU-limited actions, send-off is for actions that can block on IO.
  - "At any point in time, at most one action for each Agent is being executed."
    Actions from one source occur in send order.
  - Agent pools are non-daemon threads that prevent JVM shutdown. Use
    shutdown-agents to terminate them.
- Clojure API docs: https://clojure.github.io/clojure/
  - Function index for send, send-off, send-via, set-agent-send-executor!,
    set-agent-send-off-executor!, release-pending-sends, shutdown-agents.
- Clojure source `clojure/core.clj`
  (https://raw.githubusercontent.com/clojure/clojure/master/src/clj/clojure/core.clj):
  - `send` calls `send-via` with `Agent/pooledExecutor`.
  - `send-off` calls `send-via` with `Agent/soloExecutor`.
  - `send-via` calls `(.dispatch a ... args executor)`.
  - `set-agent-send-executor!` does `(set! Agent/pooledExecutor executor)`.
  - `set-agent-send-off-executor!` does `(set! Agent/soloExecutor executor)`.
  - `release-pending-sends` calls `(Agent/releasePendingSends)`.
  - `shutdown-agents` calls `(. Agent shutdown)`.
- Clojure source `java/clojure/lang/Agent.java`
  (https://raw.githubusercontent.com/clojure/clojure/master/src/jvm/clojure/lang/Agent.java):
  - `pooledExecutor` is `newFixedThreadPool(2 + availableProcessors(), ...)`.
  - `soloExecutor` is `newCachedThreadPool(...)`.
  - `dispatch(IFn, ISeq, Executor)` builds an `Action` and calls `dispatchAction`.
  - `dispatchAction` routes to a running transaction, else the `nested`
    thread-local, else the agent queue.
  - `releasePendingSends` drains the `nested` vector, enqueues each action, and
    returns the count.
  - `shutdown` calls `soloExecutor.shutdown()` then `pooledExecutor.shutdown()`.
  - `Action.doRun` pops the finished action and executes the next queued one.
- Engram source:
  - `/home/crinklywrappr/engram/src/engram/stats.clj`
  - `/home/crinklywrappr/engram/src/engram/system.clj`
  - `/home/crinklywrappr/engram/src/engram/main.clj`

Unverified items:

- The exact "a blocking action starves the fixed send pool" mechanism is a
  reasoned reading of the fixed-pool type. The reference states the CPU-versus-IO
  intent but not the starvation wording.
- The claim that a post-`shutdown` `send` fails at pool execution, not at the
  `dispatch` call, is a reading of the `dispatch` and `Action.execute` source.
- Hazard 3 depends on the Integrant halt ordering stopping callers before the
  writer halts. That ordering is assumed, not verified against a stated guarantee.