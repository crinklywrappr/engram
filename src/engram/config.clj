(ns engram.config
  "The admin-authored category config, loaded from a mounted EDN file (not
  shipped with the server). It holds the stats half-life and an array of
  acceptable configurations. A configuration is a map of category to a
  cardinality shorthand.

  Validation is malli. `compile-tag-schema` turns the configurations into a
  schema once at load time. A memory is valid when its category:label pairs,
  normalized to a category->labels map, satisfy any one configuration. Categories
  are closed to the configurations. Labels, `src`, and `related` are lowercase
  kebab-case tokens (`Token`), checked at the route boundary by request coercion."
  (:require [clojure.edn :as edn]
            [malli.core :as m]))

;; A token is lowercase kebab-case that also reads as a Clojure keyword literal:
;; a lowercase letter, then lowercase letters or digits in hyphen-joined words.
;; The regex is anchored because malli `:re` uses `re-find`, not `re-matches`.
(def token-regex #"^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$")
(def Token [:re token-regex])
(def Pair [:tuple Token Token])                 ; a [category label] pair

(defn load-config
  "Read the config map {:half-life-days n :configurations [{cat card} ...]}."
  [path]
  (edn/read-string (slurp path)))

(defn- cardinality->vector [card]
  (case card
    "1" [:vector {:min 1 :max 1} Token]
    "?" [:vector {:max 1} Token]
    "*" [:vector Token]
    "+" [:vector {:min 1} Token]))

(defn- configuration->schema
  "One closed map schema for a configuration. A category is a required key for
  cardinality `1` or `+`, and an optional key for `?` or `*`."
  [configuration]
  (into [:map {:closed true}]
        (map (fn [[category card]]
               [category (if (#{"1" "+"} card) {} {:optional true})
                (cardinality->vector card)])
             configuration)))

(defn compile-tag-schema
  "Compile the acceptable configurations into one malli schema, once at load
  time. A memory matches when it satisfies any one configuration."
  [config]
  (m/schema (into [:or] (map configuration->schema (:configurations config)))))

(defn- normalize
  "Group a memory's flat [category label] pairs into a category->[labels] map."
  [tags]
  (reduce (fn [m [c l]] (update m c (fnil conj []) l)) {} tags))

(defn tag-error
  "Return nil when `tags` satisfy the compiled schema, else a 409 error map. The
  caller adds the configurations to the body so the client can refresh."
  [tag-schema tags]
  (when-not (m/validate tag-schema (normalize tags))
    {:error "no-configuration"
     :message "the category:label set matches no acceptable configuration"}))
