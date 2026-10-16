(ns engram.short-term
  "Short-term memory storage over Datalevin. Stateless: every function takes the
  connection as its first argument. Lifecycle lives in engram.system.

  A short-term memory is a loose task note a user stages during one task, held
  under a thought-process. It carries an id, free text, a created time, and an
  updated time. It never joins recall, stats, freshness, conformance, link
  density, or consolidate.

  Reads and writes always filter by user, so one user never sees another's notes.
  A read-dependent write (update, single delete, bucket wipe) resolves the note
  inside a `:db.fn/call` transaction function, so the read and the commit are one
  atomic unit under the connection write lock and a concurrent delete leaves no
  half-written note. A create reads nothing before it writes, so it stays a plain
  transaction."
  (:require [datalevin.core :as d]
            [engram.search-config :as search-config])
  (:import [java.util UUID Date]))

(def ^:private pull-pattern
  '[:short-term/id :short-term/process :short-term/text
    :short-term/created-at :short-term/updated-at])

(defn- iso
  "A Date rendered as an ISO-8601 instant string."
  [^Date d]
  (.toString (.toInstant d)))

(defn- ->wire
  "Shape a pulled short-term note into the JSON wire form, with kebab-case keys
  and ISO-8601 timestamps."
  [m]
  {:id              (str (:short-term/id m))
   :thought-process (:short-term/process m)
   :text            (:short-term/text m)
   :created-at      (iso (:short-term/created-at m))
   :updated-at      (iso (:short-term/updated-at m))})

;; ---------- lookups ----------

(defn- eid-of [db user id-str]
  (d/q '[:find ?e .
         :in $ ?id ?u
         :where [?e :short-term/id ?id] [?e :short-term/user ?u]]
       db (UUID/fromString id-str) user))

(defn- process-eids [db user process]
  (d/q '[:find [?e ...] :in $ ?u ?p
         :where [?e :short-term/user ?u] [?e :short-term/process ?p]]
       db user process))

(defn- order-key
  "Sort a pulled note by created time, oldest first, tie-broken by id so a repeat
  returns the same order."
  [m]
  [(.getTime ^Date (:short-term/created-at m)) (str (:short-term/id m))])

;; ---------- writes ----------

(defn create!
  "Create one short-term note for `user` under `process`. Mint the id, stamp the
  created time and the updated time to now, and return the id string. A create
  reads nothing before it writes, so a plain transaction is atomic."
  [conn user process text]
  (let [id  (UUID/randomUUID)
        now (Date.)]
    (d/transact! conn [{:short-term/id         id
                        :short-term/user       user
                        :short-term/process    process
                        :short-term/text       text
                        :short-term/created-at now
                        :short-term/updated-at now}])
    (str id)))

(defn- absent! [] (throw (ex-info "no such short-term memory for this user" {::absent true})))

(defn- commit-plan!
  "Transact the tx-data `plan-fn` builds from the commit-time db, as one atomic
  unit. Return `ret`, or nil when `plan-fn` throws `::absent`."
  [conn ret plan-fn]
  (try
    (d/transact! conn [[:db.fn/call plan-fn]])
    ret
    (catch clojure.lang.ExceptionInfo e
      (if (::absent (ex-data e)) nil (throw e)))))

(defn update!
  "Replace a note's text and stamp the updated time, leaving the created time.
  The resolve and the commit run as one atomic unit, so a concurrent delete
  cannot resurrect the note. Returns the id string, or nil when the note does not
  exist for this user."
  [conn user id text]
  (commit-plan! conn id
                (fn [db]
                  (if-let [eid (eid-of db user id)]
                    [{:db/id eid :short-term/text text :short-term/updated-at (Date.)}]
                    (absent!)))))

(defn delete!
  "Delete `user`'s own note. The resolve and the commit run as one atomic unit.
  Returns the id string, or nil when the note does not exist for this user."
  [conn user id]
  (commit-plan! conn id
                (fn [db]
                  (if-let [eid (eid-of db user id)]
                    [[:db/retractEntity eid]]
                    (absent!)))))

(defn wipe!
  "Delete every note `user` holds under `process`, in one transaction. Return the
  number of notes deleted (0 when the bucket is empty). The resolve of the bucket
  and the commit run as one atomic unit, so a concurrent create into the bucket is
  never half-deleted. The deleted count comes from the transaction report: the
  transaction holds only retractions, so every distinct entity in the report is a
  deleted note."
  [conn user process]
  (let [report (d/transact!
                conn
                [[:db.fn/call
                  (fn [db]
                    (mapv (fn [eid] [:db/retractEntity eid])
                          (process-eids db user process)))]])]
    (count (into #{} (map :e) (:tx-data report)))))

;; ---------- reads ----------

(defn fetch
  "Return the caller's wire note by id, or nil when no such note exists for this
  user."
  [conn user id]
  (let [db (d/db conn)]
    (when-let [eid (eid-of db user id)]
      (->wire (d/pull db pull-pattern eid)))))

(defn buckets
  "A map of each of `user`'s thought-processes to its note count. A thought-process
  with no notes is absent, because it has no separate existence."
  [conn user]
  (into {}
        (d/q '[:find ?p (count ?e)
               :in $ ?u
               :where [?e :short-term/user ?u] [?e :short-term/process ?p]]
             (d/db conn) user)))

(defn- fulltext-doc-count
  "An upper bound on the number of full-text documents in the store: the datom
  count over every full-text attribute in `attrs`. Datalevin bounds a full-text
  query by :top against the ranked matches, and it caps the pool before the
  doc-filter runs, so :top must cover every competing document. Otherwise a
  higher-ranked foreign document pushes one of the caller's own matches out of
  the pool."
  [db attrs]
  (reduce (fn [n a] (+ n (or (d/q '[:find (count ?e) . :in $ ?a :where [?e ?a _]] db a) 0)))
          0 attrs))

(defn- match-eids
  "The eids of `user`'s short-term notes that `query` matches. `query` is a
  Datalevin search query: a term string, or a nested boolean expression (ticket
  22), already transformed from the wire. The query scopes the full-text hit to
  the :short-term/text attribute and to the caller's own notes, so a Memory's
  content and another user's note never match. The :top bound is the store's
  full-text document count, so no higher-ranked foreign document truncates one of
  the caller's own matches before the scope clauses apply. `attrs` is the set of
  full-text attributes, read from the connection's schema."
  [db attrs user query]
  (set
   (d/q '[:find [?e ...]
          :in $ ?q ?opts ?u
          :where [(fulltext $ ?q ?opts) [[?e ?a ?v ?score]]]
                 [(= ?a :short-term/text)]
                 [?e :short-term/user ?u]]
        db query {:display :refs+scores :top (max 1 (fulltext-doc-count db attrs))} user)))

(defn- fulltext-attrs [conn] (search-config/fulltext-attrs (d/schema conn)))

(defn match-count
  "The number of `user`'s short-term notes that `query` matches, across every
  thought-process. `query` is a Datalevin search query."
  [conn user query]
  (count (match-eids (d/db conn) (fulltext-attrs conn) user query)))

(defn stream-bucket
  "A lazy seq of `user`'s wire notes under `process`, ordered by created time,
  oldest first. Lazy over an immutable db snapshot, so realize it while `conn` is
  open."
  [conn user process]
  (let [db (d/db conn)]
    (->> (process-eids db user process)
         (map (fn [eid] (d/pull db pull-pattern eid)))
         (sort-by order-key)
         (map ->wire))))

(defn stream-matches
  "A lazy seq of `user`'s wire notes under `process` that `query` matches, ordered
  by created time, oldest first. `query` is a Datalevin search query. Lazy over an
  immutable db snapshot, so realize it while `conn` is open."
  [conn user process query]
  (let [db      (d/db conn)
        matches (match-eids db (fulltext-attrs conn) user query)]
    (->> (process-eids db user process)
         (filter matches)
         (map (fn [eid] (d/pull db pull-pattern eid)))
         (sort-by order-key)
         (map ->wire))))
