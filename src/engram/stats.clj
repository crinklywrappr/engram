(ns engram.stats
  "Per-user stats over Datalevin for the `/stats` endpoint. Stateless: connection
  first.

  For each (user, category, label) pair we keep a monotonic lifetime count and
  an exponential-decay recent count. The recent count is updated on each recall
  and projected to the current time on read, using the half-life from the admin
  config.

  The namespace also computes link density: graph-structure metrics over the
  caller's src nodes and related edges, reported beside the recall counts.

  This namespace is the functional core: the read projection and the pure
  planner. The operational consumer, which owns the agent and the executors,
  lives in `engram.stats.writer`."
  (:require [datalevin.core :as d]
            [engram.freshness :as freshness])
  (:import [java.util Date UUID]))

(defn- decay-factor
  "The fraction of a decayed weight that remains after `elapsed-ms`, given the
  half-life in days. Shared by the read path (recalls) and the write path
  (plan-recalls)."
  [half-life-days elapsed-ms]
  (Math/pow 0.5 (/ (/ (double elapsed-ms) 86400000.0) (double half-life-days))))

(defn- project-decayed
  "Project a stored decayed weight to `clock`: the weight scaled by the decay
  since its `last` recall, a stored last-request Date. The read and write paths
  share this kernel; each caller keeps its own guard for a missing `last`."
  [half-life-days decayed ^Date last clock]
  (* (double decayed) (decay-factor half-life-days (- clock (.getTime last)))))

(defn- stat-rows
  "The caller's raw stat rows [category label lifetime decayed last-request], one
  per stored (user, category, label). Every stored row, unlike `pair-rows`, which
  lists only the pairs a memory still carries."
  [db user]
  (d/q '[:find ?c ?l ?life ?dec ?last
         :in $ ?u
         :where [?s :stat/user ?u]
                [?s :stat/category ?c]
                [?s :stat/label ?l]
                [?s :stat/lifetime ?life]
                [?s :stat/decayed ?dec]
                [?s :stat/last-request ?last]]
       db user))

(defn recalls
  "Return the caller's recall-count rows: lifetime and the recent decay count
  projected to now."
  [conn user half-life-days]
  (let [now-ms (System/currentTimeMillis)]
    (map (fn [[c l life decayed ^Date last]]
           {:category c :label l :lifetime life
            :recent (project-decayed half-life-days decayed last now-ms)})
         (stat-rows (d/db conn) user))))

(defn- pair-rows
  "Left-join the stat row onto every tag pair on the caller's memories. Return
  raw datalog rows `[category label count lifetime decayed last-request]`, one per
  pair, with `count` the number of the caller's memories that carry the pair. The
  or-join binds the stored lifetime, decayed weight, and last-request when a stat
  row exists, and grounds zero defaults through the not-join branch when none
  does. The no-stat branch grounds last-request to a long rather than a Date, so
  the caller can tell a real row from a defaulted one by its type."
  [db user]
  (d/q '[:find ?c ?l (count-distinct ?e) ?life ?dec ?last
         :in $ ?u
         :where
         [?e :memory/user ?u]
         [?e :memory/tag ?t]
         [?t :tag/category ?c]
         [?t :tag/label ?l]
         (or-join [?u ?c ?l ?life ?dec ?last]
                  (and [?s :stat/user ?u]
                       [?s :stat/category ?c]
                       [?s :stat/label ?l]
                       [?s :stat/lifetime ?life]
                       [?s :stat/decayed ?dec]
                       [?s :stat/last-request ?last])
                  (and (not-join [?u ?c ?l]
                                 [?s :stat/user ?u]
                                 [?s :stat/category ?c]
                                 [?s :stat/label ?l])
                       [(ground 0) ?life]
                       [(ground 0.0) ?dec]
                       [(ground 0) ?last]))]
       db user))

(defn catalog
  "Return the caller's recall catalog: one map per tag pair on the caller's
  memories. Each map carries :category, :label, :count (the number of the
  caller's memories that carry the pair), :lifetime (the monotonic recall count,
  0 when the pair was never recalled), and :recent (the decayed recent count
  projected to now, 0.0 when never recalled). A pair that no recall has touched
  still appears, so a fresh store gives the client pairs to choose from on the
  first recall.

  The pairs and the raw stat columns come from `pair-rows`. The recent
  projection, which datalog cannot compute because it needs the current time,
  runs here: a real Date last-request decays the stored weight, and the grounded
  no-stat row is 0.0."
  [conn user half-life-days]
  (let [now-ms (System/currentTimeMillis)]
    (mapv (fn [[c l n life decayed last]]
            {:category c :label l :count n
             :lifetime (long life)
             :recent   (if (instance? Date last)
                         (project-decayed half-life-days decayed last now-ms)
                         0.0)})
          (pair-rows (d/db conn) user))))

(defn- extant-recalls [db user category label]
  (d/q '[:find [?life ?dec ?last]
         :in $ ?u ?c ?l
         :where [?s :stat/user ?u]
                [?s :stat/category ?c]
                [?s :stat/label ?l]
                [?s :stat/lifetime ?life]
                [?s :stat/decayed ?dec]
                [?s :stat/last-request ?last]]
       db user category label))

(defn plan-recalls
  "Pure. Build the batched stat transaction for `user` over `pair-counts`, a map
  of [category label] to a coalesced count. For each pair, read the current row
  from `db`, decay the stored weight to `flush-ms`, and add the count to the
  lifetime and to the decayed weight. Return a vector of tx-maps, empty when
  `pair-counts` is empty. Take no connection and perform no write."
  [db user half-life-days pair-counts flush-ms]
  (mapv (fn [[[c l] n]]
          (let [[life decayed ^Date last] (extant-recalls db user c l)
                base (if last
                       (project-decayed half-life-days decayed last flush-ms)
                       0.0)]
            {:stat/user user :stat/category c :stat/label l
             :stat/lifetime (+ (long (or life 0)) (long n))
             :stat/decayed  (+ base (double n))
             :stat/last-request (Date. flush-ms)}))
        pair-counts))

(defn by-user
  "Regroup the pending recalls keyed by [user category label] into a map of user
  to a map of [category label] to count. Public for the write consumer in
  `engram.stats.writer`."
  [pending-recalls]
  (reduce (fn [m [[user c l] n]] (assoc-in m [user [c l]] n)) {} pending-recalls))

;; ---------- per-memory recall count ----------

(defn- extant-memory-recall
  "The memory's stored recall columns [lifetime decayed last-recalled], or nil
  when the memory carries no recall count yet (its first recall)."
  [db ^UUID uuid]
  (d/q '[:find [?life ?dec ?last]
         :in $ ?id
         :where [?e :memory/id ?id]
                [?e :memory/recall-lifetime ?life]
                [?e :memory/recall-decayed ?dec]
                [?e :memory/last-recalled ?last]]
       db uuid))

(defn plan-memory-recalls
  "Pure. Build the batched memory-recall transaction over `id-counts`, a map of
  memory-id string to a coalesced count. For each id, read the memory's current
  recall columns, decay the stored weight to `flush-ms`, and add the count to
  both the lifetime and the decayed weight. The upsert is by `:memory/id`. Return
  a vector of tx-maps, empty when `id-counts` is empty. Take no connection and
  perform no write."
  [db half-life-days id-counts flush-ms]
  (mapv (fn [[id n]]
          (let [uuid (UUID/fromString id)
                [life decayed ^Date last] (extant-memory-recall db uuid)
                base (if last (project-decayed half-life-days decayed last flush-ms) 0.0)]
            {:memory/id              uuid
             :memory/recall-lifetime (+ (long (or life 0)) (long n))
             :memory/recall-decayed  (+ base (double n))
             :memory/last-recalled   (Date. (long flush-ms))}))
        id-counts))

;; ---------- recall-count write strategies ----------
;;
;; One kind of recall count per record. The async stat writer holds a strategy
;; and stays agnostic: `->pending` folds recorded items into the pending map on
;; the absorb step, and `->writes` turns the pending map into tx-data at flush.
;; A later kind of counted read adds a third record without touching the writer.

(defprotocol RecallCountWritable
  (->pending [strategy pending user xs])
  (->writes  [strategy pending db flush-ms]))

(defrecord TagRecallCount [half-life-days]
  RecallCountWritable
  (->pending [_ pending user pairs]
    (reduce (fn [b [c l]] (update b [user c l] (fnil inc 0))) pending pairs))
  (->writes [_ pending db flush-ms]
    (vec (mapcat (fn [[user pcs]] (plan-recalls db user half-life-days pcs flush-ms))
                 (by-user pending)))))

(defrecord MemoryRecallCount [half-life-days]
  RecallCountWritable
  (->pending [_ pending _user ids]
    (reduce (fn [b id] (update b id (fnil inc 0))) pending ids))
  (->writes [_ pending db flush-ms]
    (plan-memory-recalls db half-life-days pending flush-ms)))

;; ---------- memory aggregates (conformance + freshness) ----------
;;
;; One pass over the caller's memories feeds every /stats aggregate that scans
;; the whole store: the conforming fraction (ticket 09) and the three freshness
;; aggregates (ticket 15). The read pulls each memory once; the reduce is pure.

(def ^:private hot-threshold
  "The recent recall count at or above which a memory counts as hot: about one
  recall within the last half-life."
  0.5)

(defn- aggregate-rows
  "Pull each of the caller's memories once, with the columns the /stats
  aggregates need: the tags for conformance, the freshness timestamps, and the
  recall-count columns for the recent count."
  [db user]
  (d/q '[:find [(pull ?e [:memory/last-confirmed :memory/updated-at :memory/created-at
                          :memory/recall-decayed :memory/last-recalled
                          {:memory/tag [:tag/category :tag/label]}]) ...]
         :in $ ?u
         :where [?e :memory/user ?u]]
       db user))

(defn- recent-count
  "The memory's recent recall count projected to `now`: the stored decayed weight
  decayed by the recall half-life since its last recall, or 0.0 when the memory
  was never recalled."
  [recall-half-life-days m ^long now]
  (if-let [^Date last (:memory/last-recalled m)]
    (project-decayed recall-half-life-days (:memory/recall-decayed m) last now)
    0.0))

(defn aggregate-memories
  "Pure. Reduce the caller's pulled memory `rows` into the /stats whole-store
  aggregates, in one pass. `reject?` is the conformance predicate: it takes a
  memory's [category label] pairs and returns truthy when the memory conforms to
  no configuration. `freshness-half-life-days` shapes each memory's freshness
  value and its stale band. `recall-half-life-days` shapes each memory's recent
  recall count. `now` is the clock in epoch millis.

  Return a map of:
  - :conforming-fraction, the share of memories that conform.
  - :mean-freshness, the plain mean freshness value, every memory weighted
    equally. A higher value means a fresher store.
  - :use-weighted-freshness, the mean freshness value weighted by each memory's
    recent recall count, so the facts the caller loads most shape it most. It
    falls back to the plain mean when no memory has a recent count.
  - :hot-and-stale-fraction, the share of memories that are both hot (a recent
    count at or above 0.5) and stale (in the stale band). A higher value means
    more of the store is used but overdue.

  Every value is 0.0 when the caller owns no memory."
  [rows reject? freshness-half-life-days recall-half-life-days now]
  (let [{:keys [total conforming sum-fresh sum-recent sum-weighted hot-stale]}
        (reduce
         (fn [a m]
           (let [tags    (mapv (fn [t] [(:tag/category t) (:tag/label t)]) (:memory/tag m))
                 ^Date t (or (:memory/last-confirmed m) (:memory/updated-at m) (:memory/created-at m))
                 age     (- now (.getTime t))
                 fv      (freshness/value freshness-half-life-days age)
                 stale?  (freshness/stale? freshness-half-life-days age)
                 recent  (recent-count recall-half-life-days m now)
                 hot?    (>= recent hot-threshold)]
             (cond-> (-> a
                         (update :total inc)
                         (update :sum-fresh + fv)
                         (update :sum-recent + recent)
                         (update :sum-weighted + (* recent fv)))
               (not (reject? tags)) (update :conforming inc)
               (and hot? stale?)    (update :hot-stale inc))))
         {:total 0 :conforming 0 :sum-fresh 0.0 :sum-recent 0.0 :sum-weighted 0.0 :hot-stale 0}
         rows)]
    (if (zero? total)
      {:conforming-fraction 0.0 :mean-freshness 0.0 :use-weighted-freshness 0.0 :hot-and-stale-fraction 0.0}
      (let [mean-fresh (/ sum-fresh (double total))]
        {:conforming-fraction    (/ conforming (double total))
         :mean-freshness         mean-fresh
         :use-weighted-freshness (if (pos? sum-recent) (/ sum-weighted sum-recent) mean-fresh)
         :hot-and-stale-fraction (/ hot-stale (double total))}))))

(defn memory-aggregates
  "The caller's /stats whole-store aggregates in one pass: the conforming
  fraction and the three freshness aggregates. `reject?` injects the conformance
  check. The half-lives are the admin recall and freshness half-lives in days.
  See `aggregate-memories` for the returned map."
  [conn user reject? freshness-half-life-days recall-half-life-days]
  (aggregate-memories (aggregate-rows (d/db conn) user)
                      reject? freshness-half-life-days recall-half-life-days
                      (System/currentTimeMillis)))

;; ---------- link density ----------

(defn- component-from
  "The set of nodes reachable from `start` through `adj`, skipping any node in
  `seen`. This is one weakly-connected component, found by a depth-first walk."
  [adj seen start]
  (loop [stack [start], component #{}]
    (if-let [x (peek stack)]
      (let [stack (pop stack)]
        (if (or (seen x) (component x))
          (recur stack component)
          (recur (into stack (adj x)) (conj component x))))
      component)))

(defn- largest-component-size
  "Size of the largest weakly-connected component over `nodes`, using `adj`, an
  undirected adjacency map of node to a neighbor set. Walk each unseen node's
  component once and keep the largest."
  [nodes adj]
  (second
   (reduce (fn [[seen largest] x]
             (if (seen x)
               [seen largest]
               (let [c (component-from adj seen x)]
                 [(into seen c) (max largest (count c))])))
           [#{} 0]
           nodes)))

(defn- src-nodes
  "The set of the caller's src values, which are the nodes of the link graph."
  [db user]
  (set (d/q '[:find [?src ...]
              :in $ ?u
              :where [?e :memory/user ?u] [?e :memory/src ?src]]
            db user)))

(defn- src-related-pairs
  "Every [src related] pair on the caller's memories. These are the raw directed
  edges, before the filter down to real nodes."
  [db user]
  (d/q '[:find ?src ?rel
         :in $ ?u
         :where [?e :memory/user ?u]
                [?e :memory/src ?src]
                [?e :memory/related ?rel]]
       db user))

(defn link-density
  "Return the caller's memory-graph link density. `:avg-out-degree` is the mean
  number of distinct related-src edges per src node. `:largest-wcc-fraction` is
  the size of the largest weakly-connected component over the src nodes, as a
  fraction of the node count. A node is one of the caller's src values. A directed
  edge runs from a memory's src to each related src that is itself a node, apart
  from a self-edge and an edge to a src no memory owns. Both values are 0.0 when
  the caller owns no memory."
  [conn user]
  (let [db    (d/db conn)
        nodes (src-nodes db user)
        edges (set (for [[s r] (src-related-pairs db user)
                         :when (and (contains? nodes r) (not= s r))] [s r]))
        n     (count nodes)]
    (if (zero? n)
      {:avg-out-degree 0.0 :largest-wcc-fraction 0.0}
      (let [adj (reduce (fn [m [s r]]
                          (-> m (update s (fnil conj #{}) r)
                              (update r (fnil conj #{}) s)))
                        {} edges)]
        {:avg-out-degree       (double (/ (count edges) n))
         :largest-wcc-fraction (double (/ (largest-component-size nodes adj) n))}))))
