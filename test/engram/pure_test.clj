(ns engram.pure-test
  "Pure tests: configuration and token validation, the positive-pair fold, and
  the stack-safe expr fold. These need no store, so the namespace has no fixture."
  (:require [clojure.test :refer [deftest is testing]]
            [engram.config :as config]
            [engram.errors :as errors]
            [engram.expr :as expr]
            [engram.freshness :as freshness]
            [engram.handler :as handler]
            [engram.memory :as memory]
            [engram.search-config :as search-config]
            [engram.stats :as stats]
            [engram.test-support :refer [cfg]]
            [malli.core :as m]))

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
        (is (= 14 (:recall-half-life-days
                   (config/load-config "deploy/engram-config.example.edn")))))))
  (testing "with ENGRAM_CONFIG set, load-config reads that path over default-path"
    (let [tmp (java.io.File/createTempFile "engram-cfg" ".edn")]
      (try
        (spit tmp (pr-str {:recall-half-life-days 99 :configurations [{"domain" "+"}]}))
        (with-redefs-fn {#'config/getenv (fn [k] (when (= k "ENGRAM_CONFIG") (.getPath tmp)))}
          (fn []
            (is (= 99 (:recall-half-life-days
                       (config/load-config "deploy/engram-config.example.edn"))))))
        (finally (.delete tmp))))))

(deftest config-categories-validation
  (let [base {:recall-half-life-days 14
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

;; ---------- the expr fold (ticket 22) ----------

(deftest positive-pairs-drops-negated-leaves
  (testing "positive leaves are recorded, a negated leaf is dropped, double negation restores"
    (is (= [["domain" "clojure"] ["tech" "datalevin"]]
           (vec (#'handler/positive-pairs {:and [["domain" "clojure"]
                                                 {:or [["tech" "datalevin"]]}
                                                 {:not ["tech" "sqlite"]}]}))))
    (is (= [["tech" "x"]]
           (vec (#'handler/positive-pairs {:not {:not ["tech" "x"]}}))))))

(deftest expr-transform-is-faithful-and-stack-safe
  (testing "a rebuild through transform returns the original expression"
    (let [rebuild (fn [e] (expr/transform identity
                                          (fn [op kids] (if (= :not op) {op (first kids)} {op (vec kids)}))
                                          e))]
      (doseq [s [["domain" "clojure"]
                 {:or [["tech" "datalevin"] ["tech" "reitit"]]}
                 {:and [["domain" "clojure"] {:or [["tech" "a"] ["tech" "b"]]} {:not ["tech" "sqlite"]}]}
                 "term"
                 {:phrase "alpha beta"}
                 {:and ["x" {:not {:phrase "y z"}}]}]]
        (is (= s (rebuild s))))))
  (testing "a pathologically deep expression folds without a stack overflow"
    (let [deep  (loop [e ["c" "l"] i 100000] (if (zero? i) e (recur {:not e} (dec i))))
          depth (fn [e] (loop [e e d 0] (if (expr/op-node? e) (recur (:not e) (inc d)) d)))
          rebuilt (expr/transform identity
                                  (fn [op kids] (if (= :not op) {op (first kids)} {op (vec kids)}))
                                  deep)]
      (is (= 100000 (depth rebuilt))))))

;; ---------- freshness aggregates over literal rows (ticket 15) ----------

(deftest aggregate-memories-computes-the-four-aggregates
  (let [now     1000000000000
        day     86400000
        d-ago   (fn [n] (java.util.Date. (- now (* n day))))
        tag     (fn [c l] {:tag/category c :tag/label l})
        reject? (fn [tags] (not (some (fn [[c _]] (= c "domain")) tags)))
        close?  (fn [x y] (< (Math/abs (double (- x y))) 1e-9))
        a {:memory/tag [(tag "domain" "clojure")] :memory/last-confirmed (d-ago 0)
           :memory/recall-decayed 1.0 :memory/last-recalled (d-ago 0)}
        b {:memory/tag [(tag "domain" "databases")] :memory/last-confirmed (d-ago 25)
           :memory/recall-decayed 2.0 :memory/last-recalled (d-ago 0)}
        c {:memory/last-confirmed (d-ago 5)}
        agg (stats/aggregate-memories [a b c] reject? 10 14 now)]
    (testing "the conforming fraction is conforming over total"
      (is (close? (/ 2.0 3.0) (:conforming-fraction agg))))
    (testing "the plain mean freshness weights every memory equally"
      (is (close? (/ (+ 1.0 (Math/pow 0.5 2.5) (Math/pow 0.5 0.5)) 3.0)
                  (:mean-freshness agg))))
    (testing "the use-weighted mean weights each memory by its recent recall count"
      (is (close? (/ (+ (* 1.0 1.0) (* 2.0 (Math/pow 0.5 2.5))) 3.0)
                  (:use-weighted-freshness agg))))
    (testing "the hot-and-stale fraction counts only memories both hot and stale"
      (is (close? (/ 1.0 3.0) (:hot-and-stale-fraction agg))))))

(deftest aggregate-memories-use-weighted-falls-back-to-plain-mean
  (let [now 1000000000000
        day 86400000
        m1  {:memory/last-confirmed (java.util.Date. (- now (* 5 day)))}
        m2  {:memory/last-confirmed (java.util.Date. (- now (* 15 day)))}
        agg (stats/aggregate-memories [m1 m2] (constantly nil) 10 14 now)]
    (testing "with no recent counts, the use-weighted mean equals the plain mean"
      (is (== (:mean-freshness agg) (:use-weighted-freshness agg))))))

(deftest aggregate-memories-empty-store-is-zero
  (testing "a caller with no memories gets 0.0 for every aggregate"
    (is (= {:conforming-fraction 0.0 :mean-freshness 0.0
            :use-weighted-freshness 0.0 :hot-and-stale-fraction 0.0}
           (stats/aggregate-memories [] (constantly nil) 10 14 1000000000000)))))

;; ---------- the re-index drift decision ----------

(deftest reindex-decision
  (let [full #{:memory/content :memory/src}]
    (testing "no drift: equal opts signature and unchanged full-text set skips re-index"
      (is (false? (boolean (search-config/reindex? search-config/engine-opts full full)))))
    (testing "a grown full-text set forces a re-index"
      (is (true? (boolean (search-config/reindex? search-config/engine-opts #{:memory/content} full)))))
    (testing "absent persisted opts (a fresh or pre-feature store) forces a re-index"
      (is (true? (boolean (search-config/reindex? nil full full)))))
    (testing "a changed index-structure flag forces a re-index"
      (is (true? (boolean (search-config/reindex? (assoc search-config/engine-opts :index-position? false)
                                                  full full)))))))

;; ---------- the freshness kernel and half-life config (ticket 14) ----------

(def ^:private day-ms 86400000)

(deftest freshness-kernel-value-and-band
  (testing "the value is 1.0 at age zero and halves at each half-life"
    (is (== 1.0 (freshness/value 30 0)))
    (is (== 0.5 (freshness/value 30 (* 30 day-ms)))))
  (testing "the band follows the half-life split, exact at the boundaries"
    (is (= "fresh" (freshness/band 30 0)))
    (is (= "fresh" (freshness/band 30 (* 29 day-ms))))
    (is (= "aging" (freshness/band 30 (* 30 day-ms))))     ; one half-life -> aging
    (is (= "aging" (freshness/band 30 (* 59 day-ms))))
    (is (= "stale" (freshness/band 30 (* 60 day-ms))))     ; two half-lives -> stale
    (is (= "stale" (freshness/band 30 (* 120 day-ms)))))
  (testing "stale? is true only in the stale band"
    (is (not (freshness/stale? 30 (* 59 day-ms))))
    (is (freshness/stale? 30 (* 60 day-ms)))
    (is (freshness/stale? 30 (* 120 day-ms)))))

(deftest half-life-defaults-and-validation
  (testing "load-config merges the half-life defaults when the file names neither"
    (let [tmp (java.io.File/createTempFile "engram-cfg" ".edn")]
      (try
        (spit tmp (pr-str {:configurations [{"domain" "+"}]}))
        (with-redefs-fn {#'config/getenv (fn [k] (when (= k "ENGRAM_CONFIG") (.getPath tmp)))}
          (fn []
            (let [c (config/load-config "deploy/engram-config.example.edn")]
              (is (== 14 (:recall-half-life-days c)))
              (is (== 30 (:freshness-half-life-days c))))))
        (finally (.delete tmp)))))
  (testing "a named half-life overrides its default"
    (let [tmp (java.io.File/createTempFile "engram-cfg" ".edn")]
      (try
        (spit tmp (pr-str {:recall-half-life-days 7 :freshness-half-life-days 90
                           :configurations [{"domain" "+"}]}))
        (with-redefs-fn {#'config/getenv (fn [k] (when (= k "ENGRAM_CONFIG") (.getPath tmp)))}
          (fn []
            (let [c (config/load-config "deploy/engram-config.example.edn")]
              (is (== 7 (:recall-half-life-days c)))
              (is (== 90 (:freshness-half-life-days c))))))
        (finally (.delete tmp)))))
  (testing "a non-positive half-life is rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (config/validate-config {:recall-half-life-days 0 :configurations [{"domain" "+"}]})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (config/validate-config {:freshness-half-life-days -1 :configurations [{"domain" "+"}]}))))
  (testing "a non-number half-life is rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (config/validate-config {:recall-half-life-days "x" :configurations [{"domain" "+"}]})))))

;; ---------- the age-ms kernel (ticket 16) ----------

(deftest age-ms-resolves-effective-last-confirmed
  (let [now 1000000000000
        d   (fn [back] (java.util.Date. (- now back)))]
    (testing "last-confirmed wins when present"
      (is (== 1000 (memory/age-ms {:memory/last-confirmed (d 1000)
                                   :memory/updated-at (d 5000)
                                   :memory/created-at (d 9000)} now))))
    (testing "updated-at is the fallback with no last-confirmed"
      (is (== 5000 (memory/age-ms {:memory/updated-at (d 5000)
                                   :memory/created-at (d 9000)} now))))
    (testing "created-at is the final fallback"
      (is (== 9000 (memory/age-ms {:memory/created-at (d 9000)} now))))))
