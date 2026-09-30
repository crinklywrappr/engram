(ns engram.system
  "Integrant components: conn → migrated → config → handler → server.

  This process IS the database. Halting the connection flushes LMDB. The
  migrated component reopens the connection after migrations because Datalevin
  builds its full-text engine from the schema present at connection-open time."
  (:require [datalevin.core :as d]
            [engram.config :as config]
            [engram.handler :as handler]
            [engram.migrations :as migrations]
            [engram.stats.writer :as stat-writer]
            [integrant.core :as ig]
            [org.httpkit.server :as hk]
            [syncopate.core :as sc]))

(defn- data-path [path] (or (System/getenv "ENGRAM_DATA_DIR") path))

(defmethod ig/init-key :engram.db/conn [_ {:keys [path]}]
  (d/get-conn (data-path path)))

(defmethod ig/halt-key! :engram.db/conn [_ conn]
  ;; may already be closed — :engram.db/migrated reopens and closes this one
  (try (d/close conn) (catch Exception _)))

(defmethod ig/init-key :engram.db/migrated [_ {:keys [conn path]}]
  (sc/migrate-all! (sc/store conn) migrations/migrations)
  (d/close conn)
  (d/get-conn (data-path path)))

(defmethod ig/halt-key! :engram.db/migrated [_ conn]
  (try (d/close conn) (catch Exception _)))

(defmethod ig/init-key :engram.config/config [_ {:keys [path]}]
  (let [c (config/load-config path)]
    (assoc c :tag-schema (config/compile-tag-schema c))))

(defmethod ig/init-key :engram.stats/writer [_ {:keys [conn config]}]
  (stat-writer/writer conn (:half-life-days config)))

(defmethod ig/halt-key! :engram.stats/writer [_ writer]
  ;; drain the pending recalls while the connection is still open
  (stat-writer/drain! writer))

(defmethod ig/init-key :engram.web/handler [_ {:keys [db config writer]}]
  (handler/app db config writer))

(defmethod ig/init-key :engram.web/server [_ {:keys [handler port]}]
  (hk/run-server handler {:port (parse-long (or (System/getenv "PORT") (str port)))
                          :legacy-return-value? false}))

(defmethod ig/halt-key! :engram.web/server [_ server]
  (hk/server-stop! server))
