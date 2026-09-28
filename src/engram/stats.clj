(ns engram.stats
  "Per-user recall stats over Datalevin. Stateless: connection first.

  For each (user, category, label) pair we keep a monotonic lifetime count and
  an exponential-decay recent count. The recent count is updated on each recall
  and projected to the current time on read, using the half-life from the admin
  config.

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
