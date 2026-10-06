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
  (:require [datalevin.core :as d])
  (:import [java.util Date]))

(defn- decay-factor
  "The fraction of a decayed weight that remains after `elapsed-ms`, given the
  half-life in days. Shared by the read path (recalls) and the write path
  (plan-recalls)."
  [half-life-days elapsed-ms]
  (Math/pow 0.5 (/ (/ (double elapsed-ms) 86400000.0) (double half-life-days))))

(defn recalls
  "Return the caller's recall-count rows: lifetime and the recent decay count
  projected to now."
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
    (mapv (fn [[c l n life dec last]]
            {:category c :label l :count n
             :lifetime (long life)
             :recent   (if (instance? Date last)
                         (* (double dec)
                            (decay-factor half-life-days (- now-ms (.getTime ^Date last))))
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
          (let [[life dec ^Date last] (extant-recalls db user c l)
                base (if last
                       (* (double dec)
                          (decay-factor half-life-days (- flush-ms (.getTime last))))
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
