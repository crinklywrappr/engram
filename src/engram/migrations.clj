(ns engram.migrations
  "The migration set, loaded from resources/migrations/*.edn as syncopate
  migration specs, sorted by filename-derived id. The namespace also holds the
  data-migration functions a migration names as a `:fn` step."
  (:require [datalevin.core :as d]
            [syncopate.core :as sc]))

(def migrations (sc/load-resources "migrations"))

(defn copy-stat->recall
  "Data-migration step for 006. Copy every `:stat/*` recall-count row onto the
  same entity's `:recall/*` attributes, so the new identity tuple rebuilds in
  place. Runs after the `:recall/*` attributes exist and before the `:stat/*`
  attributes are removed. A fresh store carries no rows, so it is a no-op there.
  syncopate resolves and calls this with the connection. The read runs inside the
  `:db.fn/call` function, so it sees the committed db atomically with the write."
  [conn]
  (d/transact!
   conn
   [[:db.fn/call
     (fn [db]
       (mapv (fn [[s u c l life dec last]]
               {:db/id s
                :recall/user u :recall/category c :recall/label l
                :recall/lifetime life :recall/decayed dec
                :recall/last-recalled last})
             (d/q '[:find ?s ?u ?c ?l ?life ?dec ?last
                    :where [?s :stat/user ?u]
                           [?s :stat/category ?c]
                           [?s :stat/label ?l]
                           [?s :stat/lifetime ?life]
                           [?s :stat/decayed ?dec]
                           [?s :stat/last-request ?last]]
                  db)))]]))
