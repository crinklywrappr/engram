(ns engram.test-support
  "Shared test machinery, per docs/agents/testing.md: the :once store fixture, the
  request and body helpers, and a flush watcher for async count assertions.

  A namespace that drives the store uses `(use-fixtures :once store-fixture)` and
  reads `*app*` and `*conn*`. Each test keeps to its own user, so the one shared
  store stays safe. A test that needs a pristine database calls `fresh-conn`
  itself instead."
  (:require [clojure.java.io :as io]
            [clojure.test :as test]
            [datalevin.core :as d]
            [engram.config :as config]
            [engram.handler :as handler]
            [engram.recall-count :as recall-count]
            [engram.recall-count.writer :as rc-writer]
            [engram.system :as system]
            [jsonista.core :as json])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def cfg
  (let [c (config/load-config "deploy/engram-config.example.edn")]
    (assoc c :tag-schema (config/compile-tag-schema c))))

(defn fresh-conn
  "A migrated connection on a fresh temp dir, opened as the system opens it: a
  bare open, the migrations, a re-index on the fresh directory, then a reopen with
  the search options. Use it for a test that needs a pristine database."
  []
  (let [dir (.toString (Files/createTempDirectory "engram-test" (make-array FileAttribute 0)))]
    (system/migrated-conn (d/get-conn dir) dir)))

(defn tag-writer [conn] (rc-writer/writer conn (recall-count/->TagRecallCount (:recall-half-life-days cfg))))
(defn mem-writer [conn] (rc-writer/writer conn (recall-count/->MemoryRecallCount (:recall-half-life-days cfg))))

(def create-tag-err #(config/create-tag-error (:tag-schema cfg) %))
(def update-tag-err #(config/update-tag-error (:tag-schema cfg) %))

(defn seed-recall!
  "Synchronously record one recall of each pair for `user`, for test setup. Writes
  through plan-recalls at a count of one, the same tx a single recall produces.
  This bypasses the async writer, so the count lands at once."
  [conn user half-life pairs]
  (d/transact! conn (recall-count/plan-recalls (d/db conn) user half-life
                                               (into {} (map (fn [p] [p 1])) pairs)
                                               (System/currentTimeMillis))))

(defn mem-counts
  "The caller's per-memory recall counts, keyed by src. Only a memory that carries
  the recall attributes appears, so a never-recalled memory is absent."
  [conn user]
  (into {}
        (map (fn [[src life dec last]] [src {:lifetime life :decayed dec :last-recalled last}]))
        (d/q '[:find ?src ?life ?dec ?last
               :in $ ?u
               :where [?e :memory/user ?u]
                      [?e :memory/src ?src]
                      [?e :memory/recall-lifetime ?life]
                      [?e :memory/recall-decayed ?dec]
                      [?e :memory/last-recalled ?last]]
             (d/db conn) user)))

(defn- by-id [conn id attr]
  (d/q [:find '?v '. :in '$ '?id :where ['?e :memory/id '?id] ['?e attr '?v]]
       (d/db conn) (java.util.UUID/fromString id)))

(defn updated-at   [conn id] (by-id conn id :memory/updated-at))
(defn created-at   [conn id] (by-id conn id :memory/created-at))
(defn last-confirmed [conn id] (by-id conn id :memory/last-confirmed))
(defn src-val      [conn id] (by-id conn id :memory/src))

(defn related-set [conn id]
  (set (d/q '[:find [?r ...] :in $ ?id
              :where [?e :memory/id ?id] [?e :memory/related ?r]]
            (d/db conn) (java.util.UUID/fromString id))))

(defn seed-stale!
  "Transact a memory `days-ago` old in all three timestamps for `user`, so it
  respects created <= updated <= last-confirmed and passes the created-at
  pre-filter. With `recall-decayed`, stamp a recent recall count at now."
  [conn user src days-ago recall-decayed]
  (let [t (java.util.Date. (- (System/currentTimeMillis) (* days-ago 86400000)))]
    (d/transact! conn [(cond-> {:memory/id (java.util.UUID/randomUUID)
                                :memory/user user :memory/content src :memory/src src
                                :memory/created-at t :memory/updated-at t :memory/last-confirmed t
                                :memory/tag [{:tag/category "domain" :tag/label "clojure"}]}
                         recall-decayed (assoc :memory/recall-decayed recall-decayed
                                               :memory/last-recalled (java.util.Date.)))])))

(defn st-insert!
  "Transact one short-term note directly with an explicit created time, for an
  ordering test that controls the timestamps rather than racing the wall clock."
  [conn user process text ^java.util.Date created]
  (d/transact! conn [{:short-term/id         (java.util.UUID/randomUUID)
                      :short-term/user       user
                      :short-term/process    process
                      :short-term/text       text
                      :short-term/created-at created
                      :short-term/updated-at created}]))

(def ^:dynamic *conn* nil)
(def ^:dynamic *app* nil)

(defn store-fixture
  "A :once fixture. Open one migrated store, build the app with its writers, run
  the namespace's tests, then drain the writers before it closes the connection.
  The drain-before-close order flushes while the store is still open, so no
  stat-flush error reaches the log. Bind `*conn*` and `*app*` for the tests."
  [run]
  (let [conn (fresh-conn)
        tw   (tag-writer conn)
        mw   (mem-writer conn)
        app  (handler/app conn cfg tw mw)]
    (binding [*conn* conn *app* app]
      (try (run)
           (finally (rc-writer/drain! tw) (rc-writer/drain! mw) (d/close conn))))))

(defn request
  "Call the app with a ring request. The 3-arity form uses `*app*`. Options:
  :user, :body (a value, JSON-encoded onto stdin), :accept, :query."
  ([method uri opts] (request *app* method uri opts))
  ([app method uri {:keys [user body accept query]}]
   (app (cond-> {:request-method method :uri uri
                 :headers (cond-> {}
                            user   (assoc "x-engram-user" user)
                            accept (assoc "accept" accept)
                            body   (assoc "content-type" "application/json"))}
          query (assoc :query-string query)
          body  (assoc :body (io/input-stream (.getBytes (json/write-value-as-string body) "UTF-8")))))))

(defn body-json [resp]
  (json/read-value (slurp (:body resp)) json/keyword-keys-object-mapper))

(defn short-writer
  "A recall-count writer with a one-millisecond debounce, for a count assertion.
  `strategy` is :tags or :memories. Build it on the shared `*conn*` or a fresh
  store, record through it, and observe the flush with `await-flush`."
  [conn strategy]
  (rc-writer/writer conn
                    (case strategy
                      :tags     (recall-count/->TagRecallCount (:recall-half-life-days cfg))
                      :memories (recall-count/->MemoryRecallCount (:recall-half-life-days cfg)))
                    :debounce-ms 1))

(defn await-flush
  "Run `record!`, then block until `writer` commits its next flush. Add a watch on
  `:last-flush` that delivers a promise, so the block ends when the real debounced
  flush commits, not on a drain. Return true when a flush committed inside
  `timeout-ms` (default 3000)."
  ([writer record!] (await-flush writer record! 3000))
  ([writer record! timeout-ms]
   (let [p (promise)]
     (add-watch (:last-flush writer) ::await (fn [_ _ _ v] (when v (deliver p true))))
     (try (record!)
          (deref p timeout-ms false)
          (finally (remove-watch (:last-flush writer) ::await))))))
