(ns engram.batch
  "Shared machinery for the strict grouped-map batches: short-term and index. Both
  take a {:create :update :delete} map and apply it atomically, with the id rules
  the memory batch uses plus one stricter rule. A repeated id in the update group
  rejects with duplicate-id, where the memory batch would fold. These batches run
  no related repair and no tag validation, so the shared code is id-rule validation
  plus tx-building through injected builders.

  `plan` is pure: it reads the db only through the injected `resolve`, which maps
  an id to an eid or nil over the commit-time db, scoped to the user. `apply!`
  wraps `plan` in one `:db.fn/call` transaction, so the validation and the commit
  are one atomic unit. A caller supplies a builders function of the commit-time db,
  so `resolve` and the tx-builders close over the right snapshot."
  (:require [clojure.set :as st]
            [datalevin.core :as d]
            [engram.errors :as errors])
  (:import [java.util UUID]))

(def ^:private conflict-msg "the id is in both the update and the delete group")
(def ^:private duplicate-msg "the id appears more than once in the update group")
(def ^:private not-found-msg "no such item for this user")

(defn plan
  "Validate a grouped `batch` for one user and build its tx-data. `create-ids` are
  the minted uuids, one per create in order. The builder map carries:

  - `:resolve`   (fn [id] eid-or-nil) over the commit-time db, scoped to the user.
  - `:create-tx` (fn [id payload] tx-map) for a create, given its minted id.
  - `:update-tx` (fn [eid payload] tx-data) for an update op.
  - `:delete-tx` (fn [eid] tx-data) for a delete op.

  Return {:ok? true :ids [str...] :tx-data [...] :applied n} when every op passes,
  else {:ok? false :errors [{:op :i :code :message} ...]} for the failing ops only.
  Each id resolves once: an update or delete result carries its eid, so the
  tx-building reuses it rather than re-querying. An id in both the update and the
  delete group is a conflict, which wins over duplicate-id. A repeated delete id
  deduplicates, so the applied count counts distinct delete eids."
  [batch create-ids {:keys [resolve create-tx update-tx delete-tx]}]
  (let [creates   (vec (:create batch))
        updates   (vec (:update batch))
        deletes   (vec (:delete batch))
        conflicts (st/intersection (set (map :id updates)) (set deletes))
        dups      (->> (map :id updates) frequencies
                       (keep (fn [[id n]] (when (> n 1) id))) set)
        update-results
        (map-indexed
         (fn [i {:keys [id] :as p}]
           (cond
             (conflicts id) {:error {:op "update" :i i :code errors/conflict :message conflict-msg}}
             (dups id)      {:error {:op "update" :i i :code errors/duplicate-id :message duplicate-msg}}
             :else (if-let [eid (resolve id)]
                     {:eid eid :payload p}
                     {:error {:op "update" :i i :code errors/not-found :message not-found-msg}})))
         updates)
        delete-results
        (map-indexed
         (fn [i id]
           (cond
             (conflicts id) {:error {:op "delete" :i i :code errors/conflict :message conflict-msg}}
             :else (if-let [eid (resolve id)]
                     {:eid eid}
                     {:error {:op "delete" :i i :code errors/not-found :message not-found-msg}})))
         deletes)
        errs (vec (concat (keep :error update-results) (keep :error delete-results)))]
    (if (seq errs)
      {:ok? false :errors errs}
      (let [delete-eids (distinct (map :eid delete-results))
            tx-data     (vec (concat
                              (map create-tx create-ids creates)
                              (mapcat (fn [{:keys [eid payload]}] (update-tx eid payload)) update-results)
                              (mapcat delete-tx delete-eids)))]
        {:ok? true
         :ids     (mapv str create-ids)
         :tx-data tx-data
         :applied (+ (count creates) (count updates) (count delete-eids))}))))

(defn apply!
  "Apply a strict `batch` for one user atomically. `builders` is (fn [db] {...})
  giving `resolve` and the tx-builders against the commit-time db, so `plan` and
  the commit are one `:db.fn/call` unit and no concurrent write stales them. The
  create ids are minted here. A rejected batch throws its error vector from inside
  the function, which aborts the transaction and leaves the store untouched.
  Return {:ok? true :ids [...] :applied n} on success, or {:ok? false :errors [...]}
  on failure."
  [conn batch builders]
  (let [creates    (vec (:create batch))
        create-ids (mapv (fn [_] (UUID/randomUUID)) creates)]
    (try
      (d/transact!
       conn
       [[:db.fn/call
         (fn [db]
           (let [result (plan batch create-ids (builders db))]
             (if (:ok? result)
               (:tx-data result)
               (throw (ex-info "batch rejected" {::errors (:errors result)})))))]])
      {:ok? true
       :ids (mapv str create-ids)
       :applied (+ (count creates) (count (:update batch)) (count (distinct (:delete batch))))}
      (catch clojure.lang.ExceptionInfo e
        (if-let [errs (::errors (ex-data e))]
          {:ok? false :errors errs}
          (throw e))))))
