(ns engram.fresh-store-test
  "Tests that need a pristine or a destroyed store, so each opens its own fresh
  connection rather than share the `:once` store."
  (:require [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [engram.memory :as memory]
            [engram.migrations :as migrations]
            [engram.recall-count :as recall-count]
            [engram.recall-count.writer :as rc-writer]
            [engram.search-config :as search-config]
            [engram.system :as system]
            [engram.test-support :as ts]
            [syncopate.core :as sc])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftest recall-write-failure-is-isolated
  (let [conn (ts/fresh-conn)
        w    (rc-writer/writer conn (recall-count/->TagRecallCount 14))]
    (d/close conn)                                   ; every flush now fails
    ;; This test drives the failure path on purpose, so it logs one expected
    ;; flush-failed WARN on the writer's background thread. That one line is the
    ;; behavior under test, not the teardown spam the :once fixture removes.
    (testing "recording never throws to the caller when the write fails"
      (is (nil? (rc-writer/record-pairs! w "alice" [["domain" "clojure"]]))))
    (testing "draining a failing writer does not throw"
      (is (nil? (rc-writer/drain! w))))))

(deftest search-opts-persist-and-round-trip
  ;; Pins the Datalevin behavior the drift check relies on: our inter-fn
  ;; search-opts survive a close and a bare reopen. If a Datalevin upgrade breaks
  ;; this, system/migrated-conn would silently re-index on every boot, so this
  ;; test fails loudly instead.
  (let [dir (.toString (Files/createTempDirectory "engram-opts" (make-array FileAttribute 0)))]
    (let [c (d/get-conn dir {} search-config/conn-opts)]
      (sc/migrate-all! (sc/store c) migrations/migrations)
      (d/close c))
    (let [c (d/get-conn dir)]                                   ; bare reopen, exactly as the system does
      (try
        (testing "the persisted search-opts round-trip to an equal signature"
          (is (= (search-config/opts-signature search-config/engine-opts)
                 (search-config/opts-signature (:search-opts (d/opts c))))))
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
    (let [c (d/get-conn dir {} search-config/conn-opts)]                    ; reopen so the content engine is built
      (memory/create! c "alice" {:content "a short note" :src "engram-deploy"})
      (testing "before the src migration, a src-only word does not match"
        (is (empty? (memory/search c "alice" "engram" 10))))
      (d/close c))
    (let [conn (system/migrated-conn (d/get-conn dir) dir)]          ; applies 002 and backfills on drift
      (try
        (testing "after migrating and the drift re-index, the existing src is searchable by its words"
          (is (= #{"engram-deploy"} (set (map :src (memory/search conn "alice" "engram" 10))))))
        (finally (d/close conn))))))

(deftest copy-stat-rows-to-recall
  (testing "migration 006's copy step moves a :stat/* row onto :recall/* in place"
    (let [dir  (.toString (Files/createTempDirectory "engram-mig" (make-array FileAttribute 0)))
          conn (d/get-conn dir)]
      (try
        ;; The pre-006 shape (the :stat/* attrs) beside the new :recall/* attrs,
        ;; so the copy step has both schemas, as it does mid-migration.
        (d/update-schema conn
                         {:stat/user     {:db/valueType :db.type/string}
                          :stat/category {:db/valueType :db.type/string}
                          :stat/label    {:db/valueType :db.type/string}
                          :stat/lifetime {:db/valueType :db.type/long}
                          :stat/decayed  {:db/valueType :db.type/double}
                          :stat/last-request {:db/valueType :db.type/instant}
                          :recall/user     {:db/valueType :db.type/string}
                          :recall/category {:db/valueType :db.type/string}
                          :recall/label    {:db/valueType :db.type/string}
                          :recall/user+category+label {:db/tupleAttrs [:recall/user :recall/category :recall/label]
                                                       :db/unique :db.unique/identity}
                          :recall/lifetime {:db/valueType :db.type/long}
                          :recall/decayed  {:db/valueType :db.type/double}
                          :recall/last-recalled {:db/valueType :db.type/instant}})
        (let [d0 (java.util.Date.)]
          (d/transact! conn [{:stat/user "alice" :stat/category "domain" :stat/label "clojure"
                              :stat/lifetime 7 :stat/decayed 3.5 :stat/last-request d0}
                             {:stat/user "bob" :stat/category "tech" :stat/label "datalevin"
                              :stat/lifetime 2 :stat/decayed 1.0 :stat/last-request d0}])
          (migrations/copy-stat->recall conn)
          (testing "every stored row's values land on :recall/*, last-request as last-recalled"
            (is (= [7 3.5 d0]
                   (d/q '[:find [?life ?dec ?last] :in $ ?u ?c ?l
                          :where [?s :recall/user ?u] [?s :recall/category ?c] [?s :recall/label ?l]
                                 [?s :recall/lifetime ?life] [?s :recall/decayed ?dec]
                                 [?s :recall/last-recalled ?last]]
                        (d/db conn) "alice" "domain" "clojure"))))
          (testing "the identity tuple rebuilds, so the row is addressable by it"
            (is (some? (d/q '[:find ?s . :in $ ?t :where [?s :recall/user+category+label ?t]]
                            (d/db conn) ["bob" "tech" "datalevin"])))))
        (finally (d/close conn))))))
