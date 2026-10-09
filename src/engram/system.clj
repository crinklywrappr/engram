(ns engram.system
  "Integrant components: conn → migrated → config → handler → server.

  This process IS the database. Halting the connection flushes LMDB. The
  migrated component runs the migrations, then re-indexes only when the search
  index has drifted from what engram intends, because Datalevin builds its
  full-text engine from the schema present at connection-open time, and a schema
  that newly marks an attribute full-text does not back-index the values already
  stored. The drift check reads the persisted search-opts and the full-text
  attribute set, so a steady-state boot skips the costly rebuild. The bare
  connection is opened without the search options, so its persisted opts can be
  read before the serving open overwrites them. The serving connection is always
  opened with the search options, because re-index's own connection does not
  carry the analyzer for later writes."
  (:require [datalevin.core :as d]
            [engram.config :as config]
            [engram.handler :as handler]
            [engram.migrations :as migrations]
            [engram.search-config :as search-config]
            [engram.stats :as stats]
            [engram.stats.writer :as stat-writer]
            [integrant.core :as ig]
            [org.httpkit.server :as hk]
            [syncopate.core :as sc]))

(defn- data-path [path] (or (System/getenv "ENGRAM_DATA_DIR") path))

(defn migrated-conn
  "Given a freshly opened bare `conn` and its data `path`, run the migrations,
  re-index only when the persisted search configuration or the full-text
  attribute set has drifted, then return a connection opened with the search
  options for serving. Reads the persisted opts and schema off `conn` before
  migrating, closes `conn`, and opens the serving connection at `path`. Shared by
  the migrated component and the test harness so both decide identically."
  [conn path]
  (let [persisted (:search-opts (d/opts conn))
        before    (search-config/fulltext-attrs (d/schema conn))]
    (sc/migrate-all! (sc/store conn) migrations/migrations)
    (let [after (search-config/fulltext-attrs (d/schema conn))]
      (if (search-config/reindex? persisted before after)
        (d/close (d/re-index conn {:search-opts search-config/engine-opts}))
        (d/close conn))
      (d/get-conn path {} search-config/conn-opts))))

(defmethod ig/init-key :engram.db/conn [_ {:keys [path]}]
  (d/get-conn (data-path path)))

(defmethod ig/halt-key! :engram.db/conn [_ conn]
  ;; may already be closed — :engram.db/migrated closes this one
  (try (d/close conn) (catch Exception _)))

(defmethod ig/init-key :engram.db/migrated [_ {:keys [conn path]}]
  (migrated-conn conn (data-path path)))

(defmethod ig/halt-key! :engram.db/migrated [_ conn]
  (try (d/close conn) (catch Exception _)))

(defmethod ig/init-key :engram.config/config [_ {:keys [path]}]
  (let [c (config/load-config path)]
    (assoc c :tag-schema (config/compile-tag-schema c))))

(defmethod ig/init-key :engram.stats/tag-writer [_ {:keys [conn config]}]
  (stat-writer/writer conn (stats/->TagRecallCount (:recall-half-life-days config))))

(defmethod ig/halt-key! :engram.stats/tag-writer [_ writer]
  ;; drain the pending recalls while the connection is still open
  (stat-writer/drain! writer))

(defmethod ig/init-key :engram.stats/mem-writer [_ {:keys [conn config]}]
  (stat-writer/writer conn (stats/->MemoryRecallCount (:recall-half-life-days config))))

(defmethod ig/halt-key! :engram.stats/mem-writer [_ writer]
  ;; drain the pending recalls while the connection is still open
  (stat-writer/drain! writer))

(defmethod ig/init-key :engram.web/handler [_ {:keys [db config tag-writer mem-writer]}]
  (handler/app db config tag-writer mem-writer))

(defmethod ig/init-key :engram.web/server [_ {:keys [handler port]}]
  (hk/run-server handler {:port (parse-long (or (System/getenv "PORT") (str port)))
                          :legacy-return-value? false}))

(defmethod ig/halt-key! :engram.web/server [_ server]
  (hk/server-stop! server))
