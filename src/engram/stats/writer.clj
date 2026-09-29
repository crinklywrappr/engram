(ns engram.stats.writer
  "The stat-write consumer.

  One agent is the only serialization point, and it dispatches every action
  through a single-thread daemon executor the writer owns (send-via, not send
  or send-off). Its value holds the pending recalls keyed by [user category
  label], the scheduled flush, and the window start. Because every action runs
  on the agent, one at a time, no two read-modify-writes interleave and no
  increment is lost, and the debounce state needs no lock. engram never touches
  the shared agent pools, so its threads are daemon and the JVM exits without
  shutdown-agents. A request thread hands a recall in and returns at once. A
  flush drains the pending recalls to one transaction through the pure planner
  in `engram.stats`. The decay uses a clock captured at flush time, a bounded
  drift held as a known limit."
  (:require [datalevin.core :as d]
            [engram.stats :as stats]
            [taoensso.telemere :as t])
  (:import [java.util.concurrent Executors ExecutorService RejectedExecutionException
                                 ScheduledExecutorService ScheduledFuture ThreadFactory TimeUnit]))

(defn- flush-recalls
  "Agent action. Drain the pending recalls to one transaction and reset them. On
  a write failure, keep the pending recalls so the next flush retries the counts."
  [{:keys [pending-recalls] :as state} writer]
  (if (empty? pending-recalls)
    (assoc state :timer nil :first-ms nil)
    (try
      (let [db       (d/db (:conn writer))
            flush-ms (System/currentTimeMillis)
            tx       (mapcat (fn [[user pcs]] (stats/plan-recalls db user (:half-life writer) pcs flush-ms))
                             (stats/by-user pending-recalls))]
        (when (seq tx) (d/transact! (:conn writer) (vec tx)))
        (assoc state :pending-recalls {} :timer nil :first-ms nil))
      (catch Throwable e
        (t/log! {:level :warn :id ::flush-failed :error e} "stat flush failed")
        (assoc state :timer nil :first-ms nil)))))

(defn- schedule-flush!
  "Schedule a one-shot flush, delayed by the debounce but capped so the window
  that began at `first-ms` still flushes within the maximum wait. Return the
  ScheduledFuture. The scheduler only hands the flush to the agent's consumer, so
  the write stays on the one consumer thread."
  [{:keys [scheduler debounce-ms max-wait-ms consumer] ag :agent :as writer} first-ms]
  (let [now   (System/currentTimeMillis)
        delay (max 0 (min (long debounce-ms) (- (+ first-ms (long max-wait-ms)) now)))]
    ;; nil when the scheduler is shutting down, so a late fold still folds
    (try
      (.schedule ^ScheduledExecutorService scheduler
                 ^Runnable (fn [] (send-via consumer ag flush-recalls writer))
                 (long delay) TimeUnit/MILLISECONDS)
      (catch RejectedExecutionException _ nil))))

(defn- absorb
  "Agent action. Fold a recall into the pending recalls, then cancel the prior
  scheduled flush and reschedule one. A repeated pair adds one to its count."
  [{:keys [timer first-ms] :as state} writer user pairs]
  (let [pending' (reduce (fn [b [c l]] (update b [user c l] (fnil inc 0))) (:pending-recalls state) pairs)
        first-ms (or first-ms (System/currentTimeMillis))]
    (when-let [^ScheduledFuture t timer] (.cancel t false))
    (assoc state :pending-recalls pending' :first-ms first-ms :timer (schedule-flush! writer first-ms))))

(defn writer
  "Create the stat-write consumer with a debounced flush. Its agent value holds
  the pending recalls keyed by [user category label], the scheduled flush, and
  the window start.

  Tuning:
  - `half-life-days` is the decay half-life in days, from the admin config.
  - `debounce-ms` defaults to 200. Each recall reschedules a one-shot flush this
    many milliseconds later.
  - `max-wait-ms` defaults to 2000. A sustained burst still flushes within this
    cap.

  Guarantees:
  - One serial consumer applies every action in order, so no increment is lost.
  - A debounced flush coalesces a burst into one transaction.
  - The agent dispatches through a single-thread daemon executor it owns, so
    engram uses daemon threads only and the JVM exits without shutdown-agents.
  - The :continue error mode keeps the agent usable after a failed action.

  The returned map holds:
  - `:agent` serializes the work. Its value holds the pending recalls, the
    scheduled flush, and the window start.
  - `:conn` is the Datalevin connection each flush writes to.
  - `:half-life` is the decay half-life in days, from the admin config.
  - `:consumer` is the single-thread daemon executor that applies every action.
  - `:scheduler` is the single-thread daemon executor that fires the flush.
  - `:closed?` prevents writes during shutdown. `drain!` sets it, and it turns `record!` into a no-op.
  - `:debounce-ms` is the debounce delay in milliseconds.
  - `:max-wait-ms` is the cap on debounce postponement in milliseconds."
  [conn half-life-days & {:keys [debounce-ms max-wait-ms]
                          :or   {debounce-ms 200 max-wait-ms 2000}}]
  (letfn [(daemon [nm] (reify ThreadFactory
                         (newThread [_ r] (doto (Thread. ^Runnable r nm) (.setDaemon true)))))]
    {:agent       (agent {:pending-recalls {} :timer nil :first-ms nil} :error-mode :continue)
     :conn        conn
     :half-life   half-life-days
     :consumer    (Executors/newSingleThreadExecutor (daemon "engram-stat-consumer"))
     :scheduler   (Executors/newSingleThreadScheduledExecutor (daemon "engram-stat-debounce"))
     :closed?     (atom false)
     :debounce-ms debounce-ms
     :max-wait-ms max-wait-ms}))

(defn record!
  "Fold one recall into the pending recalls and reschedule the debounced flush.
  Return at once, so the caller never waits on the write and a write failure
  never reaches the caller. A no-op once the writer is drained."
  [{ag :agent consumer :consumer closed? :closed? :as writer} user pairs]
  (when-not @closed?
    (send-via consumer ag absorb writer user pairs))
  nil)

(defn drain!
  "Stop taking new recalls, cancel the pending flush, drain the pending recalls,
  and shut the owned executors down. Used on shutdown, so a final flush lands
  while the connection is open. Wait by way of a promise, so it never touches the
  shared agent pools."
  [{:keys [scheduler consumer] ag :agent closed? :closed? :as writer}]
  (reset! closed? true)
  (.shutdown ^ScheduledExecutorService scheduler)
  (let [done (promise)]
    (send-via consumer ag
              (fn [state]
                (when-let [^ScheduledFuture t (:timer state)] (.cancel t false))
                (let [drained (flush-recalls (assoc state :timer nil) writer)]
                  (deliver done true)
                  drained)))
    @done)
  (.shutdown ^ExecutorService consumer)
  nil)
