(ns engram.memory
  "Memory storage over Datalevin. Stateless: every function takes the connection
  as its first argument. Lifecycle lives in engram.system.

  A memory is one atomic fact owned by a user. Reads and writes always filter by
  user, so one user never sees another's memories. A query matches memories by
  category:label pairs, then walks the related-by-src graph transitively to pull
  in linked memories the pairs did not name."
  (:require [datalevin.core :as d]
            [clojure.set :as st]
            [engram.errors :as errors])
  (:import [java.util UUID Date]))

(def ^:private pull-pattern
  '[:memory/id :memory/content :memory/src :memory/related
    :memory/created-at :memory/updated-at
    {:memory/tag [:tag/category :tag/label]}])

(defn- ->wire
  "Shape a pulled memory into the JSON wire form (dates as strings)."
  [m]
  {:id         (str (:memory/id m))
   :content    (:memory/content m)
   :src        (:memory/src m)
   :related    (vec (:memory/related m))
   :tags       (mapv (fn [t] [(:tag/category t) (:tag/label t)]) (:memory/tag m))
   :created-at (some-> ^Date (:memory/created-at m) .toInstant str)
   :updated-at (some-> ^Date (:memory/updated-at m) .toInstant str)})

(defn- tag-tx [[category label]] {:tag/category category :tag/label label})

;; ---------- writes ----------

(defn- create-tx
  "Return [uuid tx-map] for one create payload owned by `user`. The single build
  path for a new memory entity."
  [user {:keys [content src tags related]}]
  (let [id  (UUID/randomUUID)
        now (Date.)]
    [id (cond-> {:memory/id id
                 :memory/user user
                 :memory/content content
                 :memory/src src
                 :memory/created-at now
                 :memory/updated-at now}
          (seq tags)    (assoc :memory/tag (mapv tag-tx tags))
          (seq related) (assoc :memory/related (vec related)))]))

(defn create!
  "Create one atomic fact for `user`. Returns the new id as a string."
  [conn user payload]
  (let [[id mem] (create-tx user payload)]
    (d/transact! conn [mem])
    (str id)))

(defn- eid-of [db user id-str]
  (d/q '[:find ?e .
         :in $ ?id ?u
         :where [?e :memory/id ?id] [?e :memory/user ?u]]
       db (UUID/fromString id-str) user))

(defn- update-tx
  "Tx-data that sets `eid` to the merged `fields`, replacing tags and related in
  place. Only a field present in `fields` is touched. The single build path for
  an in-place correction."
  [db eid fields]
  (let [old-tags (when (contains? fields :tags)
                   (d/q '[:find [?t ...] :in $ ?e :where [?e :memory/tag ?t]] db eid))
        old-rel  (when (contains? fields :related)
                   (d/q '[:find [?r ...] :in $ ?e :where [?e :memory/related ?r]] db eid))
        retracts (concat (map (fn [t] [:db/retractEntity t]) old-tags)
                         (map (fn [r] [:db/retract eid :memory/related r]) old-rel))
        base (cond-> {:db/id eid :memory/updated-at (Date.)}
               (contains? fields :content) (assoc :memory/content (:content fields))
               (contains? fields :tags)    (assoc :memory/tag (mapv tag-tx (:tags fields)))
               (contains? fields :related) (assoc :memory/related (vec (:related fields))))]
    (concat retracts [base])))

(defn- ->fields
  "Reduce a supplied {:content :tags :related} payload to the fields to touch:
  content when present, tags and related only when non-empty."
  [{:keys [content tags related]}]
  (cond-> {}
    (some? content) (assoc :content content)
    (seq tags)      (assoc :tags tags)
    (seq related)   (assoc :related related)))

(defn update!
  "Correct a fact in place. Replaces content, tags, and related when supplied.
  Returns the id string, or nil when the memory does not exist for this user."
  [conn user id payload]
  (let [db  (d/db conn)
        eid (eid-of db user id)]
    (when eid
      (d/transact! conn (update-tx db eid (->fields payload)))
      id)))

(defn delete!
  "Delete `user`'s own memory. Retracting the entity also retracts its component
  tags. Returns the id string, or nil when the memory does not exist for this
  user."
  [conn user id]
  (let [eid (eid-of (d/db conn) user id)]
    (when eid
      (d/transact! conn [[:db/retractEntity eid]])
      id)))

;; ---------- batch ----------

(defn- merge-update-fields
  "Fold one update payload onto an entity's pending fields, last write winning
  per field. Reuses `->fields`, so tags and related fold only when non-empty,
  matching the single-write update path."
  [cur payload]
  (merge cur (->fields payload)))

(def ^:private conflict-msg "the id is in both the update and the delete group")
(def ^:private not-found-msg "no such memory for this user")

(defn plan-batch
  "Plan a grouped batch for `user` against the database value `db`. Pure: it
  reads `db` and writes nothing. `batch` is a map with optional :create, :update,
  and :delete lists. `tag-error` maps a tag vector to an error map or nil.

  Pre-validate against `db`: a create or an update with tags is checked against
  the configurations, an update or delete is resolved to one of the user's own
  memories, and an id must not appear in both the update and the delete group.
  Within a group, several updates to one id fold cumulatively (a later entry wins
  a same-field tie) and a repeated delete id is deduplicated. If all pass, return
  {:ok? true :ids [create-ids...] :tx-data [...] :applied n}; else return
  {:ok? false :errors [{:op :i :code ...} ...]} for the failing ops only. The
  index :i is 0-based within the op's own group list."
  [db user batch tag-error]
  (let [creates (vec (:create batch))
        updates (vec (:update batch))
        deletes (vec (:delete batch))
        conflicts (st/intersection (set (map :id updates)) (set deletes))
        create-errs
        (keep-indexed
         (fn [i p] (when-let [e (tag-error (or (:tags p) []))]
                     (merge {:op "create" :i i} e)))
         creates)
        update-results
        (map-indexed
         (fn [i p]
           (let [id (:id p)]
             (cond
               (conflicts id) {:error {:op "update" :i i :code errors/conflict :message conflict-msg}}
               :else (if-let [eid (eid-of db user id)]
                       (if-let [e (when (seq (:tags p)) (tag-error (:tags p)))]
                         {:error (merge {:op "update" :i i} e)}
                         {:eid eid :payload p})
                       {:error {:op "update" :i i :code errors/not-found :message not-found-msg}}))))
         updates)
        delete-results
        (map-indexed
         (fn [i id]
           (cond
             (conflicts id) {:error {:op "delete" :i i :code errors/conflict :message conflict-msg}}
             :else (if-let [eid (eid-of db user id)]
                     {:eid eid}
                     {:error {:op "delete" :i i :code errors/not-found :message not-found-msg}})))
         deletes)
        errors (vec (concat create-errs
                            (keep :error update-results)
                            (keep :error delete-results)))]
    (if (seq errors)
      {:ok? false :errors errors}
      (let [pairs          (mapv #(create-tx user %) creates)
            ids            (mapv (comp str first) pairs)
            create-tx-data (map second pairs)
            fold           (reduce (fn [m {:keys [eid payload]}]
                                     (update m eid merge-update-fields payload))
                                   {} update-results)
            update-tx-data (mapcat (fn [[eid fields]] (update-tx db eid fields)) fold)
            delete-eids    (distinct (map :eid delete-results))
            delete-tx-data (map (fn [eid] [:db/retractEntity eid]) delete-eids)
            tx-data        (vec (concat create-tx-data update-tx-data delete-tx-data))]
        {:ok? true :ids ids :tx-data tx-data
         :applied (+ (count ids) (count fold) (count delete-eids))}))))

(defn apply-batch!
  "Apply a grouped batch for one user in one atomic transaction. Delegate the
  validation and tx-data planning to `plan-batch` over the current db value, then
  transact once on success. Return {:ok? true :ids [...] :applied n} on success,
  or {:ok? false :errors [...]} on failure, writing nothing on failure."
  [conn user batch tag-error]
  (let [result (plan-batch (d/db conn) user batch tag-error)]
    (if (:ok? result)
      (do (when (seq (:tx-data result)) (d/transact! conn (:tx-data result)))
          {:ok? true :ids (:ids result) :applied (:applied result)})
      result)))

;; ---------- query ----------

(defn- eids-by-pair [db user [category label]]
  (d/q '[:find ?e (distinct ?r)
         :in $ ?u ?c ?l
         :where [?e :memory/user ?u]
                [?e :memory/tag ?t]
                [?t :tag/category ?c]
                [?t :tag/label ?l]
         (or-join [?e ?r]
                  [?e :memory/related ?r]
                  (and (not-join [?e] [?e :memory/related _])
                       [(ground :none) ?r]))]
       db user category label))

(defn- eids-by-srcs [db user srcs]
  (if (seq srcs)
    (d/q '[:find ?e (distinct ?r)
           :in $ ?u [?s ...]
           :where [?e :memory/user ?u]
                  [?e :memory/src ?s]
           (or-join [?e ?r]
                    [?e :memory/related ?r]
                    (and (not-join [?e] [?e :memory/related _])
                         [(ground :none) ?r]))]
         db user (vec srcs))
    []))

(defn- query'
  "Walk the transitive related-by-src closure, emitting one wire memory per
  entity as a lazy sequence, deduplicated by `seen`. `pending` is a queue of
  matched rows awaiting emission, each an `[eid srcs]` pair. `frontier` is the
  set of related srcs still to expand. `lookup+args` is a sequence of
  `[lookup arg]` pairs, each a deferred lookup that yields more rows when
  applied. The emitted values are pulled from `db`, an immutable snapshot, so
  they stay valid only while the connection that produced it is open."
  [db user seen pending frontier lookup+args]
  (cond
    (seq pending)
    (loop [[[eid srcs] & more] pending]
      (cond
        (nil? eid) (lazy-seq (query' db user seen [] frontier lookup+args))
        (seen eid) (recur more)
        :else (cons (->wire (d/pull db pull-pattern eid))
                    (lazy-seq
                     (query' db user (conj seen eid) more
                             (st/union frontier srcs) lookup+args)))))

    (seq lookup+args)
    (let [[[lookup arg] & more] lookup+args]
      (recur db user seen (lookup arg) frontier more))

    (seq frontier)
    (recur db user seen pending #{}
           (conj lookup+args [(partial eids-by-srcs db user)
                              (disj frontier :none)]))))

(defn query
  "Return a lazy seq of wire memories: the pair matches for `user` plus the
  transitive related-by-src closure, deduped. Responses are not truncated. The
  seq is lazy over an immutable db snapshot, so realize it while `conn` is open."
  [conn user pairs]
  (let [db (d/db conn)
        f (partial eids-by-pair db user)]
    (query' db user #{} [] #{} (map (partial vector f) pairs))))
