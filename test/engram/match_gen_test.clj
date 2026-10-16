(ns engram.match-gen-test
  "Generative test for the by-tags match compiler. It drives the full `app`, so
  it is also an integration test: coercion, muuntaja, the route, the compiled
  datalog query, and the related-closure walk all run. One store of 1000 memories
  over a 100-pair vocabulary is built once. Each trial generates a nested
  and/or/not expression, recalls it through the server, and asserts the returned
  srcs equal an independent predicate walk over the store. A second user's
  memories are present and must never appear, so every trial also proves
  isolation."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datalevin.core :as d]
            [engram.config :as config]
            [engram.expr :as expr]
            [engram.handler :as handler]
            [engram.recall-count :as recall-count]
            [engram.recall-count.writer :as rc-writer]
            [engram.system :as system]
            [jsonista.core :as json]
            [taoensso.telemere :as t])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util Date Random UUID]))

;; ---------- the vocabulary: 100 sensible category:label pairs ----------

(def ^:private vocab-by-category
  {"domain"   ["clojure" "datalog" "databases" "distributed-systems" "security"
               "networking" "cryptography" "compilers" "graphics" "machine-learning"
               "web" "mobile" "embedded" "finance" "healthcare" "logistics"
               "gaming" "audio" "robotics" "bioinformatics"]
   "tech"     ["datalevin" "reitit" "malli" "integrant" "http-kit" "jsonista"
               "telemere" "lmdb" "postgres" "sqlite" "redis" "kafka" "docker"
               "kubernetes" "terraform" "graalvm" "babashka" "jackson" "netty" "jetty"]
   "scope"    ["global" "project" "team" "personal" "shared"]
   "activity" ["design" "implement" "review" "debug" "refactor" "test" "deploy"
               "document" "profile" "migrate" "benchmark" "audit" "research"
               "plan" "triage" "release" "monitor" "backup" "restore" "configure"]
   "status"   ["active" "blocked" "done" "deprecated" "experimental" "stable"
               "draft" "superseded" "pending" "archived" "flaky" "hot"
               "cold" "verified" "unverified"]})

(def ^:private vocab
  "The flat vector of [category label] pairs, 100 in all."
  (vec (for [[c labels] vocab-by-category, l labels] [c l])))

(def ^:private gen-user "gen-user")
(def ^:private other-user "other-user")

;; ---------- the store, built once from a seeded model ----------

(defn- mem-tx
  "A plain memory tx-map for `user` carrying `src` and the `pairs` set. Nested
  tag maps get auto-assigned tempids in a normal transaction, so no :db/id is
  needed here."
  [user {:keys [src pairs]}]
  (let [now (Date.)]
    {:memory/id (UUID/randomUUID)
     :memory/user user :memory/content src :memory/src src
     :memory/created-at now :memory/updated-at now :memory/last-confirmed now
     :memory/tag (mapv (fn [[c l]] {:tag/category c :tag/label l}) pairs)}))

(defn- build-model
  "A seeded, reproducible store model: 1000 memories, each 1-to-6 random pairs
  from the vocabulary. The pairs come from a shared 100-pair set, so overlap is
  heavy and and/or/not all bite. Each memory carries a unique src."
  []
  (let [rng (Random. 42)
        pick (fn [] (nth vocab (.nextInt rng (count vocab))))]
    (vec (for [i (range 1000)]
           {:src (str "s" i)
            :pairs (into #{} (repeatedly (inc (.nextInt rng 6)) pick))}))))

(def ^:private ctx (atom nil))

(defn- store-fixture
  "Build the migrated store once, transact the model under gen-user and a smaller
  set under other-user, stand up the app, and tear it down after the spec."
  [run]
  (let [dir   (.toString (Files/createTempDirectory "engram-gen" (make-array FileAttribute 0)))
        conn  (system/migrated-conn (d/get-conn dir) dir)
        cfg   (let [c (config/load-config "deploy/engram-config.example.edn")]
                (assoc c :tag-schema (config/compile-tag-schema c)))
        tw    (rc-writer/writer conn (recall-count/->TagRecallCount 14))
        mw    (rc-writer/writer conn (recall-count/->MemoryRecallCount 14))
        app   (handler/app conn cfg tw mw)
        model (build-model)
        other (vec (for [i (range 50)] {:src (str "o" i) :pairs #{(nth vocab (mod (* i 7) (count vocab)))}}))]
    (d/transact! conn (mapv #(mem-tx gen-user %) model))
    (d/transact! conn (mapv #(mem-tx other-user %) other))
    (reset! ctx {:app app :model model})
    ;; Quiet the per-request info line, so 200 recalls do not flood the output.
    (t/set-min-level! :warn)
    (try (run)
         (finally (t/set-min-level! :info)
                  (rc-writer/drain! tw) (rc-writer/drain! mw) (d/close conn)))))

(use-fixtures :once store-fixture)

;; ---------- the oracle: an independent per-memory predicate ----------

(defn- matches?
  "True when `expr` matches a memory carrying `pairs`. A leaf is membership, :and
  is every child, :or is some child, :not is the negation of its child. Built on
  the stack-safe fold, so it cannot overflow, and its boolean logic shares no
  mechanism with the datalog compiler it checks."
  [expr pairs]
  (expr/transform
   (fn [leaf] (contains? pairs leaf))
   (fn [op kids]
     (case op
       :and (every? true? kids)
       :or  (boolean (some true? kids))
       :not (not (first kids))))
   expr))

(defn- expected-srcs [model expr]
  (into #{} (comp (filter #(matches? expr (:pairs %))) (map :src)) model))

;; ---------- the generator and the property ----------

(def ^:private expr-gen
  ;; Capped so the largest expressions stay around a dozen-plus nodes nesting
  ;; several levels: enough to exercise every compiler path, bounded enough that a
  ;; single trial's datalog query over the thousand-memory store stays fast. The
  ;; giant expressions only add Datalevin cost, not compiler coverage.
  (gen/scale
   #(min % 15)
   (gen/recursive-gen
    (fn [inner]
      (gen/one-of
       [(gen/fmap (fn [cs] {:and (vec cs)}) (gen/vector inner 1 3))
        (gen/fmap (fn [cs] {:or  (vec cs)}) (gen/vector inner 1 3))
        (gen/fmap (fn [c]  {:not c}) inner)]))
    (gen/elements vocab))))

(defn- recall-srcs
  "Recall `expr` through the full app as gen-user, returning the set of srcs the
  server matched."
  [app expr]
  (let [resp (app {:request-method :post
                   :uri "/memories/recall/by-tags"
                   :headers {"x-engram-user" gen-user
                             "content-type"  "application/json"
                             "accept"        "application/json"}
                   :body (io/input-stream (.getBytes (json/write-value-as-string {:match expr}) "UTF-8"))})
        body (json/read-value (slurp (:body resp)) json/keyword-keys-object-mapper)]
    (into #{} (map :src) (:memories body))))

;; ^:generative keeps this out of the fast `clojure -M:test` dev run. The release
;; gate (`clojure -T:build test`) runs the whole suite, so it runs here too.
(defspec ^:generative recall-by-match-equals-predicate-oracle 200
  (prop/for-all [expr expr-gen]
    (let [{:keys [app model]} @ctx]
      (= (expected-srcs model expr) (recall-srcs app expr)))))
