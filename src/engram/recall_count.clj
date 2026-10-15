(ns engram.recall-count
  "Per-user recall counts over Datalevin, served at the `/recalls` endpoint.
  Stateless: connection first.

  For each (user, category, label) pair engram keeps a monotonic lifetime count
  and an exponential-decay recent count. A per-memory recall count mirrors the
  same two measures on the memory entity. The recent count is updated on each
  recall and projected to the current time on read, using the half-life from the
  admin config.

  This namespace is the functional core: the read projection and the pure
  planners. The operational consumer, which owns the agent and the executors,
  lives in `engram.recall-count.writer`. The `/stats` aggregate reporting is a
  separate concern and lives in `engram.stats`."
  (:require [datalevin.core :as d]
            [engram.decay :as decay])
  (:import [java.util Date UUID]))

(defn- stored-rows
  "The caller's raw recall-count rows [category label lifetime decayed
  last-recalled], one per stored (user, category, label). Every stored row,
  unlike `pair-rows`, which lists only the pairs a memory still carries."
  [db user]
  (d/q '[:find ?c ?l ?life ?dec ?last
         :in $ ?u
         :where [?s :recall/user ?u]
                [?s :recall/category ?c]
                [?s :recall/label ?l]
                [?s :recall/lifetime ?life]
                [?s :recall/decayed ?dec]
                [?s :recall/last-recalled ?last]]
       db user))

(defn all-recall-rows
  "Return the caller's recall-count rows: lifetime and the recent decay count
  projected to now. Reads every stored row, including a pair no live memory
  carries, so it is the raw view behind the live-pair `catalog`."
  [conn user half-life-days]
  (let [now-ms (System/currentTimeMillis)]
    (map (fn [[c l life decayed ^Date last]]
           {:category c :label l :lifetime life
            :recent (decay/project half-life-days decayed last now-ms)})
         (stored-rows (d/db conn) user))))

(defn- pair-rows
  "Left-join the recall-count row onto every tag pair on the caller's memories.
  Return raw datalog rows `[category label count lifetime decayed last-recalled]`,
  one per pair, with `count` the number of the caller's memories that carry the
  pair. The or-join binds the stored lifetime, decayed weight, and last-recalled
  when a row exists, and grounds zero defaults through the not-join branch when
  none does. The no-row branch grounds last-recalled to a long rather than a Date,
  so the caller can tell a real row from a defaulted one by its type."
  [db user]
  (d/q '[:find ?c ?l (count-distinct ?e) ?life ?dec ?last
         :in $ ?u
         :where
         [?e :memory/user ?u]
         [?e :memory/tag ?t]
         [?t :tag/category ?c]
         [?t :tag/label ?l]
         (or-join [?u ?c ?l ?life ?dec ?last]
                  (and [?s :recall/user ?u]
                       [?s :recall/category ?c]
                       [?s :recall/label ?l]
                       [?s :recall/lifetime ?life]
                       [?s :recall/decayed ?dec]
                       [?s :recall/last-recalled ?last])
                  (and (not-join [?u ?c ?l]
                                 [?s :recall/user ?u]
                                 [?s :recall/category ?c]
                                 [?s :recall/label ?l])
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

  The pairs and the raw stored columns come from `pair-rows`. The recent
  projection, which datalog cannot compute because it needs the current time,
  runs here: a real Date last-recalled decays the stored weight, and the grounded
  no-row is 0.0."
  [conn user half-life-days]
  (let [now-ms (System/currentTimeMillis)]
    (mapv (fn [[c l n life decayed last]]
            {:category c :label l :count n
             :lifetime (long life)
             :recent   (if (instance? Date last)
                         (decay/project half-life-days decayed last now-ms)
                         0.0)})
          (pair-rows (d/db conn) user))))

(defn- extant-recall
  "The recall-count row for the pair, as `[eid lifetime decayed last-recalled]`,
  or nil when no row exists yet. The eid lets the write target the existing row
  inside a transaction function, where a new row needs an explicit tempid
  instead."
  [db user category label]
  (d/q '[:find [?s ?life ?dec ?last]
         :in $ ?u ?c ?l
         :where [?s :recall/user ?u]
                [?s :recall/category ?c]
                [?s :recall/label ?l]
                [?s :recall/lifetime ?life]
                [?s :recall/decayed ?dec]
                [?s :recall/last-recalled ?last]]
       db user category label))

(defn plan-recalls
  "Pure. Build the batched recall-count transaction for `user` over
  `pair-counts`, a map of [category label] to a coalesced count. For each pair,
  read the current row from `db`, decay the stored weight to `flush-ms`, and add
  the count to the lifetime and to the decayed weight. An existing row writes by
  its eid, and a new row carries a tempid, so the map transacts inside a
  transaction function. Return a vector of tx-maps, empty when `pair-counts` is
  empty. Take no connection and perform no write."
  [db user half-life-days pair-counts flush-ms]
  (mapv (fn [[[c l] n]]
          (let [[eid life decayed ^Date last] (extant-recall db user c l)
                base (if last
                       (decay/project half-life-days decayed last flush-ms)
                       0.0)]
            {:db/id (or eid (str (gensym "recall")))
             :recall/user user :recall/category c :recall/label l
             :recall/lifetime (+ (long (or life 0)) (long n))
             :recall/decayed  (+ base (double n))
             :recall/last-recalled (Date. flush-ms)}))
        pair-counts))

(defn by-user
  "Regroup the pending recalls keyed by [user category label] into a map of user
  to a map of [category label] to count. Public for the write consumer in
  `engram.recall-count.writer`."
  [pending-recalls]
  (reduce (fn [m [[user c l] n]] (assoc-in m [user [c l]] n)) {} pending-recalls))

;; ---------- per-memory recall count ----------

(defn- memory-eid
  "The eid of the memory with `uuid`, or nil when no such memory exists. The
  existence check that lets a flush skip a memory a concurrent delete removed."
  [db ^UUID uuid]
  (d/q '[:find ?e . :in $ ?id :where [?e :memory/id ?id]] db uuid))

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
  both the lifetime and the decayed weight. Skip an id whose memory is gone, so a
  flush that loses a race to a delete drops the count rather than resurrecting a
  ghost. The write targets the memory's eid. Return a vector of tx-maps, empty
  when `id-counts` is empty. Take no connection and perform no write."
  [db half-life-days id-counts flush-ms]
  (into []
        (keep (fn [[id n]]
                (let [uuid (UUID/fromString id)]
                  (when-let [eid (memory-eid db uuid)]
                    (let [[life decayed ^Date last] (extant-memory-recall db uuid)
                          base (if last (decay/project half-life-days decayed last flush-ms) 0.0)]
                      {:db/id                  eid
                       :memory/recall-lifetime (+ (long (or life 0)) (long n))
                       :memory/recall-decayed  (+ base (double n))
                       :memory/last-recalled   (Date. (long flush-ms))})))))
        id-counts))

;; ---------- recall-count write strategies ----------
;;
;; One kind of recall count per record. The async writer holds a strategy and
;; stays agnostic: `->pending` folds recorded items into the pending map on the
;; absorb step, and `->writes` turns the pending map into tx-data at flush. A
;; later kind of counted read adds a third record without touching the writer.

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
