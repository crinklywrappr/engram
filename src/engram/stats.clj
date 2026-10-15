(ns engram.stats
  "Per-user whole-store aggregates over Datalevin for the `/stats` endpoint.
  Stateless: connection first.

  The namespace computes two kinds of aggregate. The memory aggregates are the
  conforming fraction and the three freshness aggregates, in one pass over the
  caller's memories. Link density is the graph-structure measure over the
  caller's src nodes and related edges.

  The recall counts served at `/recalls` are a separate concern and live in
  `engram.recall-count`, with their write consumer in
  `engram.recall-count.writer`."
  (:require [datalevin.core :as d]
            [engram.freshness :as freshness]
            [engram.memory :as memory]))

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
                 age     (memory/age-ms m now)
                 fv      (freshness/value freshness-half-life-days age)
                 stale?  (freshness/stale? freshness-half-life-days age)
                 recent  (memory/recent-count recall-half-life-days m now)
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
