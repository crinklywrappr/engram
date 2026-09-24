(ns engram.migrations
  "The migration set, loaded from resources/migrations/*.edn as syncopate
  migration specs, sorted by filename-derived id."
  (:require [syncopate.core :as sc]))

(def migrations (sc/load-resources "migrations"))
