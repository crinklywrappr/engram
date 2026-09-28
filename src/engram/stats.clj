(ns engram.stats
  "Per-user request stats over Datalevin. Stateless: connection first.

  For each (user, category, label) pair we keep a monotonic lifetime count and
  an exponential-decay recent count. The recent count is updated on each fetch
  and projected to the current time on read, using the half-life from the admin
  config."
  (:require [datalevin.core :as d]
            [taoensso.telemere :as t])
  (:import [java.util Date]
           [java.util.concurrent Executors ScheduledExecutorService ScheduledFuture
                                 ThreadFactory TimeUnit]))

(defn- decay-factor
  "The fraction of a decayed weight that remains after `elapsed-ms`, given the
  half-life in days."
  [half-life-days elapsed-ms]
  (Math/pow 0.5 (/ (/ (double elapsed-ms) 86400000.0) (double half-life-days))))

(defn- existing [db user category label]
  (d/q '[:find [?life ?dec ?last]
         :in $ ?u ?c ?l
         :where [?s :stat/user ?u]
                [?s :stat/category ?c]
                [?s :stat/label ?l]
                [?s :stat/lifetime ?life]
                [?s :stat/decayed ?dec]
                [?s :stat/last-request ?last]]
       db user category label))

(defn plan-fetch
  "Pure. Build the batched stat transaction for `user` over `pair-counts`, a map
  of [category label] to a coalesced count. For each pair, read the current row
  from `db`, decay the stored weight to `flush-ms`, and add the count to the
  lifetime and to the decayed weight. Return a vector of tx-maps, empty when
  `pair-counts` is empty. Take no connection and perform no write."
  [db user half-life-days pair-counts flush-ms]
  (mapv (fn [[[c l] n]]
          (let [[life dec ^Date last] (existing db user c l)
                base (if last
                       (* (double dec)
                          (decay-factor half-life-days (- flush-ms (.getTime last))))
                       0.0)]
            {:stat/user user :stat/category c :stat/label l
             :stat/lifetime (+ (long (or life 0)) (long n))
             :stat/decayed  (+ base (double n))
             :stat/last-request (Date. flush-ms)}))
        pair-counts))

(defn record-fetch!
  "Record that `user` fetched each pair once: read, decay to now, and add one.
  Delegate the tx-data to `plan-fetch` with a count of one per pair, then write."
  [conn user half-life-days pairs]
  (let [tx (plan-fetch (d/db conn) user half-life-days
                       (into {} (map (fn [p] [p 1])) pairs)
                       (System/currentTimeMillis))]
    (when (seq tx) (d/transact! conn tx))))

(defn stats
  "Return the caller's stat rows: lifetime and the recent decay count projected
  to now."
  [conn user half-life-days]
  (let [db     (d/db conn)
        now-ms (System/currentTimeMillis)]
    (->> (d/q '[:find ?c ?l ?life ?dec ?last
                :in $ ?u
                :where [?s :stat/user ?u]
                       [?s :stat/category ?c]
                       [?s :stat/label ?l]
                       [?s :stat/lifetime ?life]
                       [?s :stat/decayed ?dec]
                       [?s :stat/last-request ?last]]
              db user)
         (map (fn [[c l life dec ^Date last]]
                {:category c :label l :lifetime life
                 :recent (* (double dec)
                            (decay-factor half-life-days (- now-ms (.getTime last))))})))))

;; ---------- the stat-write consumer ----------
;;
;; A single agent serializes every stat write, so no two read-modify-writes
;; interleave and no increment is lost. Its value is a pending buffer keyed by
;; [user category label]. A request thread folds a fetch in and returns at once.
;; A flush drains the buffer to one transaction through plan-fetch. The decay
;; uses a clock captured at flush time, a bounded drift held as a known limit.

(defn- fold
  "Fold one fetch into the pending buffer, adding one to each pair's count."
  [buffer user pairs]
  (reduce (fn [b [c l]] (update b [user c l] (fnil inc 0))) buffer pairs))

(defn- by-user
  "Regroup a buffer keyed by [user category label] into a map of user to a map of
  [category label] to count."
  [buffer]
  (reduce (fn [m [[user c l] n]] (assoc-in m [user [c l]] n)) {} buffer))

(defn- flush-buffer
  "Agent action. Drain the pending buffer to one transaction and reset it. On a
  write failure, keep the buffer so the next flush retries the counts."
  [buffer conn half-life-days]
  (if (empty? buffer)
    buffer
    (try
      (let [db       (d/db conn)
            flush-ms (System/currentTimeMillis)
            tx       (mapcat (fn [[user pcs]] (plan-fetch db user half-life-days pcs flush-ms))
                             (by-user buffer))]
        (when (seq tx) (d/transact! conn (vec tx)))
        {})
      (catch Throwable e
        (t/log! {:level :warn :id ::flush-failed :error e} "stat flush failed")
        buffer))))

(defn- fire!
  "Reset the debounce state and hand the flush to the agent. Runs on the
  scheduler thread, so it only dispatches. The agent does the write, so the
  write stays serialized on the one consumer."
  [{state :state lock :lock ag :agent conn :conn half-life :half-life}]
  (locking lock (reset! state {:future nil :first-ms nil}))
  (send-off ag flush-buffer conn half-life))

(defn- reschedule!
  "Reschedule the one-shot flush. Cancel the prior scheduled flush and schedule a
  new one a debounce later. Cap the delay so the first pending fetch still
  flushes within the maximum wait, which stops a sustained burst from starving
  the flush."
  [{:keys [scheduler state lock debounce-ms max-wait-ms] :as writer}]
  (locking lock
    (let [now      (System/currentTimeMillis)
          st       @state
          ^ScheduledFuture future (:future st)
          first-ms (or (:first-ms st) now)
          delay    (max 0 (min (long debounce-ms)
                               (- (+ first-ms (long max-wait-ms)) now)))]
      (when future (.cancel future false))
      (let [fut (.schedule ^ScheduledExecutorService scheduler
                           ^Runnable (fn [] (fire! writer))
                           (long delay) TimeUnit/MILLISECONDS)]
        (reset! state {:future fut :first-ms first-ms})))))

(defn writer
  "Create the stat-write consumer with a debounced flush. Its agent holds a
  pending buffer keyed by [user category label]. Each fetch reschedules a
  one-shot flush a `debounce-ms` later, capped by `max-wait-ms` so a sustained
  burst still flushes. The :continue error mode keeps the agent usable after a
  failed action."
  [conn half-life-days & {:keys [debounce-ms max-wait-ms]
                          :or   {debounce-ms 200 max-wait-ms 2000}}]
  {:agent       (agent {} :error-mode :continue)
   :conn        conn
   :half-life   half-life-days
   :scheduler   (Executors/newSingleThreadScheduledExecutor
                 (reify ThreadFactory
                   (newThread [_ r]
                     (doto (Thread. ^Runnable r "engram-stat-debounce") (.setDaemon true)))))
   :state       (atom {:future nil :first-ms nil})
   :lock        (Object.)
   :debounce-ms debounce-ms
   :max-wait-ms max-wait-ms})

(defn record!
  "Fold one fetch into the buffer and reschedule the debounced flush. Return at
  once, so the caller never waits on the write and a write failure never reaches
  the caller."
  [{ag :agent :as writer} user pairs]
  (send ag fold user pairs)
  (reschedule! writer)
  nil)

(defn drain!
  "Cancel the pending flush, stop the scheduler, flush any pending counts, and
  wait for the agent. Used on shutdown, so a final flush lands while the
  connection is open."
  [{scheduler :scheduler state :state lock :lock ag :agent conn :conn half-life :half-life}]
  (locking lock
    (when-let [^ScheduledFuture f (:future @state)] (.cancel f false))
    (reset! state {:future nil :first-ms nil}))
  (.shutdown ^ScheduledExecutorService scheduler)
  (send-off ag flush-buffer conn half-life)
  (await ag)
  nil)
