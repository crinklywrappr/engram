(ns engram.config
  "The admin-authored category config, loaded from a mounted EDN file (not
  shipped with the server). It holds two half-lives and an array of acceptable
  configurations. The recall half-life shapes the recent recall count. The
  freshness half-life shapes a memory's freshness. A configuration is a map of
  category to a cardinality shorthand.

  Validation is malli. `compile-tag-schema` turns the configurations into a
  schema once at load time. A memory is valid when its category:label pairs,
  normalized to a category->labels map, satisfy any one configuration. Categories
  are closed to the configurations. Labels, `src`, and `related` are lowercase
  kebab-case tokens (`Token`), checked at the route boundary by request coercion."
  (:require [clojure.edn :as edn]
            [engram.errors :as errors]
            [malli.core :as m]
            [malli.error :as me]))

;; A token is lowercase kebab-case that also reads as a Clojure keyword literal:
;; a lowercase letter, then lowercase letters or digits in hyphen-joined words.
;; The regex is anchored because malli `:re` uses `re-find`, not `re-matches`.
(def token-regex #"^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$")
(def Token [:re {:error/message "should be a lowercase kebab-case token"} token-regex])
(def Pair [:tuple Token Token])                 ; a [category label] pair

;; The half-life defaults, merged into every loaded config so each reader sees a
;; complete map. The recall half-life shapes the recent recall count on tags and
;; memories. The freshness half-life shapes a memory's freshness band.
(def default-recall-half-life-days 14)
(def default-freshness-half-life-days 30)
(def ^:private half-life-defaults
  {:recall-half-life-days    default-recall-half-life-days
   :freshness-half-life-days default-freshness-half-life-days})

;; A configuration maps each category to a cardinality shorthand, or to a
;; value-set map {:cardinality shorthand :one-of [label ...]} that closes the
;; category's labels to the :one-of set. `card-of` reads the shorthand from either.
(defn- card-of
  "The cardinality shorthand of a category value: the value itself when it is a
  shorthand string, or its :cardinality when it is a value-set map."
  [v]
  (if (map? v) (:cardinality v) v))

;; An admin may describe each category. The :categories map is optional. Each
;; entry gives one category an optional description (<=256 chars) and an optional
;; vector of token examples. Descriptions and examples flow to the client on
;; GET /config, so it can pick labels that fit how the admin means each category.
(def Categories
  [:map-of Token
   [:map
    [:description {:optional true} [:string {:max 256}]]
    [:examples {:optional true} [:vector Token]]]])

;; The shape of an admin configuration value, checked at load. A value is a
;; cardinality shorthand, or a closed map with that shorthand and an optional
;; non-empty :one-of vector of label tokens. A closed map rejects a stray key.
;; `:multi` dispatches on the value's type, so a malformed map reports the map's
;; own error rather than collapsing to the shorthand branch.
(def Cardinality [:enum "1" "?" "*" "+"])
(def CategorySpec
  [:multi {:dispatch (fn [v] (if (map? v) :map :shorthand))}
   [:shorthand Cardinality]
   [:map [:map {:closed true}
          [:cardinality Cardinality]
          [:one-of {:optional true} [:vector {:min 1} Token]]]]])

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

(defn- validate-half-life
  "Throw when `config` names `k` as anything but a positive number. An absent
  half-life is valid, because `load-config` supplies the default."
  [config k]
  (when-let [v (get config k)]
    (when-not (and (number? v) (pos? v))
      (throw (ex-info (str "config " k " must be a positive number")
                      {:key k :value v})))))

(defn validate-config
  "Return `config` when valid, else throw. The :configurations must match a vector
  of category-to-`CategorySpec` maps, so each category value is a cardinality
  shorthand or a value-set map. Each half-life, when named, must be a positive
  number. The :categories map is optional. When present, it must match the
  Categories shape, and every described category must appear in some
  configuration, because describing an unused category is a mistake."
  [config]
  (validate-half-life config :recall-half-life-days)
  (validate-half-life config :freshness-half-life-days)
  (let [schema [:vector [:map-of :string CategorySpec]]]
    (when-not (m/validate schema (:configurations config))
      (throw (ex-info "config :configurations is malformed"
                      {:errors (me/humanize (m/explain schema (:configurations config)))}))))
  (when-let [cats (:categories config)]
    (when-not (m/validate Categories cats)
      (throw (ex-info "config :categories is malformed"
                      {:errors (me/humanize (m/explain Categories cats))})))
    (let [unknown (remove (configured-categories config) (keys cats))]
      (when (seq unknown)
        (throw (ex-info "config :categories names categories absent from :configurations"
                        {:unknown (vec unknown)})))))
  config)

(defn load-config
  "Load the config map and merge the half-life defaults, so every reader sees a
  complete {:recall-half-life-days n :freshness-half-life-days n :configurations
  [{cat card} ...]} map. A half-life named in the file overrides its default."
  [default-path]
  (let [raw (validate-config (edn/read-string (slurp (or (getenv "ENGRAM_CONFIG") default-path))))]
    (merge half-life-defaults raw)))

(defn- element-of
  "The vector element schema of a category value: an enum of the :one-of labels
  when the map carries them, else any Token."
  [v]
  (if-let [one-of (and (map? v) (:one-of v))]
    (into [:enum] one-of)
    Token))

(defn- card->vector [card element]
  (case card
    "1" [:vector {:min 1 :max 1} element]
    "?" [:vector {:max 1} element]
    "*" [:vector element]
    "+" [:vector {:min 1} element]))

(defn- configuration->schema
  "One closed map schema for a configuration. A category value is a cardinality
  shorthand or a value-set map. A category is a required key for cardinality `1`
  or `+`, and an optional key for `?` or `*`. A :one-of map limits the category's
  labels to an enum of its set."
  [configuration]
  (into [:map {:closed true}]
        (map (fn [[category v]]
               (let [card (card-of v)]
                 [category (if (#{"1" "+"} card) {} {:optional true})
                  (card->vector card (element-of v))]))
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
