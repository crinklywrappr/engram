(ns engram.core-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [engram.config :as config]
            [engram.errors :as errors]
            [engram.handler :as handler]
            [engram.memory :as memory]
            [engram.migrations :as migrations]
            [engram.search :as search]
            [engram.stats :as stats]
            [engram.stats.writer :as stat-writer]
            [engram.system :as system]
            [jsonista.core :as json]
            [malli.core :as m]
            [syncopate.core :as sc])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def cfg (let [c (config/load-config "deploy/engram-config.example.edn")]
           (assoc c :tag-schema (config/compile-tag-schema c))))

(defn fresh-conn
  "A migrated connection on a fresh temp dir, opened exactly as the system does
  through system/migrated-conn: a bare open, the migrations, a re-index when the
  search configuration drifted (always on a fresh dir), then a reopen with the
  search options."
  []
  (let [dir (.toString (Files/createTempDirectory "engram-test" (make-array FileAttribute 0)))]
    (system/migrated-conn (d/get-conn dir) dir)))

;; ---------- tag and token validation ----------

(deftest tag-validation
  (let [s (:tag-schema cfg)]
    (testing "a valid domain fact passes"
      (is (nil? (config/tag-error s [["domain" "clojure"]]))))
    (testing "a required category (domain +) missing fails"
      (is (= errors/no-configuration (:code (config/tag-error s [["tech" "datalevin"]])))))
    (testing "an unknown category fails (categories are closed)"
      (is (= errors/no-configuration (:code (config/tag-error s [["framework" "reitit"]])))))))

(deftest token-rule
  (testing "valid tokens are accepted"
    (doseq [s ["clojure" "datalevin" "foo-bar-2" "clojure-1-12" "d3"]]
      (is (m/validate config/Token s) s)))
  (testing "invalid tokens are rejected"
    (doseq [s ["Clojure" "node.js" "c++" "a_b" "a/b" "-x" "x-" "a--b" "3d" "a b"]]
      (is (not (m/validate config/Token s)) s))))

(deftest load-config-resolves-env
  (testing "with ENGRAM_CONFIG unset, load-config reads default-path"
    (with-redefs-fn {#'config/getenv (constantly nil)}
      (fn []
        (is (= 14 (:half-life-days
                   (config/load-config "deploy/engram-config.example.edn")))))))
  (testing "with ENGRAM_CONFIG set, load-config reads that path over default-path"
    (let [tmp (java.io.File/createTempFile "engram-cfg" ".edn")]
      (try
        (spit tmp (pr-str {:half-life-days 99 :configurations [{"domain" "+"}]}))
        (with-redefs-fn {#'config/getenv (fn [k] (when (= k "ENGRAM_CONFIG") (.getPath tmp)))}
          (fn []
            (is (= 99 (:half-life-days
                       (config/load-config "deploy/engram-config.example.edn"))))))
        (finally (.delete tmp))))))

(deftest config-categories-validation
  (let [base {:half-life-days 14
              :configurations [{"domain" "+" "scope" "?"}]}]
    (testing "a config with no :categories is returned unchanged"
      (is (= base (config/validate-config base))))
    (testing "a valid :categories map is accepted"
      (let [c (assoc base :categories {"domain" {:description "broad subject"
                                                 :examples ["clojure" "databases"]}})]
        (is (= c (config/validate-config c)))))
    (testing "a description over 256 characters is rejected"
      (let [c (assoc base :categories {"domain" {:description (apply str (repeat 257 "x"))}})]
        (is (thrown? clojure.lang.ExceptionInfo (config/validate-config c)))))
    (testing "a non-token example is rejected"
      (let [c (assoc base :categories {"domain" {:examples ["Clojure"]}})]
        (is (thrown? clojure.lang.ExceptionInfo (config/validate-config c)))))
    (testing "a described category absent from the configurations is rejected"
      (let [c (assoc base :categories {"framework" {:description "web stack"}})]
        (is (thrown? clojure.lang.ExceptionInfo (config/validate-config c)))))))

(deftest one-of-restricts-labels
  (let [schema (config/compile-tag-schema
                {:configurations [{"scope" {:cardinality "1" :one-of ["global" "project"]}
                                   "domain" "+"}]})]
    (testing "a label in the :one-of set conforms"
      (is (nil? (config/tag-error schema [["scope" "global"] ["domain" "clojure"]]))))
    (testing "a valid token outside the :one-of set fails"
      (is (= errors/no-configuration
             (:code (config/tag-error schema [["scope" "other"] ["domain" "clojure"]])))))
    (testing "the map's cardinality still applies, so a missing scope fails"
      (is (= errors/no-configuration
             (:code (config/tag-error schema [["domain" "clojure"]])))))))

(deftest validate-config-rejects-bad-value-set
  (testing "a category map without :cardinality is rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (config/validate-config {:configurations [{"scope" {:one-of ["global"]}}]}))))
  (testing "a non-token :one-of label is rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (config/validate-config {:configurations [{"scope" {:cardinality "?" :one-of ["Global"]}}]}))))
  (testing "an unknown cardinality is rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (config/validate-config {:configurations [{"scope" {:cardinality "x" :one-of ["global"]}}]}))))
  (testing "an empty :one-of is rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (config/validate-config {:configurations [{"scope" {:cardinality "?" :one-of []}}]}))))
  (testing "a well-formed value-set map passes unchanged"
    (let [c {:configurations [{"scope" {:cardinality "?" :one-of ["global" "project"]} "domain" "+"}]}]
      (is (= c (config/validate-config c))))))

;; ---------- memory: create, transitive recall, isolation ----------

(deftest transitive-recall-and-isolation
  (let [conn (fresh-conn)]
    (try
      ;; A matches the pair and links to src "b"; B does not match the pair.
      (memory/create! conn "alice" {:content "prefer ==" :src "a"
                                    :tags [["domain" "clojure"]] :related ["b"]})
      (memory/create! conn "alice" {:content "uses datalevin" :src "b"
                                    :tags [["tech" "datalevin"]]})
      ;; bob owns a matching fact that alice must never see.
      (memory/create! conn "bob" {:content "bob secret" :src "z"
                                  :tags [["domain" "clojure"]]})
      (let [rows (memory/recall conn "alice" [["domain" "clojure"]])
            srcs (set (map :src rows))]
        (testing "the pair match is returned"
          (is (contains? srcs "a")))
        (testing "the transitive related-by-src memory is pulled in"
          (is (contains? srcs "b")))
        (testing "another user's memory is never returned"
          (is (not (contains? srcs "z")))
          (is (not (some #(= "bob secret" (:content %)) rows)))))
      (finally (d/close conn)))))

(deftest recall-traversal-shapes
  (testing "a cycle terminates and returns both nodes"
    (let [conn (fresh-conn)]
      (try
        (memory/create! conn "alice" {:content "c1" :src "c1" :tags [["domain" "clojure"]] :related ["c2"]})
        (memory/create! conn "alice" {:content "c2" :src "c2" :tags [["misc" "m"]] :related ["c1"]})
        (is (= #{"c1" "c2"} (set (map :src (memory/recall conn "alice" [["domain" "clojure"]])))))
        (finally (d/close conn)))))
  (testing "a diamond returns the shared child once"
    (let [conn (fresh-conn)]
      (try
        (memory/create! conn "alice" {:content "m1" :src "m1" :tags [["domain" "clojure"]] :related ["x"]})
        (memory/create! conn "alice" {:content "m2" :src "m2" :tags [["domain" "clojure"]] :related ["x"]})
        (memory/create! conn "alice" {:content "x" :src "x" :tags [["misc" "m"]]})
        (let [srcs (map :src (memory/recall conn "alice" [["domain" "clojure"]]))]
          (is (= #{"m1" "m2" "x"} (set srcs)))
          (is (== 1 (count (filter #{"x"} srcs)))))
        (finally (d/close conn)))))
  (testing "a self-loop returns the memory once and terminates"
    (let [conn (fresh-conn)]
      (try
        (memory/create! conn "alice" {:content "s" :src "s" :tags [["domain" "clojure"]] :related ["s"]})
        (is (= ["s"] (map :src (memory/recall conn "alice" [["domain" "clojure"]]))))
        (finally (d/close conn)))))
  (testing "a related pointing at a nonexistent src yields nothing extra"
    (let [conn (fresh-conn)]
      (try
        (memory/create! conn "alice" {:content "g" :src "g" :tags [["domain" "clojure"]] :related ["ghost"]})
        (is (= #{"g"} (set (map :src (memory/recall conn "alice" [["domain" "clojure"]])))))
        (finally (d/close conn))))))

(deftest all-memories-returns-every-owned-memory
  (let [conn (fresh-conn)]
    (try
      ;; a2 carries a non-conforming tag and links nothing: no single pair recall
      ;; would surface it alongside a1, but all-memories must return both.
      (memory/create! conn "alice" {:content "a1" :src "a" :tags [["domain" "clojure"]] :related ["b"]})
      (memory/create! conn "alice" {:content "a2" :src "b" :tags [["tech" "datalevin"]]})
      (memory/create! conn "bob"   {:content "b1" :src "z" :tags [["domain" "clojure"]]})
      (let [rows (memory/all-memories conn "alice")
            srcs (set (map :src rows))]
        (testing "the function returns a lazy sequence"
          (is (instance? clojure.lang.LazySeq rows)))
        (testing "every memory the user owns appears"
          (is (= #{"a" "b"} srcs)))
        (testing "another user's memory never appears"
          (is (not (contains? srcs "z"))))
        (testing "each row carries the recall wire shape"
          (let [m (first (filter #(= "a" (:src %)) rows))]
            (is (= #{:id :content :src :tags :related} (set (keys m)))))))
      (finally (d/close conn)))))

(deftest nonconforming-returns-only-failing-memories
  (let [conn    (fresh-conn)
        reject? #(config/tag-error (:tag-schema cfg) %)]
    (try
      (memory/create! conn "alice" {:content "ok" :src "ok" :tags [["domain" "clojure"]]})
      (memory/create! conn "alice" {:content "bad-tag" :src "bad" :tags [["tech" "datalevin"]]})
      (memory/create! conn "alice" {:content "no-tags" :src "none"})
      (memory/create! conn "bob"   {:content "bob-bad" :src "zz" :tags [["tech" "datalevin"]]})
      (let [rows (memory/nonconforming conn "alice" reject?)
            srcs (set (map :src rows))]
        (testing "the function returns a lazy sequence"
          (is (instance? clojure.lang.LazySeq rows)))
        (testing "a conforming memory never appears"
          (is (not (contains? srcs "ok"))))
        (testing "a memory whose tags match no configuration appears"
          (is (contains? srcs "bad")))
        (testing "a memory with no tags appears"
          (is (contains? srcs "none")))
        (testing "another user's nonconforming memory never appears"
          (is (not (contains? srcs "zz")))))
      (finally (d/close conn)))))

(deftest conforming-fraction-counts-conforming-over-total
  (let [conn    (fresh-conn)
        reject? #(config/tag-error (:tag-schema cfg) %)]
    (try
      (memory/create! conn "alice" {:content "c1" :src "c1" :tags [["domain" "clojure"]]})
      (memory/create! conn "alice" {:content "c2" :src "c2" :tags [["domain" "databases"]]})
      (memory/create! conn "alice" {:content "c3" :src "c3" :tags [["domain" "clojure"] ["tech" "datalevin"]]})
      (memory/create! conn "alice" {:content "bad" :src "bad" :tags [["tech" "datalevin"]]})  ; no domain
      (memory/create! conn "bob"   {:content "zz" :src "zz" :tags [["tech" "datalevin"]]})      ; other user
      (testing "the fraction is conforming over total, for the caller only"
        (is (== 0.75 (memory/conforming-fraction conn "alice" reject?))))   ; 3 of 4 conform
      (testing "a user with no memories gets 0.0"
        (is (== 0.0 (memory/conforming-fraction conn "nobody" reject?))))
      (finally (d/close conn)))))

(deftest fetch-returns-owned-memory
  (let [conn (fresh-conn)]
    (try
      (let [id (memory/create! conn "alice" {:content "x" :src "s"
                                             :tags [["domain" "clojure"]] :related ["r"]})]
        (testing "the owner gets the wire memory by id"
          (let [m (memory/fetch conn "alice" id)]
            (is (= id (:id m)))
            (is (= "x" (:content m)))
            (is (= "s" (:src m)))
            (is (= [["domain" "clojure"]] (:tags m)))
            (is (= ["r"] (:related m)))))
        (testing "another user gets nil"
          (is (nil? (memory/fetch conn "bob" id))))
        (testing "a missing id gets nil"
          (is (nil? (memory/fetch conn "alice" (str (java.util.UUID/randomUUID)))))))
      (finally (d/close conn)))))

(deftest search-ranks-content-and-src
  (let [conn (fresh-conn)]
    (try
      ;; engram-deploy: term in src and content. deploy-notes: term in src only.
      ;; clojure-tips: term three times in content. unrelated: no term.
      (memory/create! conn "alice" {:content "deploy the server to the pi" :src "engram-deploy"
                                    :tags [["domain" "clojure"]]})
      (memory/create! conn "alice" {:content "a short note" :src "deploy-notes"})
      (memory/create! conn "alice" {:content "deploy deploy deploy everywhere" :src "clojure-tips"})
      (memory/create! conn "alice" {:content "nothing relevant here" :src "unrelated"})
      ;; bob owns a matching memory alice must never see.
      (memory/create! conn "bob" {:content "bob deploys things" :src "bob-deploy"})
      (let [rows (memory/search conn "alice" "deploy" 10)
            srcs (set (map :src rows))]
        (testing "every alice memory with the term in content or src matches, deduped"
          (is (== 3 (count rows)))
          (is (= #{"engram-deploy" "deploy-notes" "clojure-tips"} srcs)))
        (testing "a src is searchable by a hyphen-split word (deploy only in deploy-notes's src)"
          (is (contains? srcs "deploy-notes")))
        (testing "a non-matching memory never appears"
          (is (not (contains? srcs "unrelated"))))
        (testing "another user's match never appears"
          (is (not (contains? srcs "bob-deploy"))))
        (testing "each row carries a numeric score and no related key"
          (is (every? #(number? (:score %)) rows))
          (is (not-any? :related rows)))
        (testing "rows are ranked by score, descending"
          (is (= (map :score rows) (sort > (map :score rows)))))
        (testing "the memory with the term three times outranks the single mentions"
          (is (= "clojure-tips" (:src (first rows))))))
      (testing "the limit caps the count and keeps the top ranks"
        (let [top1 (memory/search conn "alice" "deploy" 1)]
          (is (== 1 (count top1)))
          (is (= "clojure-tips" (:src (first top1))))))
      (testing "a term that matches nothing returns an empty vector"
        (is (= [] (memory/search conn "alice" "nonexistentterm" 10))))
      (testing "the matched word in a src carries over to the whole-src token too"
        (is (contains? (set (map :src (memory/search conn "alice" "engram" 10))) "engram-deploy")))
      (finally (d/close conn)))))

(deftest reindex-decision
  (let [full #{:memory/content :memory/src}]
    (testing "no drift: equal opts signature and unchanged full-text set skips re-index"
      (is (false? (boolean (search/reindex? search/engine-opts full full)))))
    (testing "a grown full-text set forces a re-index"
      (is (true? (boolean (search/reindex? search/engine-opts #{:memory/content} full)))))
    (testing "absent persisted opts (a fresh or pre-feature store) forces a re-index"
      (is (true? (boolean (search/reindex? nil full full)))))
    (testing "a changed index-structure flag forces a re-index"
      (is (true? (boolean (search/reindex? (assoc search/engine-opts :index-position? false)
                                           full full)))))))

(deftest search-opts-persist-and-round-trip
  ;; Pins the Datalevin behavior the drift check relies on: our inter-fn
  ;; search-opts survive a close and a bare reopen. If a Datalevin upgrade breaks
  ;; this, system/migrated-conn would silently re-index on every boot, so this
  ;; test fails loudly instead.
  (let [dir (.toString (Files/createTempDirectory "engram-opts" (make-array FileAttribute 0)))]
    (let [c (d/get-conn dir {} search/conn-opts)]
      (sc/migrate-all! (sc/store c) migrations/migrations)
      (d/close c))
    (let [c (d/get-conn dir)]                                   ; bare reopen, exactly as the system does
      (try
        (testing "the persisted search-opts round-trip to an equal signature"
          (is (= (search/opts-signature search/engine-opts)
                 (search/opts-signature (:search-opts (d/opts c))))))
        (testing "a bare open yields the persisted analyzer, not the default (index-position? default is false)"
          (is (true? (:index-position? (:search-opts (d/opts c))))))
        (finally (d/close c))))))

(deftest search-backfills-existing-src-on-migration
  ;; Mirror an existing deployment through the real boot path: a src is written
  ;; while it is not yet full-text, then system/migrated-conn migrates src to
  ;; full-text, sees the full-text set grow, and re-indexes to backfill it.
  (let [dir (.toString (Files/createTempDirectory "engram-bf" (make-array FileAttribute 0)))]
    (let [c (d/get-conn dir)]
      (sc/migrate-all! (sc/store c) (take 1 migrations/migrations))  ; 001 only: src not full-text
      (d/close c))
    (let [c (d/get-conn dir {} search/conn-opts)]                    ; reopen so the content engine is built
      (memory/create! c "alice" {:content "a short note" :src "engram-deploy"})
      (testing "before the src migration, a src-only word does not match"
        (is (empty? (memory/search c "alice" "engram" 10))))
      (d/close c))
    (let [conn (system/migrated-conn (d/get-conn dir) dir)]          ; applies 002 and backfills on drift
      (try
        (testing "after migrating and the drift re-index, the existing src is searchable by its words"
          (is (= #{"engram-deploy"} (set (map :src (memory/search conn "alice" "engram" 10))))))
        (finally (d/close conn))))))

(deftest delete-removes-memory
  (let [conn (fresh-conn)]
    (try
      (let [id (memory/create! conn "alice" {:content "temp" :src "s"
                                             :tags [["domain" "clojure"]]})]
        (testing "the memory is present before the delete"
          (is (== 1 (count (memory/recall conn "alice" [["domain" "clojure"]])))))
        (testing "delete removes it and returns the id"
          (is (= id (memory/delete! conn "alice" id)))
          (is (empty? (memory/recall conn "alice" [["domain" "clojure"]])))))
      (finally (d/close conn)))))

(deftest delete-missing-and-isolation
  (let [conn (fresh-conn)]
    (try
      (testing "deleting a missing id returns nil"
        (is (nil? (memory/delete! conn "alice" (str (java.util.UUID/randomUUID))))))
      (let [id (memory/create! conn "alice" {:content "alice only" :src "s"
                                             :tags [["domain" "clojure"]]})]
        (testing "another user cannot delete it, and it survives"
          (is (nil? (memory/delete! conn "bob" id)))
          (is (= 1 (count (memory/recall conn "alice" [["domain" "clojure"]]))))))
      (finally (d/close conn)))))

;; ---------- memory: batch apply ----------

(def ^:private create-tag-err #(config/create-tag-error (:tag-schema cfg) %))
(def ^:private update-tag-err #(config/update-tag-error (:tag-schema cfg) %))

(defn- seed-recall!
  "Synchronously record one recall of each pair, for test setup. Writes through
  plan-recalls at a count of one, the same tx a single recall produces."
  [conn user half-life pairs]
  (d/transact! conn (stats/plan-recalls (d/db conn) user half-life
                                      (into {} (map (fn [p] [p 1])) pairs)
                                      (System/currentTimeMillis))))

(deftest batch-all-pass
  (let [conn (fresh-conn)]
    (try
      (let [x   (memory/create! conn "alice" {:content "orig-x" :src "x" :tags [["domain" "clojure"]]})
            y   (memory/create! conn "alice" {:content "orig-y" :src "y" :tags [["domain" "clojure"]]})
            res (memory/apply-batch!
                 conn "alice"
                 {:create [{:content "c-a" :src "a" :tags [["domain" "clojure"]]}]
                  :update [{:id x :content "new-x"}]
                  :delete [y]}
                 create-tag-err update-tag-err)]
        (testing "the batch reports success, one new id, and three memories written"
          (is (true? (:ok? res)))
          (is (== 1 (count (:ids res))))
          (is (== 3 (:applied res))))
        (let [by-src (into {} (map (juxt :src identity)
                                   (memory/recall conn "alice" [["domain" "clojure"]])))]
          (testing "the create landed and the update changed content in place"
            (is (contains? by-src "a"))
            (is (= "new-x" (:content (by-src "x")))))
          (testing "the delete removed its memory"
            (is (not (contains? by-src "y"))))))
      (finally (d/close conn)))))

(deftest batch-bad-tag-writes-nothing
  (let [conn (fresh-conn)]
    (try
      (let [res (memory/apply-batch!
                 conn "alice"
                 {:create [{:content "ok" :src "a" :tags [["domain" "clojure"]]}
                           {:content "bad" :src "b" :tags [["tech" "datalevin"]]}]}
                 create-tag-err update-tag-err)]
        (testing "the batch is rejected and names only the failing op by its group index"
          (is (false? (:ok? res)))
          (is (= [{:op "create" :i 1}] (map #(select-keys % [:op :i]) (:errors res))))
          (is (= errors/no-configuration (:code (first (:errors res))))))
        (testing "nothing was written, not even the valid create"
          (is (empty? (memory/recall conn "alice" [["domain" "clojure"]])))))
      (finally (d/close conn)))))

(deftest batch-missing-id-and-isolation
  (let [conn (fresh-conn)]
    (try
      (testing "an update and a delete of a missing id both fail as not-found"
        (let [res (memory/apply-batch!
                   conn "alice"
                   {:update [{:id (str (java.util.UUID/randomUUID)) :content "x"}]
                    :delete [(str (java.util.UUID/randomUUID))]}
                   create-tag-err update-tag-err)]
          (is (false? (:ok? res)))
          (is (= #{{:op "update" :i 0} {:op "delete" :i 0}}
                 (set (map #(select-keys % [:op :i]) (:errors res)))))
          (is (= #{errors/not-found} (set (map :code (:errors res)))))))
      (let [x (memory/create! conn "alice" {:content "alice only" :src "x" :tags [["domain" "clojure"]]})]
        (testing "another user cannot delete it, and it survives"
          (let [res (memory/apply-batch! conn "bob" {:delete [x]} create-tag-err update-tag-err)]
            (is (false? (:ok? res)))
            (is (= errors/not-found (:code (first (:errors res))))))
          (is (== 1 (count (memory/recall conn "alice" [["domain" "clojure"]]))))))
      (finally (d/close conn)))))

(deftest batch-cumulative-update
  (let [conn (fresh-conn)]
    (try
      (let [x   (memory/create! conn "alice" {:content "orig" :src "x" :tags [["domain" "clojure"]]})
            res (memory/apply-batch!
                 conn "alice"
                 {:update [{:id x :content "c2"} {:id x :related ["y"]}]}
                 create-tag-err update-tag-err)]
        (testing "several updates to one id fold cumulatively per field, writing one memory"
          (is (true? (:ok? res)))
          (is (== 1 (:applied res)))
          (let [m (first (memory/recall conn "alice" [["domain" "clojure"]]))]
            (is (= "c2" (:content m)))
            (is (= ["y"] (:related m))))))
      (finally (d/close conn)))))

(deftest batch-update-delete-conflict
  (let [conn (fresh-conn)]
    (try
      (let [x   (memory/create! conn "alice" {:content "orig" :src "x" :tags [["domain" "clojure"]]})
            res (memory/apply-batch!
                 conn "alice"
                 {:update [{:id x :content "after"}] :delete [x]}
                 create-tag-err update-tag-err)]
        (testing "an id in both update and delete flags both ops and writes nothing"
          (is (false? (:ok? res)))
          (is (= #{{:op "update" :i 0} {:op "delete" :i 0}}
                 (set (map #(select-keys % [:op :i]) (:errors res)))))
          (is (= #{errors/conflict} (set (map :code (:errors res)))))
          (is (= "orig" (:content (first (memory/recall conn "alice" [["domain" "clojure"]])))))))
      (finally (d/close conn)))))

(deftest batch-duplicate-delete-tolerated
  (let [conn (fresh-conn)]
    (try
      (let [x   (memory/create! conn "alice" {:content "orig" :src "x" :tags [["domain" "clojure"]]})
            res (memory/apply-batch! conn "alice" {:delete [x x]} create-tag-err update-tag-err)]
        (testing "a repeated delete id is deduplicated, deletes once, no error"
          (is (true? (:ok? res)))
          (is (== 1 (:applied res)))
          (is (empty? (memory/recall conn "alice" [["domain" "clojure"]])))))
      (finally (d/close conn)))))

(deftest plan-batch-is-pure
  (let [conn (fresh-conn)]
    (try
      (let [x    (memory/create! conn "alice" {:content "orig" :src "x" :tags [["domain" "clojure"]]})
            db   (d/db conn)
            plan (memory/plan-batch db "alice"
                                    {:create [{:content "c-a" :src "a" :tags [["domain" "clojure"]]}]
                                     :update [{:id x :content "new-x"}]}
                                    create-tag-err update-tag-err)]
        (testing "the planner returns a plan with ids, tx-data, and an applied count"
          (is (true? (:ok? plan)))
          (is (== 1 (count (:ids plan))))
          (is (seq (:tx-data plan)))
          (is (== 2 (:applied plan))))
        (testing "the planner writes nothing: the seed memory is unchanged and alone"
          (is (= "orig" (:content (first (memory/recall conn "alice" [["domain" "clojure"]])))))
          (is (== 1 (count (memory/recall conn "alice" [["domain" "clojure"]]))))))
      (finally (d/close conn)))))

(deftest plan-batch-reports-errors-without-tx-data
  (let [conn (fresh-conn)]
    (try
      (let [plan (memory/plan-batch (d/db conn) "alice"
                                    {:update [{:id (str (java.util.UUID/randomUUID)) :content "x"}]}
                                    create-tag-err update-tag-err)]
        (testing "a failing plan carries only the failing op and no tx-data"
          (is (false? (:ok? plan)))
          (is (= errors/not-found (:code (first (:errors plan)))))
          (is (nil? (:tx-data plan)))))
      (finally (d/close conn)))))

(deftest batch-empty
  (let [conn (fresh-conn)]
    (try
      (doseq [b [{} {:create [] :update [] :delete []}]]
        (let [res (memory/apply-batch! conn "alice" b create-tag-err update-tag-err)]
          (testing "an empty batch succeeds with no ids and applied 0"
            (is (true? (:ok? res)))
            (is (= [] (:ids res)))
            (is (== 0 (:applied res))))))
      (finally (d/close conn)))))

;; ---------- stats ----------

(deftest stats-lifetime-and-recent
  (let [conn (fresh-conn)]
    (try
      (seed-recall! conn "alice" 14 [["domain" "clojure"]])
      (seed-recall! conn "alice" 14 [["domain" "clojure"]])
      (let [row (first (stats/recalls conn "alice" 14))]
        (testing "lifetime counts every recall"
          (is (= 2 (:lifetime row))))
        (testing "recent decay count is positive and bounded by lifetime"
          (is (pos? (:recent row)))
          (is (<= (:recent row) 2.0))))
      (finally (d/close conn)))))

(deftest stat-writer-serializes-and-drains
  (let [conn (fresh-conn)
        w    (stat-writer/writer conn 14)]
    (try
      ;; 10 threads each record the same pair 5 times, concurrently
      (let [fs (doall (repeatedly 10 #(future (dotimes [_ 5]
                                                (stat-writer/record! w"alice" [["domain" "clojure"]])))))]
        (run! deref fs))
      (stat-writer/drain! w)
      (testing "every concurrent increment lands, none lost to a race"
        (is (== 50 (:lifetime (first (stats/recalls conn "alice" 14))))))
      (finally (d/close conn)))))

(deftest stat-write-failure-is-isolated
  (let [conn (fresh-conn)
        w    (stat-writer/writer conn 14)]
    (d/close conn)                                   ; every flush now fails
    (testing "recording never throws to the caller when the write fails"
      (is (nil? (stat-writer/record! w"alice" [["domain" "clojure"]]))))
    (testing "draining a failing writer does not throw"
      (is (nil? (stat-writer/drain! w))))))

(deftest stat-flush-is-debounced-and-coalesces
  (let [conn (fresh-conn)
        w    (stat-writer/writer conn 14 :debounce-ms 80 :max-wait-ms 1000)]
    (try
      (dotimes [_ 5] (stat-writer/record! w"alice" [["domain" "clojure"]]))
      (testing "the flush is deferred, so no write has happened yet"
        (Thread/sleep 20)
        (is (empty? (stats/recalls conn "alice" 14))))
      (testing "after the debounce elapses the burst coalesces into the summed count"
        (Thread/sleep 250)
        (is (== 5 (:lifetime (first (stats/recalls conn "alice" 14))))))
      (finally (stat-writer/drain! w) (d/close conn)))))

(deftest stat-flush-max-wait-caps-postponement
  (let [conn (fresh-conn)
        w    (stat-writer/writer conn 14 :debounce-ms 5000 :max-wait-ms 100)]
    (try
      ;; keep rescheduling faster than the debounce; the max wait must still flush
      (dotimes [_ 8] (stat-writer/record! w"alice" [["domain" "clojure"]]) (Thread/sleep 30))
      (testing "the maximum wait flushes even though the debounce never elapses"
        (is (pos? (:lifetime (first (stats/recalls conn "alice" 14))))))
      (finally (stat-writer/drain! w) (d/close conn)))))

(deftest plan-recalls-pure-and-coalesced
  (let [conn (fresh-conn)]
    (try
      (seed-recall! conn "alice" 14 [["domain" "clojure"]])   ; seed one row
      (let [db (d/db conn)]
        (testing "a coalesced count adds to the prior lifetime and decayed weight"
          (let [tx (stats/plan-recalls db "alice" 14 {["domain" "clojure"] 3} (System/currentTimeMillis))]
            (is (== 1 (count tx)))
            (is (== 4 (:stat/lifetime (first tx))))
            (is (< 3.0 (:stat/decayed (first tx)) 4.001))))
        (testing "a pair with no prior row starts at the count"
          (let [tx (stats/plan-recalls db "bob" 14 {["domain" "clojure"] 2} (System/currentTimeMillis))]
            (is (== 2 (:stat/lifetime (first tx))))
            (is (== 2.0 (:stat/decayed (first tx))))))
        (testing "an empty pair-counts plans nothing"
          (is (= [] (stats/plan-recalls db "alice" 14 {} (System/currentTimeMillis)))))
        (testing "the planner writes nothing: the stored lifetime is still one"
          (is (== 1 (:lifetime (first (stats/recalls conn "alice" 14)))))))
      (finally (d/close conn)))))

(deftest stats-injective-across-user-space
  (let [conn (fresh-conn)]
    (try
      (seed-recall! conn "alice one" 14 [["domain" "clojure"]])
      (seed-recall! conn "alice" 14 [["domain" "clojure"]])
      (seed-recall! conn "alice" 14 [["domain" "clojure"]])
      (testing "user ids that differ by a space keep separate rows via the composite tuple"
        (is (= 1 (:lifetime (first (stats/recalls conn "alice one" 14)))))
        (is (= 2 (:lifetime (first (stats/recalls conn "alice" 14))))))
      (finally (d/close conn)))))

(deftest catalog-covers-the-pair-universe
  (let [conn (fresh-conn)]
    (try
      ;; "clojure" sits on two of alice's memories and was never recalled.
      ;; "datalevin" sits on one and was recalled twice.
      (memory/create! conn "alice" {:content "m1" :src "a" :tags [["domain" "clojure"]]})
      (memory/create! conn "alice" {:content "m2" :src "b" :tags [["domain" "clojure"] ["tech" "datalevin"]]})
      ;; bob owns a pair alice must never see in her catalog.
      (memory/create! conn "bob" {:content "secret" :src "z" :tags [["domain" "haskell"]]})
      (seed-recall! conn "alice" 14 [["tech" "datalevin"]])
      (seed-recall! conn "alice" 14 [["tech" "datalevin"]])
      (let [rows (stats/catalog conn "alice" 14)
            by-pair (into {} (map (juxt (juxt :category :label) identity)) rows)
            cold (by-pair ["domain" "clojure"])
            warm (by-pair ["tech" "datalevin"])]
        (testing "a pair on a memory but never recalled still appears, with a lifetime of 0"
          (is (some? cold))
          (is (== 2 (:count cold)))
          (is (== 0 (:lifetime cold)))
          (is (== 0.0 (:recent cold))))
        (testing "a recalled pair shows its real lifetime and a positive decayed recent"
          (is (== 1 (:count warm)))
          (is (== 2 (:lifetime warm)))
          (is (pos? (:recent warm))))
        (testing "a never-recalled pair never carries a nil count"
          (is (every? (comp integer? :count) rows)))
        (testing "one user never sees another user's pairs"
          (is (not (contains? by-pair ["domain" "haskell"])))))
      (finally (d/close conn)))))

(deftest catalog-counts-only-memories-carrying-the-pair
  (let [conn (fresh-conn)]
    (try
      (memory/create! conn "alice" {:content "m1" :src "a" :tags [["domain" "clojure"]]})
      (memory/create! conn "alice" {:content "m2" :src "b" :tags [["domain" "clojure"]]})
      (memory/create! conn "alice" {:content "m3" :src "c" :tags [["domain" "databases"]]})
      (let [by-pair (into {} (map (juxt (juxt :category :label) :count))
                          (stats/catalog conn "alice" 14))]
        (testing "the count equals the number of the caller's memories that carry the pair"
          (is (== 2 (by-pair ["domain" "clojure"])))
          (is (== 1 (by-pair ["domain" "databases"])))))
      (finally (d/close conn)))))

(deftest link-density-metrics
  (let [conn (fresh-conn)]
    (try
      ;; a -> b -> c is one chain, d is isolated, e points at a ghost src.
      (memory/create! conn "alice" {:content "a" :src "a" :related ["b"]})
      (memory/create! conn "alice" {:content "b" :src "b" :related ["c"]})
      (memory/create! conn "alice" {:content "c" :src "c"})
      (memory/create! conn "alice" {:content "d" :src "d"})
      (memory/create! conn "alice" {:content "e" :src "e" :related ["ghost"]})
      (memory/create! conn "bob"   {:content "z" :src "z" :related ["z2"]})
      (let [{:keys [avg-out-degree largest-wcc-fraction]} (stats/link-density conn "alice")]
        (testing "average out-degree counts edges to real src nodes over all nodes"
          (is (== 0.4 avg-out-degree)))          ; edges a->b, b->c; e->ghost excluded; 5 nodes
        (testing "largest weakly-connected component is a fraction of the nodes"
          (is (== 0.6 largest-wcc-fraction))))   ; {a,b,c} is 3 of 5 nodes
      (testing "a user with no memories gets zero density"
        (is (= {:avg-out-degree 0.0 :largest-wcc-fraction 0.0}
               (stats/link-density conn "nobody"))))
      (finally (d/close conn)))))

;; ---------- handler: auth, 409, streaming ----------

(defn- request
  [app method uri {:keys [user body accept query]}]
  (app (cond-> {:request-method method :uri uri
                :headers (cond-> {}
                           user   (assoc "x-engram-user" user)
                           accept (assoc "accept" accept)
                           body   (assoc "content-type" "application/json"))}
         query (assoc :query-string query)
         body  (assoc :body (io/input-stream (.getBytes (json/write-value-as-string body) "UTF-8"))))))

(defn- body-json [resp]
  (json/read-value (slurp (:body resp)) json/keyword-keys-object-mapper))

(deftest handler-flow
  (let [conn (fresh-conn)
        app  (handler/app conn cfg (stat-writer/writer conn (:half-life-days cfg)))]
    (try
      (testing "a request without the user header is rejected"
        (is (= 401 (:status (request app :post "/memories"
                                     {:body {:content "x" :src "s" :tags [["domain" "clojure"]]}
                                      :accept "application/json"})))))
      (testing "a valid create returns 201"
        (is (= 201 (:status (request app :post "/memories"
                                     {:user "alice" :accept "application/json"
                                      :body {:content "prefer ==" :src "s1"
                                             :tags [["domain" "clojure"]]}})))))
      (testing "an invalid create returns 409 with the configurations"
        (let [resp (request app :post "/memories"
                            {:user "alice" :accept "application/json"
                             :body {:content "bad" :src "s2" :tags [["tech" "datalevin"]]}})
              body (body-json resp)]
          (is (= 409 (:status resp)))
          (is (= "no-configuration" (:error body)))
          (is (some? (:configurations body)))))
      (testing "GET /config returns the configurations and the category descriptions"
        (let [resp (request app :get "/config" {:user "alice" :accept "application/json"})
              body (body-json resp)]
          (is (= 200 (:status resp)))
          (is (some? (:configurations body)))
          (is (map? (:categories body)))
          (is (= "the broad subject area" (get-in body [:categories :domain :description])))
          (is (some #{"clojure"} (get-in body [:categories :domain :examples])))))
      (testing "the recall call streams NDJSON when asked"
        (let [resp  (request app :post "/memories/recall"
                             {:user "alice" :accept "application/x-ndjson"
                              :body {:pairs [["domain" "clojure"]]}})
              lines (->> (slurp (:body resp)) str/split-lines (remove str/blank?))]
          (is (= "application/x-ndjson" (get-in resp [:headers "Content-Type"])))
          (is (>= (count lines) 2))                       ; header line + at least one memory
          (is (true? (:header (json/read-value (first lines) json/keyword-keys-object-mapper))))
          (is (some #(= "prefer ==" (:content (json/read-value % json/keyword-keys-object-mapper)))
                    (rest lines)))))
      (finally (d/close conn)))))

(deftest handler-recall-streams-all-rows
  ;; A recall of many matched memories streams every row over NDJSON: the header
  ;; line plus one line per memory, with nothing dropped.
  (let [conn (fresh-conn)
        app  (handler/app conn cfg (stat-writer/writer conn (:half-life-days cfg)))
        n    40]
    (try
      (dotimes [i n]
        (memory/create! conn "alice" {:content (str "m" i) :src (str "s" i)
                                      :tags [["domain" "clojure"]]}))
      (let [resp  (request app :post "/memories/recall"
                           {:user "alice" :accept "application/x-ndjson"
                            :body {:pairs [["domain" "clojure"]]}})
            lines (->> (slurp (:body resp)) str/split-lines (remove str/blank?))]
        (testing "every matched memory streams, not just the first chunk"
          (is (= (inc n) (count lines)))))          ; header line + n memories
      (finally (d/close conn)))))

(deftest handler-memories-streams-all
  ;; GET /memories streams every memory the caller owns over NDJSON: the header
  ;; line plus one line per memory, with nothing dropped and no other user's rows.
  (let [conn (fresh-conn)
        app  (handler/app conn cfg (stat-writer/writer conn (:half-life-days cfg)))
        n    40]
    (try
      (dotimes [i n]
        (memory/create! conn "alice" {:content (str "m" i) :src (str "s" i)
                                      :tags [["domain" "clojure"]]}))
      (memory/create! conn "bob" {:content "secret" :src "z" :tags [["domain" "clojure"]]})
      (let [resp  (request app :get "/memories" {:user "alice" :accept "application/x-ndjson"})
            lines (->> (slurp (:body resp)) str/split-lines (remove str/blank?))
            mems  (map #(json/read-value % json/keyword-keys-object-mapper) (rest lines))]
        (testing "the response is an NDJSON stream"
          (is (= "application/x-ndjson" (get-in resp [:headers "Content-Type"]))))
        (testing "the header line plus one line per owned memory, nothing dropped"
          (is (= (inc n) (count lines)))
          (is (true? (:header (json/read-value (first lines) json/keyword-keys-object-mapper)))))
        (testing "another user's memory never appears"
          (is (not (some #(= "secret" (:content %)) mems))))
        (testing "each memory line carries the recall wire keys"
          (is (every? #(every? % [:id :content :src]) mems))))
      (finally (d/close conn)))))

(deftest handler-nonconforming-streams
  (let [conn (fresh-conn)
        app  (handler/app conn cfg (stat-writer/writer conn (:half-life-days cfg)))]
    (try
      (memory/create! conn "alice" {:content "ok" :src "ok" :tags [["domain" "clojure"]]})
      (memory/create! conn "alice" {:content "bad" :src "bad" :tags [["tech" "datalevin"]]})
      (memory/create! conn "bob"   {:content "bob-bad" :src "zz" :tags [["tech" "datalevin"]]})
      (let [resp  (request app :get "/memories/nonconforming" {:user "alice" :accept "application/x-ndjson"})
            lines (->> (slurp (:body resp)) str/split-lines (remove str/blank?))
            mems  (map #(json/read-value % json/keyword-keys-object-mapper) (rest lines))
            srcs  (set (map :src mems))]
        (testing "the response is an NDJSON stream with a header line"
          (is (= "application/x-ndjson" (get-in resp [:headers "Content-Type"])))
          (is (true? (:header (json/read-value (first lines) json/keyword-keys-object-mapper)))))
        (testing "only the caller's nonconforming memory appears"
          (is (= #{"bad"} srcs))))
      (finally (d/close conn)))))

(deftest handler-fetch-one
  (let [conn (fresh-conn)
        app  (handler/app conn cfg (stat-writer/writer conn (:half-life-days cfg)))]
    (try
      (let [id (:id (body-json (request app :post "/memories"
                                        {:user "alice" :accept "application/json"
                                         :body {:content "x" :src "s" :tags [["domain" "clojure"]]}})))]
        (testing "the owner reads the memory by id"
          (let [resp (request app :get (str "/memories/" id) {:user "alice" :accept "application/json"})
                body (body-json resp)]
            (is (== 200 (:status resp)))
            (is (= id (:id body)))
            (is (= "x" (:content body)))))
        (testing "another user gets 404"
          (is (== 404 (:status (request app :get (str "/memories/" id)
                                        {:user "bob" :accept "application/json"})))))
        (testing "a missing id gets 404"
          (is (== 404 (:status (request app :get (str "/memories/" (java.util.UUID/randomUUID))
                                        {:user "alice" :accept "application/json"})))))
        (testing "a malformed id is a 400 coercion failure"
          (is (== 400 (:status (request app :get "/memories/not-a-uuid"
                                        {:user "alice" :accept "application/json"}))))))
      (finally (d/close conn)))))

(deftest handler-search-route
  (let [conn (fresh-conn)
        app  (handler/app conn cfg (stat-writer/writer conn (:half-life-days cfg)))]
    (try
      (memory/create! conn "alice" {:content "deploy to the pi" :src "engram-deploy"
                                    :tags [["domain" "clojure"] ["tech" "datalevin"]]})
      (memory/create! conn "alice" {:content "unrelated" :src "other"})
      (memory/create! conn "bob"   {:content "deploy secret" :src "bob-deploy"})
      (testing "a search returns a bounded results array, ranked and user-scoped"
        (let [resp (request app :post "/memories/search"
                            {:user "alice" :accept "application/json" :body {:search "deploy"}})
              body (body-json resp)]
          (is (== 200 (:status resp)))
          (is (vector? (:results body)))
          (is (= #{"engram-deploy"} (set (map :src (:results body)))))
          (let [row (first (:results body))]
            (is (number? (:score row)))
            (is (not (contains? row :related))))))
      (testing "with no categories, a row carries no tags"
        (let [row (first (:results (body-json (request app :post "/memories/search"
                                                       {:user "alice" :accept "application/json"
                                                        :body {:search "deploy"}}))))]
          (is (not (contains? row :tags)))))
      (testing "an empty categories vector also yields no tags"
        (let [row (first (:results (body-json (request app :post "/memories/search"
                                                       {:user "alice" :accept "application/json"
                                                        :body {:search "deploy" :categories []}}))))]
          (is (not (contains? row :tags)))))
      (testing "categories project the tags to the named ones only"
        (let [row (first (:results (body-json (request app :post "/memories/search"
                                                       {:user "alice" :accept "application/json"
                                                        :body {:search "deploy" :categories ["domain"]}}))))]
          (is (= [["domain" "clojure"]] (:tags row)))))
      (testing "a blank search string is a 400"
        (is (== 400 (:status (request app :post "/memories/search"
                                      {:user "alice" :accept "application/json" :body {:search "   "}})))))
      (testing "a missing search string is a 400 (coercion)"
        (is (== 400 (:status (request app :post "/memories/search"
                                      {:user "alice" :accept "application/json" :body {:limit 5}})))))
      (testing "an over-cap limit is clamped, not rejected"
        (is (== 200 (:status (request app :post "/memories/search"
                                      {:user "alice" :accept "application/json"
                                       :body {:search "deploy" :limit 999}})))))
      (testing "a request without the user header is rejected"
        (is (== 401 (:status (request app :post "/memories/search"
                                      {:accept "application/json" :body {:search "deploy"}})))))
      (finally (d/close conn)))))

(deftest handler-delete
  (let [conn (fresh-conn)
        app  (handler/app conn cfg (stat-writer/writer conn (:half-life-days cfg)))]
    (try
      (let [resp (request app :post "/memories"
                          {:user "alice" :accept "application/json"
                           :body {:content "x" :src "s" :tags [["domain" "clojure"]]}})
            id   (:id (body-json resp))]
        (testing "another user's delete returns 404"
          (is (== 404 (:status (request app :delete (str "/memories/" id)
                                        {:user "bob" :accept "application/json"})))))
        (testing "the owner's delete returns 200"
          (is (== 200 (:status (request app :delete (str "/memories/" id)
                                        {:user "alice" :accept "application/json"}))))))
      (finally (d/close conn)))))

(deftest handler-token-format
  (let [conn (fresh-conn)
        app  (handler/app conn cfg (stat-writer/writer conn (:half-life-days cfg)))]
    (try
      (testing "a malformed src returns 400 (coercion)"
        (is (== 400 (:status (request app :post "/memories"
                                      {:user "alice" :accept "application/json"
                                       :body {:content "x" :src "Bad_Src"
                                              :tags [["domain" "clojure"]]}})))))
      (testing "a malformed related returns 400 (coercion)"
        (is (== 400 (:status (request app :post "/memories"
                                      {:user "alice" :accept "application/json"
                                       :body {:content "x" :src "ok"
                                              :tags [["domain" "clojure"]]
                                              :related ["Bad!"]}})))))
      (finally (d/close conn)))))

(deftest handler-malformed-id
  (let [conn (fresh-conn)
        app  (handler/app conn cfg (stat-writer/writer conn (:half-life-days cfg)))]
    (try
      (testing "a non-uuid id on PUT is a coercion failure, returning 400"
        (is (== 400 (:status (request app :put "/memories/not-a-uuid"
                                      {:user "alice" :accept "application/json"
                                       :body {:content "x"}})))))
      (testing "a non-uuid id on DELETE is a coercion failure, returning 400"
        (is (== 400 (:status (request app :delete "/memories/not-a-uuid"
                                      {:user "alice" :accept "application/json"})))))
      (finally (d/close conn)))))

(deftest handler-malformed-user
  (let [conn (fresh-conn)
        app  (handler/app conn cfg (stat-writer/writer conn (:half-life-days cfg)))]
    (try
      (testing "a user id that is not a token is rejected with 400"
        (is (== 400 (:status (request app :get "/config"
                                      {:user "alice bob" :accept "application/json"})))))
      (testing "a well-formed user id is accepted"
        (is (== 200 (:status (request app :get "/config"
                                      {:user "alice" :accept "application/json"})))))
      (finally (d/close conn)))))

(deftest handler-batch
  (let [conn (fresh-conn)
        app  (handler/app conn cfg (stat-writer/writer conn (:half-life-days cfg)))]
    (try
      (testing "a grouped-map batch of creates returns 200 with ids and an applied count"
        (let [resp (request app :post "/memories/batch"
                            {:user "alice" :accept "application/json"
                             :body {:create [{:content "f1" :src "a" :tags [["domain" "clojure"]]}
                                             {:content "f2" :src "b" :tags [["domain" "clojure"]]}]}})
              body (body-json resp)]
          (is (== 200 (:status resp)))
          (is (== 2 (count (:ids body))))
          (is (== 2 (:applied body)))))
      (testing "a bad tag rejects the batch with 422 and the configurations on the failing entry"
        (let [resp (request app :post "/memories/batch"
                            {:user "alice" :accept "application/json"
                             :body {:create [{:content "bad" :src "c" :tags [["tech" "datalevin"]]}]}})
              body (body-json resp)]
          (is (== 422 (:status resp)))
          (is (= "create" (:op (first (:errors body)))))
          (is (== 0 (:i (first (:errors body)))))
          (is (= "no-configuration" (:error (first (:errors body)))))
          (is (some? (:configurations (first (:errors body)))))))
      (testing "an id in both update and delete rejects with 422 and conflict on the wire"
        (let [id   (:id (body-json (request app :post "/memories"
                                            {:user "alice" :accept "application/json"
                                             :body {:content "orig" :src "z" :tags [["domain" "clojure"]]}})))
              resp (request app :post "/memories/batch"
                            {:user "alice" :accept "application/json"
                             :body {:update [{:id id :content "after"}] :delete [id]}})
              body (body-json resp)]
          (is (== 422 (:status resp)))
          (is (= #{"conflict"} (set (map :error (:errors body)))))))
      (testing "an update of a missing id rejects with 422 and not-found on the wire"
        (let [resp (request app :post "/memories/batch"
                            {:user "alice" :accept "application/json"
                             :body {:update [{:id (str (java.util.UUID/randomUUID)) :content "x"}]}})
              body (body-json resp)]
          (is (== 422 (:status resp)))
          (is (= "not-found" (:error (first (:errors body)))))))
      (testing "an empty batch returns 200 with no ids"
        (let [resp (request app :post "/memories/batch"
                            {:user "alice" :accept "application/json" :body {}})
              body (body-json resp)]
          (is (== 200 (:status resp)))
          (is (= [] (:ids body)))
          (is (== 0 (:applied body)))))
      (testing "a create payload missing a required field is malformed and returns 400"
        (is (== 400 (:status (request app :post "/memories/batch"
                                      {:user "alice" :accept "application/json"
                                       :body {:create [{:content "x"}]}})))))
      (finally (d/close conn)))))

(deftest recall-json-fallback-shape
  (let [conn (fresh-conn)
        app  (handler/app conn cfg (stat-writer/writer conn (:half-life-days cfg)))]
    (try
      (request app :post "/memories"
               {:user "alice" :accept "application/json"
                :body {:content "prefer ==" :src "s" :tags [["domain" "clojure"]]}})
      (let [resp (request app :post "/memories/recall"
                          {:user "alice" :accept "application/json"
                           :body {:pairs [["domain" "clojure"]]}})
            body (body-json resp)]
        (testing "the JSON fallback returns pairs and memories"
          (is (== 200 (:status resp)))
          (is (vector? (:pairs body)))
          (is (vector? (:memories body))))
        (testing "each memory carries the wire keys, and an empty related is omitted"
          (let [m (first (:memories body))]
            (is (= #{:id :content :src :tags}
                   (set (keys m))))
            (is (not (contains? m :related))))))
      (finally (d/close conn)))))

(deftest handler-stats-carries-link-density
  (let [conn (fresh-conn)
        app  (handler/app conn cfg (stat-writer/writer conn (:half-life-days cfg)))]
    (try
      (memory/create! conn "alice" {:content "a" :src "a" :tags [["domain" "clojure"]] :related ["b"]})
      (memory/create! conn "alice" {:content "b" :src "b" :tags [["domain" "clojure"]]})
      (let [resp (request app :get "/stats" {:user "alice" :accept "application/json"})
            body (body-json resp)
            ld   (get-in body [:stats :link-density])]
        (testing "link density sits under :stats, and the recall rows are gone"
          (is (== 200 (:status resp)))
          (is (nil? (get-in body [:stats :recalls])))
          (is (map? ld)))
        (testing "the two metrics are present and numeric"
          (is (== 0.5 (:avg-out-degree ld)))           ; one edge a->b over two nodes
          (is (== 1.0 (:largest-wcc-fraction ld)))))   ; {a,b} is the whole graph
      (finally (d/close conn)))))

(deftest handler-stats-conforming-fraction
  (let [conn (fresh-conn)
        app  (handler/app conn cfg (stat-writer/writer conn (:half-life-days cfg)))]
    (try
      ;; two conforming, one not: 2/3 = 0.6666... truncated to four decimals
      (memory/create! conn "alice" {:content "c1" :src "c1" :tags [["domain" "clojure"]]})
      (memory/create! conn "alice" {:content "c2" :src "c2" :tags [["domain" "databases"]]})
      (memory/create! conn "alice" {:content "bad" :src "bad" :tags [["tech" "datalevin"]]})
      (let [body (body-json (request app :get "/stats" {:user "alice" :accept "application/json"}))
            f    (get-in body [:stats :conforming-fraction])]
        (testing "the stats body carries the conforming fraction, truncated to four decimals"
          (is (== 0.6666 f))))
      (finally (d/close conn)))))

(deftest handler-recalls-route
  (let [conn (fresh-conn)
        app  (handler/app conn cfg (stat-writer/writer conn (:half-life-days cfg)))]
    (try
      (memory/create! conn "alice" {:content "m1" :src "a" :tags [["domain" "clojure"]]})
      (memory/create! conn "alice" {:content "m2" :src "b" :tags [["domain" "clojure"] ["tech" "datalevin"]]})
      (memory/create! conn "bob"   {:content "secret" :src "z" :tags [["domain" "haskell"]]})
      (testing "a bare recalls map, no stats wrapper, five-element rows, user-scoped"
        (let [resp (request app :get "/recalls" {:user "alice" :accept "application/json"})
              body (body-json resp)]
          (is (== 200 (:status resp)))
          (is (vector? (:recalls body)))
          (is (nil? (:stats body)))
          (is (not (some #(= "haskell" (nth % 1)) (:recalls body))))      ; bob's label never appears
          (let [row (first (filter #(= ["domain" "clojure"] [(nth % 0) (nth % 1)]) (:recalls body)))]
            (is (== 5 (count row)))
            (is (== 2 (nth row 2))))))                                    ; count: two alice memories carry the pair
      (testing "a single-category filter returns only that category"
        (let [cats (->> (request app :get "/recalls" {:user "alice" :accept "application/json" :query "categories=tech"})
                        body-json :recalls (map first) set)]
          (is (= #{"tech"} cats))))
      (testing "a multi-category filter returns the union"
        (let [cats (->> (request app :get "/recalls" {:user "alice" :accept "application/json" :query "categories=domain,tech"})
                        body-json :recalls (map first) set)]
          (is (= #{"domain" "tech"} cats))))
      (testing "no filter returns every category"
        (let [cats (->> (request app :get "/recalls" {:user "alice" :accept "application/json"})
                        body-json :recalls (map first) set)]
          (is (= #{"domain" "tech"} cats))))
      (finally (d/close conn)))))

(deftest handler-config-carries-value-set
  (let [raw  {:half-life-days 14
              :configurations [{"scope" {:cardinality "?" :one-of ["global" "project"]} "domain" "+"}]}
        c    (assoc raw :tag-schema (config/compile-tag-schema raw))
        conn (fresh-conn)
        app  (handler/app conn c (stat-writer/writer conn 14))]
    (try
      (let [body  (body-json (request app :get "/config" {:user "alice" :accept "application/json"}))
            scope (get-in body [:configurations 0 :scope])]
        (testing "GET /config carries the map form for a value-set category"
          (is (= "?" (:cardinality scope)))
          (is (= ["global" "project"] (:one-of scope)))))
      (finally (d/close conn)))))

(deftest error-logging-and-correlation-id
  (let [conn (fresh-conn)
        app  (handler/app conn cfg (stat-writer/writer conn (:half-life-days cfg)))]
    (try
      (testing "every response carries a correlation-id header"
        (is (some? (get-in (request app :get "/healthz" {:accept "application/json"})
                           [:headers "X-Engram-Request-Id"]))))
      (testing "a handler error returns 500 with the correlation id in body and header"
        (with-redefs [memory/recall (fn [& _] (throw (ex-info "boom" {})))]
          (let [resp (request app :post "/memories/recall"
                              {:user "alice" :accept "application/json"
                               :body {:pairs [["domain" "clojure"]]}})
                body (body-json resp)]
            (is (== 500 (:status resp)))
            (is (some? (:id body)))
            (is (= (:id body) (get-in resp [:headers "X-Engram-Request-Id"]))))))
      (finally (d/close conn)))))

(deftest healthz-reports-the-version
  (let [conn (fresh-conn)
        app  (handler/app conn cfg (stat-writer/writer conn (:half-life-days cfg)))]
    (try
      (testing "/healthz returns the build version, dev in a source tree"
        (let [body (body-json (request app :get "/healthz" {:accept "application/json"}))]
          (is (= "ok" (:status body)))
          (is (= "dev" (:version body)))))
      (finally (d/close conn)))))
