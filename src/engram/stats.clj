(ns engram.stats
  "Per-user request stats over Datalevin. Stateless: connection first.

  For each (user, category, label) pair we keep a monotonic lifetime count and
  an exponential-decay recent count. The recent count is updated on each fetch
  and projected to the current time on read, using the half-life from the admin
  config."
  (:require [datalevin.core :as d])
  (:import [java.util Date]))

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
