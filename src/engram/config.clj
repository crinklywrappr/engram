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
            [engram.errors :as errors]
            [malli.core :as m]))

;; A token is lowercase kebab-case that also reads as a Clojure keyword literal:
;; a lowercase letter, then lowercase letters or digits in hyphen-joined words.
;; The regex is anchored because malli `:re` uses `re-find`, not `re-matches`.
(def token-regex #"^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$")
(def Token [:re token-regex])
(def Pair [:tuple Token Token])                 ; a [category label] pair

;; An admin may describe each category. The :categories map is optional. Each
;; entry gives one category an optional description (<=256 chars) and an optional
;; vector of token examples. Descriptions and examples flow to the client on
;; GET /config, so it can pick labels that fit how the admin means each category.
(def Categories
  [:map-of Token
   [:map
    [:description {:optional true} [:string {:max 256}]]
    [:examples {:optional true} [:vector Token]]]])

(defn valid-token?
  "True when `s` is a lowercase kebab-case token. The user id is held to this
  shape at the request boundary, so it can never contain a space or a control
  byte."
  [s]
  (m/validate Token s))

(defn- getenv
  "Indirection over `System/getenv` so a test can stub the environment."
  [k]
  (System/getenv k))

(defn- configured-categories
  "The set of category names that appear across the configurations."
  [config]
  (into #{} (mapcat keys) (:configurations config)))

(defn validate-config
  "Return `config` when valid, else throw. The :categories map is optional. When
  present, it must match the Categories shape, and every described category must
  appear in some configuration, because describing an unused category is a
  mistake."
  [config]
  (when-let [cats (:categories config)]
    (when-not (m/validate Categories cats)
      (throw (ex-info "config :categories is malformed"
                      {:errors (m/explain Categories cats)})))
    (let [unknown (remove (configured-categories config) (keys cats))]
      (when (seq unknown)
        (throw (ex-info "config :categories names categories absent from :configurations"
                        {:unknown (vec unknown)})))))
  config)

(defn load-config
  "Load the config map {:half-life-days n :configurations [{cat card} ...]}."
  [default-path]
  (validate-config (edn/read-string (slurp (or (getenv "ENGRAM_CONFIG") default-path)))))

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
    {:code errors/no-configuration
     :message "the category:label set matches no acceptable configuration"}))

;; The two tag rules, and the one place the create-versus-update asymmetry lives.
;; A create matches even an empty tag set, so an untagged create must satisfy a
;; configuration. An update matches only when it supplies tags, because an update
;; that omits tags leaves them unchanged.

(defn create-tag-error
  "The create tag rule. Match `tags`, or an empty set when tags is nil, against
  the configurations. Return a 409 error map or nil."
  [tag-schema tags]
  (tag-error tag-schema (or tags [])))

(defn update-tag-error
  "The update tag rule. Match `tags` against the configurations only when tags is
  non-empty, else skip the check. Return a 409 error map or nil."
  [tag-schema tags]
  (when (seq tags) (tag-error tag-schema tags)))
