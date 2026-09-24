(ns engram.config
  "The admin-authored category config, loaded from a mounted EDN file (not
  shipped with the server). It holds the stats half-life and an array of
  acceptable configurations. A configuration is a map of category to a
  cardinality shorthand. A memory is valid if its category:label pairs satisfy
  any one configuration.

  Categories are closed to the configurations. Labels are open vocabulary but
  must be lowercase kebab-case (a fixed server rule). `src` and `related` are
  imposed by the server and never appear in a configuration, so they are not
  validated here."
  (:require [clojure.edn :as edn]))

(defn load-config
  "Read the config map {:half-life-days n :configurations [{cat card} ...]}."
  [path]
  (edn/read-string (slurp path)))

(defn- kebab?
  "True when s is a lowercase kebab-case token."
  [s]
  (boolean (and (string? s) (re-matches #"[a-z0-9]+(?:-[a-z0-9]+)*" s))))

(defn- cardinality-ok? [card cnt]
  (case card
    "1" (= cnt 1)
    "?" (<= cnt 1)
    "*" true
    "+" (pos? cnt)
    false))

(defn- configuration-matches?
  "True when the category counts satisfy this configuration: every category the
  memory uses is allowed (closed set) and every configured cardinality holds."
  [configuration cat->count]
  (and (every? #(contains? configuration %) (keys cat->count))
       (every? (fn [[c card]] (cardinality-ok? card (get cat->count c 0)))
               configuration)))

(defn validate
  "Return nil when the tags are valid, else a map describing the first failure.
  `tags` is a seq of [category label] pairs, excluding the server-imposed src
  and related."
  [config tags]
  (let [bad (seq (filter (fn [[_ l]] (not (kebab? l))) tags))
        cat->count (frequencies (map first tags))]
    (cond
      bad
      {:error "label-format"
       :message "labels must be lowercase kebab-case"
       :offending (mapv second bad)}

      (not (some #(configuration-matches? % cat->count) (:configurations config)))
      {:error "no-configuration"
       :message "the category:label set matches no acceptable configuration"}

      :else nil)))
