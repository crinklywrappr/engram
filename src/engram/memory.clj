(ns engram.memory
  "Memory storage over Datalevin. Stateless: every function takes the connection
  as its first argument. Lifecycle lives in engram.system.

  A memory is one atomic fact owned by a user. Reads and writes always filter by
  user, so one user never sees another's memories. A query matches memories by
  category:label pairs, then walks the related-by-src graph transitively to pull
  in linked memories the pairs did not name."
  (:require [datalevin.core :as d]
            [clojure.set :as st])
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
  {:fields (merge (:fields cur) (->fields payload))})

(defn apply-batch!
  "Apply an ordered, flattened op list for one user in one atomic transaction.
  Each op is {:i n :op verb :payload p}, verb one of \"create\", \"update\",
  \"delete\". `tag-error` maps a tag vector to an error map or nil.

  Pre-validate every op against the batch's starting state: a create or an update
  with tags is checked against the configurations, an update or delete is resolved
  to one of the user's own memories, and a delete makes its id terminal so a later
  op on that id fails. If all pass, transact once and return
  {:ok? true :ids [create-ids...] :applied n}; else write nothing and return
  {:ok? false :errors [{:i :op :error ...} ...]} for the failing ops only."
  [conn user ops tag-error]
  (let [db (d/db conn)]
    (loop [[{:keys [i op payload] :as o} & more] ops
           deleted #{}
           creates []
           fold    {}
           errors  []]
      (if (nil? o)
        (if (seq errors)
          {:ok? false :errors errors}
          (let [pairs          (mapv #(create-tx user %) creates)
                ids            (mapv (comp str first) pairs)
                create-tx-data (map second pairs)
                upd-tx-data    (mapcat (fn [[eid st]]
                                         (if (:deleted st)
                                           [[:db/retractEntity eid]]
                                           (update-tx db eid (:fields st))))
                                       fold)
                tx-data        (concat create-tx-data upd-tx-data)]
            (when (seq tx-data) (d/transact! conn tx-data))
            {:ok? true :ids ids :applied (count ops)}))
        (case op
          "create"
          (let [err (tag-error (or (:tags payload) []))]
            (recur more deleted (conj creates payload) fold
                   (cond-> errors err (conj (merge {:i i :op op} err)))))

          "update"
          (let [id (:id payload)]
            (if (deleted id)
              (recur more deleted creates fold
                     (conj errors {:i i :op op :error "deleted"
                                   :message "the memory was deleted earlier in the batch"}))
              (if-let [eid (eid-of db user id)]
                (if-let [err (when (seq (:tags payload)) (tag-error (:tags payload)))]
                  (recur more deleted creates fold (conj errors (merge {:i i :op op} err)))
                  (recur more deleted creates
                         (update fold eid merge-update-fields payload)
                         errors))
                (recur more deleted creates fold
                       (conj errors {:i i :op op :error "not-found"
                                     :message "no such memory for this user"})))))

          "delete"
          (let [id payload]
            (if (deleted id)
              (recur more deleted creates fold
                     (conj errors {:i i :op op :error "deleted"
                                   :message "the memory was deleted earlier in the batch"}))
              (if-let [eid (eid-of db user id)]
                (recur more (conj deleted id) creates (assoc fold eid {:deleted true}) errors)
                (recur more deleted creates fold
                       (conj errors {:i i :op op :error "not-found"
                                     :message "no such memory for this user"}))))))))))

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

(defn query' [db user seen unseen related xs]
  (cond
    (seq unseen)
    (loop [[[eid related'] & unseen'] unseen]
      (cond
        (nil? eid) (lazy-seq (query' db user seen [] related xs))
        (seen eid) (recur unseen')
        :else (cons (->wire (d/pull db pull-pattern eid))
                    (lazy-seq
                     (query' db user (conj seen eid) unseen'
                             (st/union related related') xs)))))

    (seq xs)
    (let [[[f x] & xs'] xs]
      (recur db user seen (f x) related xs'))

    (seq related)
    (recur db user seen unseen #{}
           (conj xs [(partial eids-by-srcs db user)
                     (disj related :none)]))))

(defn query
  "Return a lazy seq of wire memories: the pair matches for `user` plus the
  transitive related-by-src closure, deduped. Responses are not truncated."
  [conn user pairs]
  (let [db (d/db conn)
        f (partial eids-by-pair db user)]
    (query' db user #{} [] #{} (map (partial vector f) pairs))))
