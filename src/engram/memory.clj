(ns engram.memory
  "Memory storage over Datalevin. Stateless: every function takes the connection
  as its first argument. Lifecycle lives in engram.system.

  A memory is one atomic fact owned by a user. Reads and writes always filter by
  user, so one user never sees another's memories. A recall matches memories by
  category:label pairs, then walks the related-by-src graph transitively to pull
  in linked memories the pairs did not name."
  (:require [datalevin.core :as d]
            [clojure.set :as st]
            [engram.decay :as decay]
            [engram.errors :as errors]
            [engram.freshness :as freshness])
  (:import [java.util UUID Date]))

(def ^:private pull-pattern
  '[:memory/id :memory/content :memory/src :memory/related
    :memory/last-confirmed :memory/updated-at :memory/created-at
    {:memory/tag [:tag/category :tag/label]}])

(defn- tag-tuples
  "Reshape a pulled memory's component tags into [category label] wire tuples."
  [m]
  (mapv (fn [t] [(:tag/category t) (:tag/label t)]) (:memory/tag m)))

(defn age-ms
  "Milliseconds from a pulled memory's effective last-confirmed to `clock`. The
  effective last-confirmed resolves through last-confirmed, then updated-at, then
  created-at for a memory that predates the field. The one shared home, so the
  wire band, the stats aggregates, and the stale route resolve age identically."
  [m clock]
  (- clock (.getTime ^Date (or (:memory/last-confirmed m)
                               (:memory/updated-at m)
                               (:memory/created-at m)))))

(defn- band-for
  "A function from a pulled memory to its freshness band, at `half-life` days
  against one clock captured now."
  [half-life]
  (let [clock (System/currentTimeMillis)]
    (fn [m] (freshness/band half-life (age-ms m clock)))))

(defn- ->wire
  "Shape a pulled memory into the JSON wire form, with its freshness band. Omit
  an empty tags vector and an empty related vector, so a bare memory carries no
  empty key on the recall wire. The recall path carries no timestamps, only the
  derived band."
  [band m]
  (let [related (vec (:memory/related m))
        tags    (tag-tuples m)]
    (cond-> {:id        (str (:memory/id m))
             :content   (:memory/content m)
             :src       (:memory/src m)
             :freshness (band m)}
      (seq tags)    (assoc :tags tags)
      (seq related) (assoc :related related))))

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
                 :memory/updated-at now
                 :memory/last-confirmed now}
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
  "Tx-data that sets `eid` to the merged `fields`, replacing src, tags, and
  related in place. Only a field present in `fields` is touched. The single
  build path for an in-place correction."
  [db eid fields]
  (let [old-tags (when (contains? fields :tags)
                   (d/q '[:find [?t ...] :in $ ?e :where [?e :memory/tag ?t]] db eid))
        old-rel  (when (contains? fields :related)
                   (d/q '[:find [?r ...] :in $ ?e :where [?e :memory/related ?r]] db eid))
        retracts (concat (map (fn [t] [:db/retractEntity t]) old-tags)
                         (map (fn [r] [:db/retract eid :memory/related r]) old-rel))
        now  (Date.)
        ;; An edit reconfirms the fact, so updated-at and last-confirmed move
        ;; together. An empty fields set is a bare confirm: it stamps
        ;; last-confirmed alone and leaves updated-at, keeping
        ;; created-at <= updated-at <= last-confirmed.
        base (cond-> {:db/id eid :memory/last-confirmed now}
               (seq fields)                (assoc :memory/updated-at now)
               (contains? fields :content) (assoc :memory/content (:content fields))
               (contains? fields :src)     (assoc :memory/src (:src fields))
               (contains? fields :tags)    (assoc :memory/tag (mapv tag-tx (:tags fields)))
               (contains? fields :related) (assoc :memory/related (vec (:related fields))))]
    (concat retracts [base])))

(defn- ->fields
  "Reduce a supplied {:content :src :tags :related} payload to the fields to
  touch: content and src when present, tags and related only when non-empty."
  [{:keys [content src tags related]}]
  (cond-> {}
    (some? content) (assoc :content content)
    (some? src)     (assoc :src src)
    (seq tags)      (assoc :tags tags)
    (seq related)   (assoc :related related)))

;; ---------- related repair (keep the related graph whole after a write) ----------

(defn- src-of [db eid] (:memory/src (d/pull db [:memory/src] eid)))

(defn- memories-with-src
  "Eids of `user`'s memories that carry `src`."
  [db user src]
  (d/q '[:find [?e ...] :in $ ?u ?s
         :where [?e :memory/user ?u] [?e :memory/src ?s]]
       db user src))

(defn- related-referrers
  "Eids of `user`'s memories whose `related` names `src`."
  [db user src]
  (d/q '[:find [?e ...] :in $ ?u ?s
         :where [?e :memory/user ?u] [?e :memory/related ?s]]
       db user src))

(defn- single-src? [db user src]
  (== 1 (count (memories-with-src db user src))))

(defn- plan-repair-for-batch
  "Related repair datoms for a batch over `db`, scoped to `user`. Only a src the
  batch empties is repaired: every memory whose related names it loses it and gains
  the srcs its memories moved to, none for a plain delete. A src the batch leaves
  populated needs no repair, so a swap or a cycle makes no edit. The datoms commit
  in the same transaction as the batch, so a reader never sees a dangling related.

  - `deletes`      memories the batch removes e.g. `[{:eid e :src \"a\"} ...]`.
  - `renames`      src moves the batch makes e.g. `[{:old \"a\" :new \"b\"} ...]`.
  - `created-srcs` srcs the batch adds e.g. `[\"a\" \"b\"]`.
  - `exempt`       eids the repair leaves to the batch, because it rewrites their
                   related itself, `#{e1 e2}`."
  [db user {:keys [deletes renames created-srcs exempt]
            :or {deletes [] renames [] created-srcs [] exempt #{}}}]
  (let [touched (into #{} (concat (map :src deletes) (map :old renames)))

        ;; Net population change per src. A memory scores -1 at the src it leaves
        ;; and +1 at the src it enters: a delete leaves to nowhere, a create enters
        ;; from nowhere, a rename does both. So pre-count + delta = post-count.
        delta (as-> {} $
                (reduce
                 (fn [m {:keys [src]}]
                   (update m src (fnil dec 0)))
                 $ deletes)
                (reduce
                 (fn [m {:keys [old new]}]
                   (-> m (update old (fnil dec 0))
                       (update new (fnil inc 0))))
                 $ renames)
                (reduce
                 (fn [m s]
                   (update m s (fnil inc 0)))
                 $ created-srcs))

        ;; empty after the batch: the pre-count plus the net change is zero
        dead? (fn [src]
                (zero? (+ (count (memories-with-src db user src))
                          (delta src 0))))

        dead  (filter dead? touched)

        dests (reduce
               (fn [m {:keys [old new]}]
                 (update m old (fnil conj #{}) new))
               {} renames)

        skip  (into exempt (map :eid deletes))]
    (for [old dead
          ref (remove skip (related-referrers db user old))
          datom (cons [:db/retract ref :memory/related old]
                      (for [n (dests old)]
                        [:db/add ref :memory/related n]))]
      datom)))

(defn- batch-src-moves
  "Turn a planned batch into the src moves `plan-repair-for-batch` reads: the
  deletes with their srcs, the net renames, the created srcs, and the eids whose
  related the batch rewrites. `fold` is the merged updates (eid -> fields),
  `delete-eids` the removed eids, `creates` the create payloads."
  [db fold delete-eids creates]
  (letfn [(make-delete [eid] {:eid eid :src (src-of db eid)})
          (make-rename [[eid fields]]
            (when (contains? fields :src)
              (let [o (src-of db eid)]
                (when (not= (:src fields) o)
                  {:old o :new (:src fields)}))))
          (make-exempt [[eid fields]]
            (when (contains? fields :related) eid))]
    (let [renames (keep make-rename fold)
          exempt  (keep make-exempt fold)]
      (cond-> {}
        (seq delete-eids) (assoc :deletes (map make-delete delete-eids))
        (seq renames) (assoc :renames renames)
        (seq creates) (assoc :created-srcs (map :src creates))
        (seq exempt) (assoc :exempt (into #{} exempt))))))

(defn- plan-repair-for-delete
  "retract dangling related values"
  [db user deleted-eid deleted-src]
  (when (single-src? db user deleted-src)
    (for [ref (remove #{deleted-eid} (related-referrers db user deleted-src))]
      [:db/retract ref :memory/related deleted-src])))

(defn- plan-repair-for-update
  "related memories follow a memory when renamed, only
   when the `src` is not shared with other memories."
  [db user eid fields]
  (let [old-src (src-of db eid)
        new-src (:src fields)]
    (when (and new-src (not= new-src old-src) (single-src? db user old-src))
      ;; skip this memory when its own related is being rewritten, so the repair
      ;; does not add the new src on top of the related the caller just set.
      (let [skip-self (if (contains? fields :related) #{eid} #{})
            replace-fn (fn [ref] [[:db/retract ref :memory/related old-src]
                                 [:db/add ref :memory/related new-src]])]
        (->> (related-referrers db user old-src)
             (remove skip-self)
             (mapcat replace-fn))))))

(defn update!
  "Correct a fact in place. Replaces content, src, tags, and related when
  supplied. A src change repairs the related space in the same transaction, so no
  other memory is left pointing at a src that no longer has a memory. An empty
  payload is a bare confirm: it stamps last-confirmed alone and leaves updated-at,
  the same effect as the confirm route. Returns the id string, or nil when the
  memory does not exist for this user."
  [conn user id payload]
  (let [db  (d/db conn)
        eid (eid-of db user id)]
    (when eid
      (let [fields (->fields payload)
            tx     (into (vec (update-tx db eid fields))
                         (plan-repair-for-update db user eid fields))]
        (d/transact! conn tx)
        id))))

(defn delete!
  "Delete `user`'s own memory. Retracting the entity also retracts its component
  tags. The delete repairs the related space in the same transaction, so no other
  memory is left pointing at a src the delete emptied. Returns the id string, or
  nil when the memory does not exist for this user."
  [conn user id]
  (let [db  (d/db conn)
        eid (eid-of db user id)]
    (when eid
      (let [tx (into [[:db/retractEntity eid]]
                     (plan-repair-for-delete db user eid (src-of db eid)))]
        (d/transact! conn tx)
        id))))

(defn confirm!
  "Stamp last-confirmed to now for each of `ids` the caller owns, in one
  transaction. Drop an id the caller does not own and an id that does not exist.
  Collapse a repeated id to one stamp. Return the number of memories stamped.
  Never touch updated-at, because a confirm affirms the fact without editing it.
  `ids` are uuid strings."
  [conn user ids]
  (let [db   (d/db conn)
        now  (Date.)
        eids (distinct (keep #(eid-of db user %) ids))
        tx   (mapv (fn [eid] {:db/id eid :memory/last-confirmed now}) eids)]
    (when (seq tx) (d/transact! conn tx))
    (count eids)))

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
  and :delete lists. `create-tag-error` and `update-tag-error` each map a tag
  vector to an error map or nil, carrying the create and the update tag rule.

  Pre-validate against `db`: a create or an update is checked against its tag
  rule, an update or delete is resolved to one of the user's own memories, and an
  id must not appear in both the update and the delete group. Within a group,
  several updates to one id fold cumulatively (a later entry wins a same-field
  tie) and a repeated delete id is deduplicated. If all pass, return
  {:ok? true :ids [create-ids...] :tx-data [...] :applied n}; else return
  {:ok? false :errors [{:op :i :code ...} ...]} for the failing ops only. The
  index :i is 0-based within the op's own group list."
  [db user batch create-tag-error update-tag-error]
  (let [creates (vec (:create batch))
        updates (vec (:update batch))
        deletes (vec (:delete batch))
        conflicts (st/intersection (set (map :id updates)) (set deletes))
        create-errs
        (keep-indexed
         (fn [i p] (when-let [e (create-tag-error (:tags p))]
                     (merge {:op "create" :i i} e)))
         creates)
        update-results
        (map-indexed
         (fn [i p]
           (let [id (:id p)]
             (cond
               (conflicts id) {:error {:op "update" :i i :code errors/conflict :message conflict-msg}}
               :else (if-let [eid (eid-of db user id)]
                       (if-let [e (update-tag-error (:tags p))]
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
            fold           (reduce
                            (fn [m {:keys [eid payload]}]
                              (update m eid merge-update-fields payload))
                            {} update-results)
            update-tx-data (mapcat (fn [[eid fields]] (update-tx db eid fields)) fold)
            delete-eids    (distinct (map :eid delete-results))
            delete-tx-data (map (fn [eid] [:db/retractEntity eid]) delete-eids)
            repair-tx-data (->> (batch-src-moves db fold delete-eids creates)
                                (plan-repair-for-batch db user))
            tx-data        (vec (concat create-tx-data update-tx-data delete-tx-data repair-tx-data))]
        {:ok? true :ids ids :tx-data tx-data
         :applied (+ (count ids) (count fold) (count delete-eids))}))))

(defn apply-batch!
  "Apply a grouped batch for one user in one atomic transaction. Delegate the
  validation and tx-data planning to `plan-batch` over the current db value, then
  transact once on success. Return {:ok? true :ids [...] :applied n} on success,
  or {:ok? false :errors [...]} on failure, writing nothing on failure."
  [conn user batch create-tag-error update-tag-error]
  (let [result (plan-batch (d/db conn) user batch create-tag-error update-tag-error)]
    (if (:ok? result)
      (do (when (seq (:tx-data result)) (d/transact! conn (:tx-data result)))
          {:ok? true :ids (:ids result) :applied (:applied result)})
      result)))

;; ---------- fetch one ----------

(defn fetch
  "Return the caller's wire memory by id, or nil when no such memory exists for
  this user. The read before a correction or a delete."
  [conn user id half-life]
  (let [db   (d/db conn)
        band (band-for half-life)]
    (when-let [eid (eid-of db user id)]
      (->wire band (d/pull db pull-pattern eid)))))

;; ---------- recall ----------

;; Datalog rule: bind ?r to each related src of ?e, or to :none when ?e has none.
;; :none is the no-related sentinel, never a real src. The three recall lookups
;; share it, so the related-closure fragment lives in one place.
(def ^:private related-rules
  '[[(related-or-none ?e ?r)
     [?e :memory/related ?r]]
    [(related-or-none ?e ?r)
     (not-join [?e] [?e :memory/related _])
     [(ground :none) ?r]]])

(defn- eids-by-tag [db user [category label]]
  (d/q '[:find ?e (distinct ?r)
         :in $ % ?u ?c ?l
         :where [?e :memory/user ?u]
                [?e :memory/tag ?t]
                [?t :tag/category ?c]
                [?t :tag/label ?l]
                (related-or-none ?e ?r)]
       db related-rules user category label))

(defn- eids-by-srcs [db user srcs]
  (if (seq srcs)
    (d/q '[:find ?e (distinct ?r)
           :in $ % ?u [?s ...]
           :where [?e :memory/user ?u]
                  [?e :memory/src ?s]
                  (related-or-none ?e ?r)]
         db related-rules user (vec srcs))
    []))

(defn- query
  "Walk the transitive related-by-src closure, emitting one wire memory per
  entity as a lazy sequence, deduplicated by `seen`. `band` is the wire builder's
  freshness function, or nil for no band. `pending` is a queue of matched rows
  awaiting emission, each an `[eid srcs]` pair. `frontier` is the set of related
  srcs still to expand. `lookup+args` is a sequence of `[lookup arg]` pairs, each
  a deferred lookup that yields more rows when applied. The emitted values are
  pulled from `db`, an immutable snapshot, so they stay valid only while the
  connection that produced it is open."
  [db user band seen pending frontier lookup+args]
  (cond
    (seq pending)
    (loop [[[eid srcs] & more] pending]
      (cond
        (nil? eid) (lazy-seq (query db user band seen [] frontier lookup+args))
        (seen eid) (recur more)
        :else (cons (->wire band (d/pull db pull-pattern eid))
                    (lazy-seq
                     (query db user band (conj seen eid) more
                             (st/union frontier srcs) lookup+args)))))

    (seq lookup+args)
    (let [[[lookup arg] & more] lookup+args]
      (recur db user band seen (lookup arg) frontier more))

    (seq frontier)
    (recur db user band seen pending #{}
           (conj lookup+args [(partial eids-by-srcs db user)
                              ;; :none is the no-related sentinel, never a real src
                              (disj frontier :none)]))))

(defn recall-by-tags
  "Return a lazy seq of wire memories: the tag matches for `user` plus the
  transitive related-by-src closure, deduped. With a `half-life`, each wire
  memory carries its freshness band; without one, the bare wire. Responses are
  not truncated. The seq is lazy over an immutable db snapshot, so realize it
  while `conn` is open."
  [conn user tags half-life]
  (let [db   (d/db conn)
        band (band-for half-life)
        f    (partial eids-by-tag db user)]
    (query db user band #{} [] #{} (map (partial vector f) tags))))

(defn- eids-by-ids [db user ids]
  (if (seq ids)
    (d/q '[:find ?e (distinct ?r)
           :in $ % ?u [?id ...]
           :where [?e :memory/id ?id]
                  [?e :memory/user ?u]
                  (related-or-none ?e ?r)]
         db related-rules user (vec ids))
    []))

(defn recall-by-ids
  "Return a lazy seq of wire memories: the memories `user` owns among `ids` plus
  the transitive related-by-src closure, deduped. An id that is not the caller's
  or does not exist is skipped, because the match joins on the caller's user. A
  duplicate id collapses to one memory, the dedup the closure walk performs. An
  empty `ids` selects nothing. `ids` are uuid strings. Responses are not
  truncated. Each wire memory carries its freshness band. The seq is lazy over
  an immutable db snapshot, so realize it while `conn` is open."
  [conn user ids half-life]
  (let [db    (d/db conn)
        band  (band-for half-life)
        uuids (mapv #(UUID/fromString %) ids)
        f     (partial eids-by-ids db user)]
    (query db user band #{} [] #{} [[f uuids]])))

;; ---------- list all ----------

(defn- all-eids [db user]
  (d/q '[:find [?e ...]
         :in $ ?u
         :where [?e :memory/user ?u]]
       db user))

(defn all-memories
  "Return a lazy seq of every wire memory `user` owns, in no set order, each
  carrying its freshness band. The eids come back up front as cheap longs. Each
  memory map is pulled lazily, so the full set never sits in memory at once.
  Responses are not truncated. The seq is lazy over an immutable db snapshot, so
  realize it while `conn` is open."
  [conn user half-life]
  (let [db   (d/db conn)
        band (band-for half-life)]
    (map (fn [eid] (->wire band (d/pull db pull-pattern eid)))
         (all-eids db user))))

;; ---------- recall recency and the stale route ----------

(defn recent-count
  "A pulled memory's recent recall count projected to `now`: the stored decayed
  weight decayed by the recall half-life since its last recall, or 0.0 when the
  memory was never recalled. The stale route ranks by it, and the stats
  aggregates read it for the use-weighted mean and the hot test."
  [recall-half-life-days m now]
  (if-let [^Date last (:memory/last-recalled m)]
    (decay/project recall-half-life-days (:memory/recall-decayed m) last now)
    0.0))

(defn- priority-row
  "The confirm-priority sort row for the pulled memory `m` at `now`, or nil when
  `m` is not stale. The row is {:eid :src :id :priority}, where the priority is
  the staleness (one minus freshness) times the recent recall count."
  [freshness-half-life-days recall-half-life-days m now]
  (let [age (age-ms m now)]
    (when (freshness/stale? freshness-half-life-days age)
      (let [staleness (- 1.0 (freshness/value freshness-half-life-days age))
            recent    (recent-count recall-half-life-days m now)]
        {:eid (:db/id m) :src (:memory/src m) :id (:memory/id m)
         :priority (* staleness recent)}))))

(defn- stale-candidate-rows
  "Pull the cheap sort columns for the caller's memories that could be stale: the
  eid, id, src, the freshness timestamps, and the recall columns. The created-at
  pre-filter keeps only memories old enough to possibly be stale. It is a harmless
  superset, because created-at is the floor of the effective last-confirmed, so no
  stale memory is dropped."
  [db user ^Date cutoff]
  (d/q '[:find [(pull ?e [:db/id :memory/id :memory/src
                          :memory/last-confirmed :memory/updated-at :memory/created-at
                          :memory/recall-decayed :memory/last-recalled]) ...]
         :in $ ?u ?cutoff
         :where [?e :memory/user ?u]
                [?e :memory/created-at ?c]
                [(<= ?c ?cutoff)]]
       db user cutoff))

(defn stale
  "Return the caller's stale memories as wire memories, ordered by confirm-priority
  highest first. The confirm-priority is the staleness (one minus freshness) times
  the recent recall count. A never-loaded stale memory scores zero and sorts last.
  A tie breaks by src then id, so repeated calls return the same order. Phase one
  reads only the cheap sort columns, pre-filtered by created-at. Phase two maps
  each sorted row to its full wire memory, pulled lazily from the one snapshot.
  The result is a lazy seq over that snapshot, so realize it while `conn` is open."
  [conn user freshness-half-life-days recall-half-life-days]
  (let [db     (d/db conn)
        band   (band-for freshness-half-life-days)
        now    (System/currentTimeMillis)
        cutoff (Date. (- now (freshness/stale-age-ms freshness-half-life-days)))]
    (->> (stale-candidate-rows db user cutoff)
         (keep #(priority-row freshness-half-life-days recall-half-life-days % now))
         (sort-by (juxt (comp - :priority) :src :id))
         (map #(->wire band (d/pull db pull-pattern (:eid %)))))))

;; ---------- conformance ----------

(defn nonconforming
  "Return a lazy seq of the caller's wire memories that `reject?` rejects.
  `reject?` takes a memory's tags (a vector of [category label] pairs, empty when
  the memory carries none) and returns a truthy value when the memory fails
  conformance. The caller injects the live configuration check, so this namespace
  holds no configuration dependency. Each wire memory carries its freshness band.
  Lazy over the all-memories snapshot, so realize it while `conn` is open."
  [conn user reject? half-life]
  (filter #(reject? (:tags % [])) (all-memories conn user half-life)))

;; ---------- search ----------

(defn- ->search-row
  "Shape a pulled memory and its relevance score into a search result row.
  Unlike the recall wire, a search row carries a :score and never carries
  related, because search follows no links."
  [m score]
  (let [tags (tag-tuples m)]
    (cond-> {:id      (str (:memory/id m))
             :content (:memory/content m)
             :src     (:memory/src m)
             :score   score}
      (seq tags) (assoc :tags tags))))

(defn- fulltext-scores
  "Run the ranked full-text query over every :db/fulltext attribute, returning
  `[eid score]` rows. `top` bounds the candidate count. `keep?` is the
  doc-filter: it takes a doc-ref `[eid attr value]` and keeps the candidate when
  truthy, used to scope the scan to the caller's own memories. A memory can match
  in more than one attribute, so an eid can repeat across the rows."
  [db query top keep?]
  (d/q '[:find ?e ?score
         :in $ ?q ?opts
         :where [(fulltext $ ?q ?opts) [[?e ?a ?v ?score]]]]
       db query {:display    :refs+scores
                 :top        top
                 :doc-filter keep?}))

(defn search
  "Return up to `limit` of `user`'s memories that best match the full-text
  `query`, ranked by relevance, each carrying a :score. One ranked query covers
  content and src. A doc-filter restricts the scan to the caller's own memories,
  so one user never sees another's and the fulltext :top is spent on the caller's
  hits. A memory that matches in both fields returns two rows, so the rows reduce
  to one score per entity, keeping the higher. Search follows no related links.
  The result is a vector bounded by `limit`."
  [conn user query limit]
  (let [db   (d/db conn)
        mine (set (all-eids db user))
        ;; over-fetch: a memory can match in both content and src, and the dedup
        ;; below collapses that to one row, so fetch extra.
        rows (fulltext-scores db query (* 3 limit)
                              (fn [doc-ref] (contains? mine (first doc-ref))))
        best (reduce (fn [m [e s]] (update m e (fnil max 0.0) s)) {} rows)]
    (->> best
         (sort-by val >)
         (take limit)
         (mapv (fn [[e s]] (->search-row (d/pull db pull-pattern e) s))))))
