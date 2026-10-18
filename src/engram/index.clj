(ns engram.index
  "The per-user memory index over Datalevin (ticket 25). Stateless: every function
  takes the connection as its first argument. Lifecycle lives in engram.system.

  An index entry is one item in the memory index, which a session loads whole at
  session start. It carries an id, the owning user, a kebab-case title, a
  free-text hook, and one or more requests. A request is a stored recall body of
  one of three kinds: a by-tags match expression, a by-src list, or a
  thought-process name. Each request carries a required? flag.

  A required request is a liveness probe. An entry lapses once any one required
  request returns no memory, and `read-live!` reaps the lapsed entries. A probe
  runs in existence mode: it reuses the recall-side existence helpers
  (`memory/any-match?`, `memory/owns-any-src?`, `short-term/bucket-has-note?`), so
  the probe and the full recall can never disagree about what a request yields.

  Reads and writes always filter by user, so one user never sees another's index.
  A read-dependent write (replace, delete) and the read-time reap resolve inside a
  `:db.fn/call` transaction function, so the read and the commit are one atomic
  unit under the connection write lock and a concurrent write cannot strand a
  half-reaped entry. A create reads nothing before it writes, so it stays a plain
  transaction. The payload stores as an opaque JSON string, so an arbitrary-depth
  match tree needs no shredding into datoms."
  (:require [datalevin.core :as d]
            [engram.batch :as batch]
            [engram.memory :as memory]
            [engram.short-term :as short-term]
            [jsonista.core :as json])
  (:import [java.util UUID]))

(def ^:private pull-pattern
  '[:index/id :index/title :index/hook
    {:index/request [:index.request/kind :index.request/payload :index.request/required?]}])

;; ---------- wire shaping ----------

(defn- parse-payload
  "Parse a request's opaque JSON payload back into data, keywordizing object keys
  so a by-tags match expression reads with keyword operator keys, exactly as the
  recall compiler and the probe expect."
  [s]
  (json/read-value s json/keyword-keys-object-mapper))

(defn- ->wire
  "Shape a pulled index entry into the JSON wire form. Each request hands its
  payload back as parsed JSON, so the client reads the stored recall body as data."
  [m]
  {:id    (str (:index/id m))
   :title (:index/title m)
   :hook  (:index/hook m)
   :requests (mapv (fn [r]
                     {:kind     (:index.request/kind r)
                      :payload  (parse-payload (:index.request/payload r))
                      :required (boolean (:index.request/required? r))})
                   (:index/request m))})

;; ---------- lookups ----------

(defn- eid-of [db user id-str]
  (d/q '[:find ?e .
         :in $ ?id ?u
         :where [?e :index/id ?id] [?e :index/user ?u]]
       db (UUID/fromString id-str) user))

(defn- entry-eids [db user]
  (d/q '[:find [?e ...] :in $ ?u :where [?e :index/user ?u]] db user))

;; ---------- tx building ----------

;; A request component entity. It carries a gensym tempid, so it transacts inside
;; a `:db.fn/call` function, where a nested map without a tempid is rejected. The
;; payload serializes to an opaque JSON string here, the one place that happens.
(defn- request-tx [{:keys [kind payload required]}]
  {:db/id                   (str (gensym "req"))
   :index.request/kind      kind
   :index.request/payload   (json/write-value-as-string payload)
   :index.request/required? (boolean required)})

(defn- entry-tx
  "The tx-map for one entry owned by `user`, carrying the minted `id`. The id
  string doubles as the `:db/id` tempid, so the map transacts both in a plain
  transaction and inside a transaction function."
  [user id {:keys [title hook requests]}]
  {:db/id          (str id)
   :index/id       id
   :index/user     user
   :index/title    title
   :index/hook     hook
   :index/request  (mapv request-tx requests)})

;; ---------- writes ----------

(defn create!
  "Create one index entry for `user`. Mint the id and return it as a string. A
  create reads nothing before it writes, so a plain transaction is atomic."
  [conn user entry]
  (let [id (UUID/randomUUID)]
    (d/transact! conn [(entry-tx user id entry)])
    (str id)))

(defn- absent! [] (throw (ex-info "no such index entry for this user" {::absent true})))

(defn- commit-plan!
  "Transact the tx-data `plan-fn` builds from the commit-time db, as one atomic
  unit. Return `ret`, or nil when `plan-fn` throws `::absent`."
  [conn ret plan-fn]
  (try
    (d/transact! conn [[:db.fn/call plan-fn]])
    ret
    (catch clojure.lang.ExceptionInfo e
      (if (::absent (ex-data e)) nil (throw e)))))

(defn- replace-tx
  "Tx-data that replaces entry `eid` with `entry` in place: retract the old request
  components, then set the new title, hook, and requests. The single build path for
  a full-replace update, shared by the single route and the batch."
  [db eid {:keys [title hook requests]}]
  (let [old-reqs (d/q '[:find [?r ...] :in $ ?e :where [?e :index/request ?r]] db eid)]
    (conj (mapv (fn [r] [:db/retractEntity r]) old-reqs)
          {:db/id         eid
           :index/title   title
           :index/hook    hook
           :index/request (mapv request-tx requests)})))

(defn replace!
  "Replace the entry at `id` for `user`, keeping the id. Retract the old request
  components and set the new title, hook, and requests in place. The resolve and
  the commit run as one atomic unit, so a concurrent delete cannot resurrect the
  entry. Returns the id string, or nil when the entry does not exist for this user."
  [conn user id entry]
  (commit-plan! conn id
                (fn [db]
                  (if-let [eid (eid-of db user id)]
                    (replace-tx db eid entry)
                    (absent!)))))

(defn delete!
  "Delete `user`'s own entry. Retracting the entity also retracts its request
  components. The resolve and the commit run as one atomic unit. Returns the id
  string, or nil when the entry does not exist for this user."
  [conn user id]
  (commit-plan! conn id
                (fn [db]
                  (if-let [eid (eid-of db user id)]
                    [[:db/retractEntity eid]]
                    (absent!)))))

;; ---------- batch ----------

(defn apply-batch!
  "Apply a grouped batch of index operations for one user in one atomic
  transaction (see engram.batch). A create mints its id. An update is a full
  replace, retracting the old requests and setting the new body. A delete retracts
  the entry and its request components. Return {:ok? true :ids [...] :applied n} on
  success, or {:ok? false :errors [...]} on failure."
  [conn user batch]
  (batch/apply! conn batch
                (fn [db]
                  {:resolve   (fn [id] (eid-of db user id))
                   :create-tx (fn [id p] (entry-tx user id p))
                   :update-tx (fn [eid p] (replace-tx db eid p))
                   :delete-tx (fn [eid] [[:db/retractEntity eid]])})))

;; ---------- liveness probe and reap ----------

(defn- request-lives?
  "True when the pulled request `r` still yields at least one memory for `user`
  against `db`. Dispatch on kind, parsing the opaque JSON payload and handing it
  to the request kind's existence helper."
  [db user r]
  (let [payload (parse-payload (:index.request/payload r))]
    (case (:index.request/kind r)
      "by-tags"         (memory/any-match? db user payload)
      "by-src"          (memory/owns-any-src? db user payload)
      "thought-process" (short-term/bucket-has-note? db user payload)
      ;; The write schema (schema/IndexRequest) admits only the three kinds
      ;; above, so an unknown kind can only come from corrupted stored data. The
      ;; reap cannot probe one, so it treats it as alive: a read never removes an
      ;; entry whose liveness it cannot judge.
      true)))

(defn- lapsed?
  "True when the pulled entry `m` carries a required request that no longer yields
  a memory. An entry with no required request never lapses."
  [db user m]
  (boolean
   (some (fn [r] (and (:index.request/required? r)
                      (not (request-lives? db user r))))
         (:index/request m))))

(defn read-live!
  "Return the caller's live index entries, reaping the lapsed ones first. The
  probe of every required request and the removal of the lapsed entries run inside
  one `:db.fn/call` transaction, so they are atomic and a concurrent write cannot
  strand a half-reaped entry. The entry eids are scanned twice on purpose: once
  inside the reap to decide removals, and once against a fresh snapshot after the
  commit, because only the post-reap snapshot reflects the removals this call
  made."
  [conn user]
  (d/transact!
   conn
   [[:db.fn/call
     (fn [db]
       (into []
             (comp (filter (fn [eid] (lapsed? db user (d/pull db pull-pattern eid))))
                   (map (fn [eid] [:db/retractEntity eid])))
             (entry-eids db user)))]])
  (let [db (d/db conn)]
    (mapv (fn [eid] (->wire (d/pull db pull-pattern eid)))
          (entry-eids db user))))
