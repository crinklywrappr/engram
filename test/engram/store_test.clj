(ns engram.store-test
  "The store-driven suite: memory recall and writes, the batch, the by-tags match
  expression, and the memory index. One `:once` store, shared by every test
  through per-user isolation. Concerns are grouped by section comment, not by a
  separate namespace, so the suite pays one migration and one re-index."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [engram.config :as config]
            [engram.errors :as errors]
            [engram.handler :as handler]
            [engram.memory :as memory]
            [engram.recall-count :as recall-count]
            [engram.recall-count.writer :as rc-writer]
            [engram.short-term :as short-term]
            [engram.stats :as stats]
            [engram.test-support :as ts :refer [request body-json create-tag-err update-tag-err]]
            [jsonista.core :as json]
            [taoensso.telemere :as t]))

(use-fixtures :once ts/store-fixture)

;; ---------- memory: recall, transitive closure, fetch, delete ----------

(deftest transitive-recall-and-isolation
  (let [conn ts/*conn* u "m-trans" other "m-trans-bob"]
    (memory/create! conn u {:content "prefer ==" :src "m-trans-a"
                            :tags [["domain" "clojure"]] :related ["m-trans-b"]})
    (memory/create! conn u {:content "uses datalevin" :src "m-trans-b"
                            :tags [["tech" "datalevin"]]})
    (memory/create! conn other {:content "secret" :src "m-trans-z"
                                :tags [["domain" "clojure"]]})
    (let [rows (memory/recall-by-match conn u ["domain" "clojure"] 30)
          srcs (set (map :src rows))]
      (testing "the pair match is returned"
        (is (contains? srcs "m-trans-a")))
      (testing "the transitive related-by-src memory is pulled in"
        (is (contains? srcs "m-trans-b")))
      (testing "another user's memory is never returned"
        (is (not (contains? srcs "m-trans-z")))
        (is (not (some #(= "secret" (:content %)) rows)))))))

(deftest recall-traversal-shapes
  (let [conn ts/*conn*]
    (testing "a cycle terminates and returns both nodes"
      (let [u "m-cycle"]
        (memory/create! conn u {:content "c1" :src "c1" :tags [["domain" "clojure"]] :related ["c2"]})
        (memory/create! conn u {:content "c2" :src "c2" :tags [["misc" "m"]] :related ["c1"]})
        (is (= #{"c1" "c2"} (set (map :src (memory/recall-by-match conn u ["domain" "clojure"] 30)))))))
    (testing "a diamond returns the shared child once"
      (let [u "m-diamond"]
        (memory/create! conn u {:content "m1" :src "m1" :tags [["domain" "clojure"]] :related ["x"]})
        (memory/create! conn u {:content "m2" :src "m2" :tags [["domain" "clojure"]] :related ["x"]})
        (memory/create! conn u {:content "x" :src "x" :tags [["misc" "m"]]})
        (let [srcs (map :src (memory/recall-by-match conn u ["domain" "clojure"] 30))]
          (is (= #{"m1" "m2" "x"} (set srcs)))
          (is (== 1 (count (filter #{"x"} srcs)))))))
    (testing "a self-loop returns the memory once and terminates"
      (let [u "m-selfloop"]
        (memory/create! conn u {:content "s" :src "s" :tags [["domain" "clojure"]] :related ["s"]})
        (is (= ["s"] (map :src (memory/recall-by-match conn u ["domain" "clojure"] 30))))))
    (testing "a related pointing at a nonexistent src yields nothing extra"
      (let [u "m-ghost"]
        (memory/create! conn u {:content "g" :src "g" :tags [["domain" "clojure"]] :related ["ghost"]})
        (is (= #{"g"} (set (map :src (memory/recall-by-match conn u ["domain" "clojure"] 30)))))))))

(deftest recall-by-ids-selects-and-follows-closure
  (let [conn ts/*conn* u "m-ids" other "m-ids-bob"]
    (let [a (memory/create! conn u {:content "fact a" :src "m-ids-a"
                                    :tags [["domain" "clojure"]] :related ["m-ids-b"]})
          _ (memory/create! conn u {:content "fact b" :src "m-ids-b" :tags [["tech" "datalevin"]]})
          c (memory/create! conn u {:content "fact c" :src "m-ids-c" :tags [["domain" "clojure"]]})
          z (memory/create! conn other {:content "secret" :src "m-ids-z" :tags [["domain" "clojure"]]})]
      (testing "a selected id returns, and its related closure is pulled in"
        (is (= #{"m-ids-a" "m-ids-b"} (set (map :src (memory/recall-by-ids conn u [a] 30))))))
      (testing "a foreign id is skipped silently"
        (is (empty? (memory/recall-by-ids conn u [z] 30))))
      (testing "a missing id is skipped silently"
        (is (empty? (memory/recall-by-ids conn u [(str (java.util.UUID/randomUUID))] 30))))
      (testing "a duplicate id collapses to one memory"
        (is (= ["m-ids-c"] (map :src (memory/recall-by-ids conn u [c c] 30)))))
      (testing "an empty id list selects nothing"
        (is (empty? (memory/recall-by-ids conn u [] 30))))
      (testing "each row carries the recall wire shape, no score"
        (let [m (first (filter #(= "m-ids-a" (:src %)) (memory/recall-by-ids conn u [a] 30)))]
          (is (= #{:id :content :src :tags :related :freshness} (set (keys m))))))
      (testing "a mix of own, foreign, and missing returns only the owned closure"
        (let [srcs (set (map :src (memory/recall-by-ids conn u
                                                        [a z (str (java.util.UUID/randomUUID))] 30)))]
          (is (= #{"m-ids-a" "m-ids-b"} srcs)))))))

(deftest all-memories-returns-every-owned-memory
  (let [conn ts/*conn* u "m-all" other "m-all-bob"]
    (memory/create! conn u {:content "a1" :src "m-all-a" :tags [["domain" "clojure"]] :related ["m-all-b"]})
    (memory/create! conn u {:content "a2" :src "m-all-b" :tags [["tech" "datalevin"]]})
    (memory/create! conn other {:content "b1" :src "m-all-z" :tags [["domain" "clojure"]]})
    (let [rows (memory/all-memories conn u 30)
          srcs (set (map :src rows))]
      (testing "the function returns a lazy sequence"
        (is (instance? clojure.lang.LazySeq rows)))
      (testing "every memory the user owns appears"
        (is (= #{"m-all-a" "m-all-b"} srcs)))
      (testing "another user's memory never appears"
        (is (not (contains? srcs "m-all-z"))))
      (testing "each row carries the recall wire shape"
        (let [m (first (filter #(= "m-all-a" (:src %)) rows))]
          (is (= #{:id :content :src :tags :related :freshness} (set (keys m)))))))))

(deftest nonconforming-returns-only-failing-memories
  (let [conn ts/*conn* u "m-nc" other "m-nc-bob"
        reject? #(config/tag-error (:tag-schema ts/cfg) %)]
    (memory/create! conn u {:content "ok" :src "m-nc-ok" :tags [["domain" "clojure"]]})
    (memory/create! conn u {:content "bad-tag" :src "m-nc-bad" :tags [["tech" "datalevin"]]})
    (memory/create! conn u {:content "no-tags" :src "m-nc-none"})
    (memory/create! conn other {:content "other-bad" :src "m-nc-zz" :tags [["tech" "datalevin"]]})
    (let [rows (memory/nonconforming conn u reject? 30)
          srcs (set (map :src rows))]
      (testing "the function returns a lazy sequence"
        (is (instance? clojure.lang.LazySeq rows)))
      (testing "a conforming memory never appears"
        (is (not (contains? srcs "m-nc-ok"))))
      (testing "a memory whose tags match no configuration appears"
        (is (contains? srcs "m-nc-bad")))
      (testing "a memory with no tags appears"
        (is (contains? srcs "m-nc-none")))
      (testing "another user's nonconforming memory never appears"
        (is (not (contains? srcs "m-nc-zz")))))))

(deftest fetch-returns-owned-memory
  (let [conn ts/*conn* u "m-fetch" other "m-fetch-bob"]
    (let [id (memory/create! conn u {:content "x" :src "m-fetch-s"
                                     :tags [["domain" "clojure"]] :related ["r"]})]
      (testing "the owner gets the wire memory by id"
        (let [m (memory/fetch conn u id 30)]
          (is (= id (:id m)))
          (is (= "x" (:content m)))
          (is (= "m-fetch-s" (:src m)))
          (is (= [["domain" "clojure"]] (:tags m)))
          (is (= ["r"] (:related m)))))
      (testing "another user gets nil"
        (is (nil? (memory/fetch conn other id 30))))
      (testing "a missing id gets nil"
        (is (nil? (memory/fetch conn u (str (java.util.UUID/randomUUID)) 30)))))))

(deftest delete-removes-memory
  (let [conn ts/*conn* u "m-del"]
    (let [id (memory/create! conn u {:content "temp" :src "m-del-s"
                                     :tags [["domain" "clojure"]]})]
      (testing "the memory is present before the delete"
        (is (== 1 (count (memory/recall-by-match conn u ["domain" "clojure"] 30)))))
      (testing "delete removes it and returns the id"
        (is (= id (memory/delete! conn u id)))
        (is (empty? (memory/recall-by-match conn u ["domain" "clojure"] 30)))))))

(deftest delete-missing-and-isolation
  (let [conn ts/*conn* u "m-delmiss" other "m-delmiss-bob"]
    (testing "deleting a missing id returns nil"
      (is (nil? (memory/delete! conn u (str (java.util.UUID/randomUUID))))))
    (let [id (memory/create! conn u {:content "owner only" :src "m-delmiss-s"
                                     :tags [["domain" "clojure"]]})]
      (testing "another user cannot delete it, and it survives"
        (is (nil? (memory/delete! conn other id)))
        (is (= 1 (count (memory/recall-by-match conn u ["domain" "clojure"] 30))))))))

;; ---------- by-tags match expression (ticket 22) ----------

(deftest recall-by-match-boolean
  (let [conn ts/*conn* u "mx-bool" other "mx-bool-other"]
    (memory/create! conn u {:content "m1" :src "mx-m1" :tags [["domain" "clojure"] ["tech" "datalevin"]]})
    (memory/create! conn u {:content "m2" :src "mx-m2" :tags [["domain" "clojure"] ["tech" "reitit"]]})
    (memory/create! conn u {:content "m3" :src "mx-m3" :tags [["domain" "clojure"] ["tech" "sqlite"]]})
    (memory/create! conn u {:content "m5" :src "mx-m5" :tags [["tech" "datalevin"]]})
    (memory/create! conn other {:content "zz" :src "mx-zz" :tags [["domain" "clojure"] ["tech" "datalevin"]]})
    (let [srcs (fn [expr] (set (map :src (memory/recall-by-match conn u expr 30))))]
      (testing "a bare leaf matches its pair"
        (is (= #{"mx-m1" "mx-m2" "mx-m3"} (srcs ["domain" "clojure"]))))
      (testing "and intersects, or unions, not subtracts within the user"
        (is (= #{"mx-m1" "mx-m2"}
               (srcs {:and [["domain" "clojure"]
                            {:or [["tech" "datalevin"] ["tech" "reitit"]]}
                            {:not ["tech" "sqlite"]}]}))))
      (testing "a top-level not subtracts from the caller's own memories"
        (is (= #{"mx-m1" "mx-m2" "mx-m5"} (srcs {:not ["tech" "sqlite"]}))))
      (testing "double negation returns the positive set"
        (is (= (srcs ["tech" "datalevin"]) (srcs {:not {:not ["tech" "datalevin"]}}))))
      (testing "another user's memory never matches"
        (is (not (contains? (srcs ["domain" "clojure"]) "mx-zz")))))))

(deftest recall-by-match-follows-related
  (let [conn ts/*conn* u "mx-rel"]
    (memory/create! conn u {:content "a" :src "mx-rel-a" :tags [["domain" "clojure"]] :related ["mx-rel-b"]})
    (memory/create! conn u {:content "b" :src "mx-rel-b" :tags [["tech" "datalevin"]]})
    (testing "the match set seeds the related-by-src closure"
      (is (= #{"mx-rel-a" "mx-rel-b"} (set (map :src (memory/recall-by-match conn u ["domain" "clojure"] 30))))))))

(deftest recall-by-match-route-and-validation
  (let [u "mx-route"]
    (memory/create! ts/*conn* u {:content "m1" :src "mx-route-m1" :tags [["domain" "clojure"] ["tech" "datalevin"]]})
    (testing "a nested match over the route returns the matches as JSON"
      (let [body (body-json (request :post "/memories/recall/by-tags"
                                     {:user u :accept "application/json"
                                      :body {:match {:and [["domain" "clojure"] {:not ["tech" "sqlite"]}]}}}))]
        (is (= #{"mx-route-m1"} (set (map :src (:memories body)))))))
    (testing "a zero-argument and is a 400"
      (is (== 400 (:status (request :post "/memories/recall/by-tags"
                                    {:user u :body {:match {:and []}}})))))
    (testing "a not with a non-expression child is a 400"
      (is (== 400 (:status (request :post "/memories/recall/by-tags"
                                    {:user u :body {:match {:not [["a" "b"] ["c" "d"]]}}})))))
    (testing "a missing match is a 400"
      (is (== 400 (:status (request :post "/memories/recall/by-tags"
                                    {:user u :body {}})))))))

;; ---------- memory batch ----------

(deftest recalls-excludes-a-pair-no-live-memory-carries
  (let [conn ts/*conn* u "b-excl"
        lists? (fn [cat lab]
                 (boolean (some #(= [cat lab] [(:category %) (:label %)])
                                (recall-count/catalog conn u 14))))]
    (let [m (memory/create! conn u {:content "m" :src "b-excl-s" :tags [["domain" "clojure"]]})]
      (ts/seed-recall! conn u 14 [["domain" "clojure"]])
      (testing "while a live memory carries the pair, the catalog lists it"
        (is (lists? "domain" "clojure")))
      (memory/delete! conn u m)
      (testing "after the last memory with the pair is deleted, the catalog drops it"
        (is (not (lists? "domain" "clojure")))))
    (let [m2 (memory/create! conn u {:content "m2" :src "b-excl-s2" :tags [["domain" "clojure"]]})]
      (ts/seed-recall! conn u 14 [["domain" "clojure"]])
      (memory/update! conn u m2 {:tags [["domain" "datalevin"]]})
      (testing "after the last use of a pair is retagged away, the catalog drops it"
        (is (not (lists? "domain" "clojure")))
        (is (lists? "domain" "datalevin"))))))

(deftest batch-all-pass
  (let [conn ts/*conn* u "b-pass"
        x   (memory/create! conn u {:content "orig-x" :src "b-pass-x" :tags [["domain" "clojure"]]})
        y   (memory/create! conn u {:content "orig-y" :src "b-pass-y" :tags [["domain" "clojure"]]})
        res (memory/apply-batch!
             conn u
             {:create [{:content "c-a" :src "b-pass-a" :tags [["domain" "clojure"]]}]
              :update [{:id x :content "new-x"}]
              :delete [y]}
             create-tag-err update-tag-err)]
    (testing "the batch reports success, one new id, and three memories written"
      (is (true? (:ok? res)))
      (is (== 1 (count (:ids res))))
      (is (== 3 (:applied res))))
    (let [by-src (into {} (map (juxt :src identity)
                               (memory/recall-by-match conn u ["domain" "clojure"] 30)))]
      (testing "the create landed and the update changed content in place"
        (is (contains? by-src "b-pass-a"))
        (is (= "new-x" (:content (by-src "b-pass-x")))))
      (testing "the delete removed its memory"
        (is (not (contains? by-src "b-pass-y")))))))

(deftest batch-bad-tag-writes-nothing
  (let [conn ts/*conn* u "b-badtag"
        res (memory/apply-batch!
             conn u
             {:create [{:content "ok" :src "b-badtag-a" :tags [["domain" "clojure"]]}
                       {:content "bad" :src "b-badtag-b" :tags [["tech" "datalevin"]]}]}
             create-tag-err update-tag-err)]
    (testing "the batch is rejected and names only the failing op by its group index"
      (is (false? (:ok? res)))
      (is (= [{:op "create" :i 1}] (map #(select-keys % [:op :i]) (:errors res))))
      (is (= errors/no-configuration (:code (first (:errors res))))))
    (testing "nothing was written, not even the valid create"
      (is (empty? (memory/recall-by-match conn u ["domain" "clojure"] 30))))))

(deftest batch-missing-id-and-isolation
  (let [conn ts/*conn* u "b-missing" other "b-missing-bob"]
    (testing "an update and a delete of a missing id both fail as not-found"
      (let [res (memory/apply-batch!
                 conn u
                 {:update [{:id (str (java.util.UUID/randomUUID)) :content "x"}]
                  :delete [(str (java.util.UUID/randomUUID))]}
                 create-tag-err update-tag-err)]
        (is (false? (:ok? res)))
        (is (= #{{:op "update" :i 0} {:op "delete" :i 0}}
               (set (map #(select-keys % [:op :i]) (:errors res)))))
        (is (= #{errors/not-found} (set (map :code (:errors res)))))))
    (let [x (memory/create! conn u {:content "owner only" :src "b-missing-x" :tags [["domain" "clojure"]]})]
      (testing "another user cannot delete it, and it survives"
        (let [res (memory/apply-batch! conn other {:delete [x]} create-tag-err update-tag-err)]
          (is (false? (:ok? res)))
          (is (= errors/not-found (:code (first (:errors res))))))
        (is (== 1 (count (memory/recall-by-match conn u ["domain" "clojure"] 30))))))))

(deftest batch-cumulative-update
  (let [conn ts/*conn* u "b-cumul"
        x   (memory/create! conn u {:content "orig" :src "b-cumul-x" :tags [["domain" "clojure"]]})
        res (memory/apply-batch!
             conn u
             {:update [{:id x :content "c2"} {:id x :related ["y"]}]}
             create-tag-err update-tag-err)]
    (testing "several updates to one id fold cumulatively per field, writing one memory"
      (is (true? (:ok? res)))
      (is (== 1 (:applied res)))
      (let [m (first (memory/recall-by-match conn u ["domain" "clojure"] 30))]
        (is (= "c2" (:content m)))
        (is (= ["y"] (:related m)))))))

(deftest batch-update-delete-conflict
  (let [conn ts/*conn* u "b-conflict"
        x   (memory/create! conn u {:content "orig" :src "b-conflict-x" :tags [["domain" "clojure"]]})
        res (memory/apply-batch!
             conn u
             {:update [{:id x :content "after"}] :delete [x]}
             create-tag-err update-tag-err)]
    (testing "an id in both update and delete flags both ops and writes nothing"
      (is (false? (:ok? res)))
      (is (= #{{:op "update" :i 0} {:op "delete" :i 0}}
             (set (map #(select-keys % [:op :i]) (:errors res)))))
      (is (= #{errors/conflict} (set (map :code (:errors res)))))
      (is (= "orig" (:content (first (memory/recall-by-match conn u ["domain" "clojure"] 30))))))))

(deftest batch-duplicate-delete-tolerated
  (let [conn ts/*conn* u "b-dupdel"
        x   (memory/create! conn u {:content "orig" :src "b-dupdel-x" :tags [["domain" "clojure"]]})
        res (memory/apply-batch! conn u {:delete [x x]} create-tag-err update-tag-err)]
    (testing "a repeated delete id is deduplicated, deletes once, no error"
      (is (true? (:ok? res)))
      (is (== 1 (:applied res)))
      (is (empty? (memory/recall-by-match conn u ["domain" "clojure"] 30))))))

(deftest plan-batch-is-pure
  (let [conn ts/*conn* u "b-plan"
        x    (memory/create! conn u {:content "orig" :src "b-plan-x" :tags [["domain" "clojure"]]})
        db   (d/db conn)
        plan (memory/plan-batch db u
                                {:create [{:content "c-a" :src "b-plan-a" :tags [["domain" "clojure"]]}]
                                 :update [{:id x :content "new-x"}]}
                                create-tag-err update-tag-err
                                [(java.util.UUID/randomUUID)])]
    (testing "the planner returns a plan with ids, tx-data, and an applied count"
      (is (true? (:ok? plan)))
      (is (== 1 (count (:ids plan))))
      (is (seq (:tx-data plan)))
      (is (== 2 (:applied plan))))
    (testing "the planner writes nothing: the seed memory is unchanged and alone"
      (is (= "orig" (:content (first (memory/recall-by-match conn u ["domain" "clojure"] 30)))))
      (is (== 1 (count (memory/recall-by-match conn u ["domain" "clojure"] 30)))))))

(deftest plan-batch-reports-errors-without-tx-data
  (let [conn ts/*conn* u "b-planerr"
        plan (memory/plan-batch (d/db conn) u
                                {:update [{:id (str (java.util.UUID/randomUUID)) :content "x"}]}
                                create-tag-err update-tag-err
                                [])]
    (testing "a failing plan carries only the failing op and no tx-data"
      (is (false? (:ok? plan)))
      (is (= errors/not-found (:code (first (:errors plan)))))
      (is (nil? (:tx-data plan))))))

(deftest batch-empty
  (let [conn ts/*conn* u "b-empty"]
    (doseq [b [{} {:create [] :update [] :delete []}]]
      (let [res (memory/apply-batch! conn u b create-tag-err update-tag-err)]
        (testing "an empty batch succeeds with no ids and applied 0"
          (is (true? (:ok? res)))
          (is (= [] (:ids res)))
          (is (== 0 (:applied res))))))))

;; ---------- memory index (ticket 25) ----------

(defn- index-entry [resp] (:entries (body-json resp)))

(deftest index-crud-round-trip
  (let [u "ix-crud"]
    (testing "an empty index reads as no entries"
      (let [resp (request :get "/index" {:user u :accept "application/json"})]
        (is (= 200 (:status resp)))
        (is (= [] (index-entry resp)))))
    (let [id (:id (body-json
                   (request :post "/index"
                            {:user u :accept "application/json"
                             :body {:title "engram-work" :hook "the engram project"
                                    :requests [{:kind "by-tags" :payload ["domain" "clojure"]}]}})))]
      (testing "a create returns a server-minted id"
        (is (string? id)))
      (testing "a read returns the created entry"
        (let [entries (index-entry (request :get "/index" {:user u :accept "application/json"}))
              e       (first entries)]
          (is (= 1 (count entries)))
          (is (= id (:id e)))
          (is (= "engram-work" (:title e)))
          (is (= "the engram project" (:hook e)))
          (is (= [{:kind "by-tags" :payload ["domain" "clojure"] :required false}] (:requests e)))))
      (testing "a put replaces the entry in place, keeping the id"
        (let [resp (request :put (str "/index/" id)
                            {:user u :accept "application/json"
                             :body {:title "engram-recall" :hook "recall backbone"
                                    :requests [{:kind "thought-process" :payload "engram-decompress" :required false}]}})]
          (is (= 200 (:status resp)))
          (let [e (first (index-entry (request :get "/index" {:user u :accept "application/json"})))]
            (is (= id (:id e)))
            (is (= "engram-recall" (:title e)))
            (is (= [{:kind "thought-process" :payload "engram-decompress" :required false}] (:requests e))))))
      (testing "a delete removes the entry"
        (is (= 200 (:status (request :delete (str "/index/" id) {:user u :accept "application/json"}))))
        (is (= [] (index-entry (request :get "/index" {:user u :accept "application/json"}))))))))

(deftest index-is-per-user-isolated
  (let [owner "ix-iso-a" other "ix-iso-b"]
    (let [id (:id (body-json
                   (request :post "/index"
                            {:user owner :accept "application/json"
                             :body {:title "alice-work" :hook "hers"
                                    :requests [{:kind "by-tags" :payload ["domain" "clojure"]}]}})))]
      (testing "another user never reads the first user's index"
        (is (= [] (index-entry (request :get "/index" {:user other :accept "application/json"})))))
      (testing "a put against another user's id is a 404"
        (is (= 404 (:status (request :put (str "/index/" id)
                                     {:user other :accept "application/json"
                                      :body {:title "bob-work" :hook "his"
                                             :requests [{:kind "by-tags" :payload ["domain" "clojure"]}]}})))))
      (testing "a delete against another user's id is a 404"
        (is (= 404 (:status (request :delete (str "/index/" id) {:user other :accept "application/json"})))))
      (testing "the first user's entry is untouched"
        (is (= 1 (count (index-entry (request :get "/index" {:user owner :accept "application/json"})))))))))

(deftest index-reaps-a-lapsed-required-request
  (let [u "ix-reap"]
    (let [mem (:id (body-json (request :post "/memories"
                                       {:user u :accept "application/json"
                                        :body {:content "a fact" :src "s1"
                                               :tags [["domain" "clojure"]]}})))]
      (request :post "/index"
               {:user u :accept "application/json"
                :body {:title "live-entry" :hook "probes a real pair"
                       :requests [{:kind "by-tags" :payload ["domain" "clojure"] :required true}]}})
      (testing "the entry lives while its required request still matches"
        (is (= 1 (count (index-entry (request :get "/index" {:user u :accept "application/json"}))))))
      (request :delete (str "/memories/" mem) {:user u :accept "application/json"})
      (testing "the entry is reaped once its required request matches nothing"
        (is (= [] (index-entry (request :get "/index" {:user u :accept "application/json"}))))))))

(deftest index-without-a-required-request-survives
  (let [u "ix-sticky"]
    (request :post "/index"
             {:user u :accept "application/json"
              :body {:title "sticky-entry" :hook "no liveness probe"
                     :requests [{:kind "by-tags" :payload ["domain" "clojure"]}]}})
    (testing "an entry with no required request survives a read even when nothing matches"
      (is (= 1 (count (index-entry (request :get "/index" {:user u :accept "application/json"}))))))))

(deftest index-probes-every-request-kind
  (let [u "ix-probe"]
    (let [mem (:id (body-json (request :post "/memories"
                                       {:user u :accept "application/json"
                                        :body {:content "a fact" :src "s1"
                                               :tags [["domain" "clojure"]]}})))]
      (short-term/create! ts/*conn* u "engram-work" "a staged note")
      (request :post "/index"
               {:user u :accept "application/json"
                :body {:title "by-src-entry" :hook "probes a source"
                       :requests [{:kind "by-src" :payload ["s1"] :required true}]}})
      (request :post "/index"
               {:user u :accept "application/json"
                :body {:title "process-entry" :hook "probes a bucket"
                       :requests [{:kind "thought-process" :payload "engram-work" :required true}]}})
      (testing "a by-src and a thought-process required request both live when their target exists"
        (is (= 2 (count (index-entry (request :get "/index" {:user u :accept "application/json"}))))))
      (request :delete (str "/memories/" mem) {:user u :accept "application/json"})
      (short-term/wipe! ts/*conn* u "engram-work")
      (testing "both lapse once their targets are gone"
        (is (= [] (index-entry (request :get "/index" {:user u :accept "application/json"}))))))))

(deftest index-existence-helpers
  (let [u "ix-exist" other "ix-exist-b"
        conn ts/*conn*]
    (memory/create! conn u {:content "a" :src "ix-exist-s1" :tags [["domain" "clojure"]]})
    (memory/create! conn u {:content "b" :src "ix-exist-s2" :tags [["tech" "datalevin"]]})
    (let [db (d/db conn)]
      (testing "any-match? is existence over the match compiler"
        (is (true?  (memory/any-match? db u ["domain" "clojure"])))
        (is (false? (memory/any-match? db u ["domain" "web"])))
        (is (true?  (memory/any-match? db u {:or [["domain" "web"] ["tech" "datalevin"]]})))
        (is (false? (memory/any-match? db other ["domain" "clojure"]))))
      (testing "owns-any-src? is existence over the caller's own sources"
        (is (true?  (memory/owns-any-src? db u ["ix-exist-s1"])))
        (is (true?  (memory/owns-any-src? db u ["ix-exist-s2"])))
        (is (false? (memory/owns-any-src? db u ["absent-src"])))
        (is (false? (memory/owns-any-src? db other ["ix-exist-s1"])))
        (is (false? (memory/owns-any-src? db u []))))
      (testing "bucket-has-note? is existence over a thought-process"
        (short-term/create! conn u "ix-exist-bucket" "note")
        (is (true?  (short-term/bucket-has-note? (d/db conn) u "ix-exist-bucket")))
        (is (false? (short-term/bucket-has-note? (d/db conn) u "absent-bucket")))))))

;; ---------- index batch (ticket 25d) ----------

(deftest index-batch-crud
  (let [u "ixb-crud"
        mk   (fn [title] (:id (body-json (request :post "/index"
                                          {:user u :accept "application/json"
                                           :body {:title title :hook "h"
                                                  :requests [{:kind "by-src" :payload ["s1"]}]}}))))
        a    (mk "alpha")
        b    (mk "beta")
        resp (request :post "/index/batch"
                      {:user u :accept "application/json"
                       :body {:create [{:title "gamma" :hook "g"
                                        :requests [{:kind "by-tags" :payload ["domain" "clojure"]}]}]
                              :update [{:id a :title "alpha-2" :hook "h2"
                                        :requests [{:kind "thought-process" :payload "proc" :required false}]}]
                              :delete [b]}})
        body (body-json resp)]
    (testing "a mixed batch returns 200 with one new id and the applied count"
      (is (== 200 (:status resp)))
      (is (= 1 (count (:ids body))))
      (is (= 3 (:applied body))))
    (let [entries  (index-entry (request :get "/index" {:user u :accept "application/json"}))
          by-title (into {} (map (juxt :title identity)) entries)]
      (testing "create added gamma, update replaced alpha in place, delete removed beta"
        (is (= #{"alpha-2" "gamma"} (set (keys by-title))))
        (is (= [{:kind "thought-process" :payload "proc" :required false}]
               (:requests (by-title "alpha-2"))))))))

(deftest index-batch-rejects-bad-ids
  (let [u "ixb-bad"
        a   (:id (body-json (request :post "/index"
                             {:user u :accept "application/json"
                              :body {:title "alpha" :hook "h"
                                     :requests [{:kind "by-src" :payload ["s1"]}]}})))
        upd (fn [id] {:id id :title "x" :hook "h" :requests [{:kind "by-src" :payload ["s1"]}]})]
    (testing "an id in both update and delete is a conflict"
      (let [resp (request :post "/index/batch"
                          {:user u :accept "application/json"
                           :body {:update [(upd a)] :delete [a]}})]
        (is (== 422 (:status resp)))
        (is (some #(= "conflict" (:error %)) (:errors (body-json resp))))))
    (testing "a repeated update id is a duplicate-id on every occurrence"
      (let [resp (request :post "/index/batch"
                          {:user u :accept "application/json"
                           :body {:update [(upd a) (upd a)]}})
            errs (:errors (body-json resp))]
        (is (== 422 (:status resp)))
        (is (= #{0 1} (set (map :i (filter #(= "duplicate-id" (:error %)) errs)))))))
    (testing "the entry is untouched after the rejected batches"
      (let [entries (index-entry (request :get "/index" {:user u :accept "application/json"}))]
        (is (= ["alpha"] (map :title entries)))))))

(deftest index-batch-not-found-and-isolation
  (let [u "ixb-nf" owner "ixb-nf-owner"
        a     (:id (body-json (request :post "/index"
                      {:user owner :accept "application/json"
                       :body {:title "bobs" :hook "h" :requests [{:kind "by-src" :payload ["s1"]}]}})))
        ghost (str (java.util.UUID/randomUUID))
        upd   {:id a :title "x" :hook "h" :requests [{:kind "by-src" :payload ["s1"]}]}
        resp  (request :post "/index/batch"
                       {:user u :accept "application/json"
                        :body {:update [upd] :delete [ghost]}})
        errs  (:errors (body-json resp))]
    (is (== 422 (:status resp)))
    (testing "a foreign entry id and an unknown id both report not-found"
      (is (= 2 (count (filter #(= "not-found" (:error %)) errs)))))
    (testing "the other user's entry is untouched"
      (is (= ["bobs"] (map :title (index-entry (request :get "/index" {:user owner :accept "application/json"}))))))))

(deftest index-batch-delete-dedups
  (let [u "ixb-dedup"
        a    (:id (body-json (request :post "/index"
                     {:user u :accept "application/json"
                      :body {:title "alpha" :hook "h" :requests [{:kind "by-src" :payload ["s1"]}]}})))
        resp (request :post "/index/batch"
                      {:user u :accept "application/json"
                       :body {:delete [a a]}})
        body (body-json resp)]
    (is (== 200 (:status resp)))
    (testing "a repeated delete counts once and removes the entry"
      (is (= 1 (:applied body)))
      (is (empty? (index-entry (request :get "/index" {:user u :accept "application/json"})))))))

;; ---------- recall counts and stats ----------

(deftest recall-count-lifetime-and-recent
  (let [conn ts/*conn* u "s-life"]
    (ts/seed-recall! conn u 14 [["domain" "clojure"]])
    (ts/seed-recall! conn u 14 [["domain" "clojure"]])
    (let [row (first (recall-count/all-recall-rows conn u 14))]
      (testing "lifetime counts every recall"
        (is (= 2 (:lifetime row))))
      (testing "recent decay count is positive and bounded by lifetime"
        (is (pos? (:recent row)))
        (is (<= (:recent row) 2.0))))))

(deftest recall-writer-serializes-and-drains
  (let [conn ts/*conn* u "s-serialize"
        w    (rc-writer/writer conn (recall-count/->TagRecallCount 14))]
    ;; 10 threads each record the same pair 5 times, concurrently
    (let [fs (doall (repeatedly 10 #(future (dotimes [_ 5]
                                              (rc-writer/record-pairs! w u [["domain" "clojure"]])))))]
      (run! deref fs))
    (rc-writer/drain! w)
    (testing "every concurrent increment lands, none lost to a race"
      (is (== 50 (:lifetime (first (recall-count/all-recall-rows conn u 14))))))))

(deftest recall-flush-is-debounced-and-coalesces
  (let [conn ts/*conn* u "s-debounce"
        w    (rc-writer/writer conn (recall-count/->TagRecallCount 14) :debounce-ms 80 :max-wait-ms 1000)]
    (try
      (dotimes [_ 5] (rc-writer/record-pairs! w u [["domain" "clojure"]]))
      (testing "the flush is deferred, so no write has happened yet"
        (Thread/sleep 20)
        (is (empty? (recall-count/all-recall-rows conn u 14))))
      (testing "after the debounce elapses the burst coalesces into the summed count"
        (Thread/sleep 250)
        (is (== 5 (:lifetime (first (recall-count/all-recall-rows conn u 14))))))
      (finally (rc-writer/drain! w)))))

(deftest recall-flush-max-wait-caps-postponement
  (let [conn ts/*conn* u "s-maxwait"
        w    (rc-writer/writer conn (recall-count/->TagRecallCount 14) :debounce-ms 5000 :max-wait-ms 100)]
    (try
      ;; keep rescheduling faster than the debounce; the max wait must still flush
      (dotimes [_ 8] (rc-writer/record-pairs! w u [["domain" "clojure"]]) (Thread/sleep 30))
      (testing "the maximum wait flushes even though the debounce never elapses"
        (is (pos? (:lifetime (first (recall-count/all-recall-rows conn u 14))))))
      (finally (rc-writer/drain! w)))))

(deftest plan-recalls-pure-and-coalesced
  (let [conn ts/*conn* u "s-planr" other "s-planr-bob"]
    (ts/seed-recall! conn u 14 [["domain" "clojure"]])
    (let [db (d/db conn)]
      (testing "a coalesced count adds to the prior lifetime and decayed weight"
        (let [tx (recall-count/plan-recalls db u 14 {["domain" "clojure"] 3} (System/currentTimeMillis))]
          (is (== 1 (count tx)))
          (is (== 4 (:recall/lifetime (first tx))))
          (is (< 3.0 (:recall/decayed (first tx)) 4.001))))
      (testing "a pair with no prior row starts at the count"
        (let [tx (recall-count/plan-recalls db other 14 {["domain" "clojure"] 2} (System/currentTimeMillis))]
          (is (== 2 (:recall/lifetime (first tx))))
          (is (== 2.0 (:recall/decayed (first tx))))))
      (testing "an empty pair-counts plans nothing"
        (is (= [] (recall-count/plan-recalls db u 14 {} (System/currentTimeMillis)))))
      (testing "the planner writes nothing: the stored lifetime is still one"
        (is (== 1 (:lifetime (first (recall-count/all-recall-rows conn u 14)))))))))

(deftest recall-count-injective-across-user-space
  (let [conn ts/*conn*]
    (ts/seed-recall! conn "s-inj one" 14 [["domain" "clojure"]])
    (ts/seed-recall! conn "s-inj" 14 [["domain" "clojure"]])
    (ts/seed-recall! conn "s-inj" 14 [["domain" "clojure"]])
    (testing "user ids that differ by a space keep separate rows via the composite tuple"
      (is (= 1 (:lifetime (first (recall-count/all-recall-rows conn "s-inj one" 14)))))
      (is (= 2 (:lifetime (first (recall-count/all-recall-rows conn "s-inj" 14))))))))

(deftest catalog-covers-the-pair-universe
  (let [conn ts/*conn* u "s-catcov" other "s-catcov-bob"]
    (memory/create! conn u {:content "m1" :src "s-catcov-a" :tags [["domain" "clojure"]]})
    (memory/create! conn u {:content "m2" :src "s-catcov-b" :tags [["domain" "clojure"] ["tech" "datalevin"]]})
    (memory/create! conn other {:content "secret" :src "s-catcov-z" :tags [["domain" "haskell"]]})
    (ts/seed-recall! conn u 14 [["tech" "datalevin"]])
    (ts/seed-recall! conn u 14 [["tech" "datalevin"]])
    (let [rows    (recall-count/catalog conn u 14)
          by-pair (into {} (map (juxt (juxt :category :label) identity)) rows)
          cold    (by-pair ["domain" "clojure"])
          warm    (by-pair ["tech" "datalevin"])]
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
        (is (not (contains? by-pair ["domain" "haskell"])))))))

(deftest catalog-counts-only-memories-carrying-the-pair
  (let [conn ts/*conn* u "s-catcount"]
    (memory/create! conn u {:content "m1" :src "s-cc-a" :tags [["domain" "clojure"]]})
    (memory/create! conn u {:content "m2" :src "s-cc-b" :tags [["domain" "clojure"]]})
    (memory/create! conn u {:content "m3" :src "s-cc-c" :tags [["domain" "databases"]]})
    (let [by-pair (into {} (map (juxt (juxt :category :label) :count))
                        (recall-count/catalog conn u 14))]
      (testing "the count equals the number of the caller's memories that carry the pair"
        (is (== 2 (by-pair ["domain" "clojure"])))
        (is (== 1 (by-pair ["domain" "databases"])))))))

(deftest link-density-metrics
  (let [conn ts/*conn* u "s-link"]
    ;; a -> b -> c is one chain, d is isolated, e points at a ghost src.
    (memory/create! conn u {:content "a" :src "s-link-a" :related ["s-link-b"]})
    (memory/create! conn u {:content "b" :src "s-link-b" :related ["s-link-c"]})
    (memory/create! conn u {:content "c" :src "s-link-c"})
    (memory/create! conn u {:content "d" :src "s-link-d"})
    (memory/create! conn u {:content "e" :src "s-link-e" :related ["ghost"]})
    (memory/create! conn "s-link-bob" {:content "z" :src "s-link-z" :related ["s-link-z2"]})
    (let [{:keys [avg-out-degree largest-wcc-fraction]} (stats/link-density conn u)]
      (testing "average out-degree counts edges to real src nodes over all nodes"
        (is (== 0.4 avg-out-degree)))          ; edges a->b, b->c; e->ghost excluded; 5 nodes
      (testing "largest weakly-connected component is a fraction of the nodes"
        (is (== 0.6 largest-wcc-fraction))))   ; {a,b,c} is 3 of 5 nodes
    (testing "a user with no memories gets zero density"
      (is (= {:avg-out-degree 0.0 :largest-wcc-fraction 0.0}
             (stats/link-density conn "s-link-nobody"))))))

(deftest conforming-fraction-counts-conforming-over-total
  (let [conn ts/*conn* u "s-conf"
        reject? #(config/tag-error (:tag-schema ts/cfg) %)]
    (memory/create! conn u {:content "c1" :src "s-conf-c1" :tags [["domain" "clojure"]]})
    (memory/create! conn u {:content "c2" :src "s-conf-c2" :tags [["domain" "databases"]]})
    (memory/create! conn u {:content "c3" :src "s-conf-c3" :tags [["domain" "clojure"] ["tech" "datalevin"]]})
    (memory/create! conn u {:content "bad" :src "s-conf-bad" :tags [["tech" "datalevin"]]})  ; no domain
    (memory/create! conn "s-conf-bob" {:content "zz" :src "s-conf-zz" :tags [["tech" "datalevin"]]})
    (testing "the fraction is conforming over total, for the caller only"
      (is (== 0.75 (:conforming-fraction (stats/memory-aggregates conn u reject? 30 14)))))   ; 3 of 4 conform
    (testing "a user with no memories gets 0.0"
      (is (== 0.0 (:conforming-fraction (stats/memory-aggregates conn "s-conf-nobody" reject? 30 14)))))))

(deftest aggregate-memories-covers-only-the-callers-memories
  (let [conn ts/*conn* u "s-aggcov"
        reject? #(config/tag-error (:tag-schema ts/cfg) %)]
    (memory/create! conn u {:content "a" :src "s-aggcov-a" :tags [["domain" "clojure"]]})
    (memory/create! conn "s-aggcov-bob" {:content "z" :src "s-aggcov-z" :tags [["tech" "datalevin"]]})
    (testing "the caller's aggregates ignore another user's nonconforming memory"
      (is (== 1.0 (:conforming-fraction (stats/memory-aggregates conn u reject? 30 14)))))))

;; ---------- per-memory recall count (ticket 13) ----------

(deftest memory-recall-schema-and-no-backfill
  (let [conn ts/*conn* u "pm-schema"]
    (testing "migration 003 added the three recall attributes"
      (is (= :db.type/long    (get-in (d/schema conn) [:memory/recall-lifetime :db/valueType])))
      (is (= :db.type/double  (get-in (d/schema conn) [:memory/recall-decayed :db/valueType])))
      (is (= :db.type/instant (get-in (d/schema conn) [:memory/last-recalled :db/valueType]))))
    (memory/create! conn u {:content "x" :src "pm-schema-s" :tags [["domain" "clojure"]]})
    (testing "a never-recalled memory carries no recall count"
      (is (empty? (ts/mem-counts conn u))))))

(deftest plan-memory-recalls-pure-and-coalesced
  (let [conn ts/*conn* u "pm-plan"
        id   (memory/create! conn u {:content "m" :src "pm-plan-m" :tags [["domain" "clojure"]]})]
    (testing "a first count starts the lifetime and the decayed weight at the count"
      (let [tx (recall-count/plan-memory-recalls (d/db conn) 14 {id 2} (System/currentTimeMillis))]
        (is (== 1 (count tx)))
        (is (== 2 (:memory/recall-lifetime (first tx))))
        (is (== 2.0 (:memory/recall-decayed (first tx))))
        (is (instance? java.util.Date (:memory/last-recalled (first tx))))))
    (testing "an empty id-counts plans nothing"
      (is (= [] (recall-count/plan-memory-recalls (d/db conn) 14 {} (System/currentTimeMillis)))))
    (testing "the planner writes nothing"
      (is (empty? (ts/mem-counts conn u))))
    (testing "a later count adds to the stored lifetime and decays the prior weight"
      (d/transact! conn (recall-count/plan-memory-recalls (d/db conn) 14 {id 3} (System/currentTimeMillis)))
      (let [tx (recall-count/plan-memory-recalls (d/db conn) 14 {id 1} (System/currentTimeMillis))]
        (is (== 4 (:memory/recall-lifetime (first tx))))
        (is (< 3.0 (:memory/recall-decayed (first tx)) 4.001))))))

(deftest memory-recall-count-records-on-recall-by-tags
  (let [conn ts/*conn* u "pm-bytags"
        mw   (ts/short-writer conn :memories)
        tw   (ts/short-writer conn :tags)
        app  (handler/app conn ts/cfg tw mw)]
    (try
      ;; a matches the tag and links to b; b is pulled in only by the closure.
      (request app :post "/memories" {:user u :accept "application/json"
                                      :body {:content "fa" :src "pm-bytags-a" :tags [["domain" "clojure"]] :related ["pm-bytags-b"]}})
      (request app :post "/memories" {:user u :accept "application/json"
                                      :body {:content "fb" :src "pm-bytags-b" :tags [["domain" "clojure"]]}})
      (is (ts/await-flush mw #(request app :post "/memories/recall/by-tags"
                                       {:user u :accept "application/json" :body {:match ["domain" "clojure"]}})))
      (let [by-src (ts/mem-counts conn u)]
        (testing "the tag match gains a recall count"
          (is (== 1 (get-in by-src ["pm-bytags-a" :lifetime])))
          (is (pos? (get-in by-src ["pm-bytags-a" :decayed]))))
        (testing "the closure memory gains a recall count too"
          (is (== 1 (get-in by-src ["pm-bytags-b" :lifetime]))))
        (testing "every counted memory carries a last-recalled stamp"
          (is (every? :last-recalled (vals by-src)))))
      (finally (rc-writer/drain! tw) (rc-writer/drain! mw)))))

(deftest memory-recall-count-records-on-recall-by-ids
  (let [conn ts/*conn* u "pm-byids"
        mw   (ts/short-writer conn :memories)
        tw   (ts/short-writer conn :tags)
        app  (handler/app conn ts/cfg tw mw)]
    (try
      (let [a (:id (body-json (request app :post "/memories"
                                       {:user u :accept "application/json"
                                        :body {:content "fa" :src "pm-byids-a" :tags [["domain" "clojure"]] :related ["pm-byids-b"]}})))]
        (request app :post "/memories" {:user u :accept "application/json"
                                        :body {:content "fb" :src "pm-byids-b" :tags [["domain" "clojure"]]}})
        (is (ts/await-flush mw #(request app :post "/memories/recall/by-ids"
                                         {:user u :accept "application/json" :body {:ids [a]}})))
        (let [by-src (ts/mem-counts conn u)]
          (testing "the id and its closure each gain a recall count"
            (is (== 1 (get-in by-src ["pm-byids-a" :lifetime])))
            (is (== 1 (get-in by-src ["pm-byids-b" :lifetime]))))))
      (finally (rc-writer/drain! tw) (rc-writer/drain! mw)))))

(deftest search-does-not-record-memory-recall-count
  (let [conn ts/*conn* u "pm-nosearch"
        mw   (ts/short-writer conn :memories)
        tw   (ts/short-writer conn :tags)
        app  (handler/app conn ts/cfg tw mw)]
    (try
      (request app :post "/memories" {:user u :accept "application/json"
                                      :body {:content "deploy to the pi" :src "pm-nosearch-s" :tags [["domain" "clojure"]]}})
      (request app :post "/memories/search" {:user u :accept "application/json" :body {:search "deploy"}})
      (rc-writer/drain! mw)
      (testing "a search records no memory recall count"
        (is (empty? (ts/mem-counts conn u))))
      (finally (rc-writer/drain! tw)))))

(deftest memory-recall-count-off-the-wire-and-off-updated-at
  (let [conn ts/*conn* u "pm-offwire"
        mw   (ts/short-writer conn :memories)
        tw   (ts/short-writer conn :tags)
        app  (handler/app conn ts/cfg tw mw)]
    (try
      (let [id     (:id (body-json (request app :post "/memories"
                                            {:user u :accept "application/json"
                                             :body {:content "x" :src "pm-offwire-s" :tags [["domain" "clojure"]]}})))
            before (ts/updated-at conn id)
            mem    (atom nil)]
        (is (ts/await-flush mw #(let [resp (request app :post "/memories/recall/by-tags"
                                                    {:user u :accept "application/json" :body {:match ["domain" "clojure"]}})]
                                  (reset! mem (first (:memories (body-json resp)))))))
        (testing "the recall wire carries the freshness band but no recall-count keys"
          (is (= #{:id :content :src :tags :freshness} (set (keys @mem)))))
        (testing "the memory was counted"
          (is (== 1 (get-in (ts/mem-counts conn u) ["pm-offwire-s" :lifetime]))))
        (testing "the count write did not bump updated-at"
          (is (= before (ts/updated-at conn id)))))
      (finally (rc-writer/drain! tw) (rc-writer/drain! mw)))))

(deftest memory-recall-count-records-over-ndjson-stream
  (let [conn ts/*conn* u "pm-ndjson"
        mw   (ts/short-writer conn :memories)
        tw   (ts/short-writer conn :tags)
        app  (handler/app conn ts/cfg tw mw)]
    (try
      (request app :post "/memories" {:user u :accept "application/json"
                                      :body {:content "x" :src "pm-ndjson-s" :tags [["domain" "clojure"]]}})
      (is (ts/await-flush mw #(slurp (:body (request app :post "/memories/recall/by-tags"
                                                     {:user u :accept "application/x-ndjson" :body {:match ["domain" "clojure"]}})))))
      (testing "the streamed recall records the delivered memory"
        (is (== 1 (get-in (ts/mem-counts conn u) ["pm-ndjson-s" :lifetime]))))
      (finally (rc-writer/drain! tw) (rc-writer/drain! mw)))))

;; ---------- handler: stats, recalls, config, errors, healthz ----------

(deftest handler-stats-carries-link-density
  (let [conn ts/*conn* u "h-ld"]
    (memory/create! conn u {:content "a" :src "h-ld-a" :tags [["domain" "clojure"]] :related ["h-ld-b"]})
    (memory/create! conn u {:content "b" :src "h-ld-b" :tags [["domain" "clojure"]]})
    (let [resp (request :get "/stats" {:user u :accept "application/json"})
          body (body-json resp)
          ld   (get-in body [:stats :link-density])]
      (testing "link density sits under :stats, and the recall rows are gone"
        (is (== 200 (:status resp)))
        (is (nil? (get-in body [:stats :recalls])))
        (is (map? ld)))
      (testing "the two metrics are present and numeric"
        (is (== 0.5 (:avg-out-degree ld)))           ; one edge a->b over two nodes
        (is (== 1.0 (:largest-wcc-fraction ld)))))))  ; {a,b} is the whole graph

(deftest handler-stats-conforming-fraction
  (let [conn ts/*conn* u "h-conf"]
    ;; two conforming, one not: 2/3 = 0.6666... truncated to four decimals
    (memory/create! conn u {:content "c1" :src "h-conf-c1" :tags [["domain" "clojure"]]})
    (memory/create! conn u {:content "c2" :src "h-conf-c2" :tags [["domain" "databases"]]})
    (memory/create! conn u {:content "bad" :src "h-conf-bad" :tags [["tech" "datalevin"]]})
    (let [body (body-json (request :get "/stats" {:user u :accept "application/json"}))
          f    (get-in body [:stats :conforming-fraction])]
      (testing "the stats body carries the conforming fraction, truncated to four decimals"
        (is (== 0.6666 f))))))

(deftest handler-stats-freshness-aggregates
  (let [conn ts/*conn* u "h-fresh"]
    (memory/create! conn u {:content "m1" :src "h-fresh-a" :tags [["domain" "clojure"]]})
    (memory/create! conn u {:content "m2" :src "h-fresh-b" :tags [["domain" "databases"]]})
    (let [s (:stats (body-json (request :get "/stats" {:user u :accept "application/json"})))]
      (testing "the stats body names the three freshness aggregates, each numeric"
        (is (number? (:mean-freshness s)))
        (is (number? (:use-weighted-freshness s)))
        (is (number? (:hot-and-stale-fraction s))))
      (testing "a just-created store is fully fresh and nothing is hot-and-stale"
        (is (<= 0.9999 (:mean-freshness s) 1.0))
        (is (== 0.0 (:hot-and-stale-fraction s))))
      (testing "with no recalls, the use-weighted mean falls back to the plain mean"
        (is (== (:mean-freshness s) (:use-weighted-freshness s)))))))

(deftest handler-recalls-route
  (let [conn ts/*conn* u "h-recalls" other "h-recalls-bob"]
    (memory/create! conn u {:content "m1" :src "h-recalls-a" :tags [["domain" "clojure"]]})
    (memory/create! conn u {:content "m2" :src "h-recalls-b" :tags [["domain" "clojure"] ["tech" "datalevin"]]})
    (memory/create! conn other {:content "secret" :src "h-recalls-z" :tags [["domain" "haskell"]]})
    (testing "a bare recalls map, no stats wrapper, five-element rows, user-scoped"
      (let [resp (request :get "/recalls" {:user u :accept "application/json"})
            body (body-json resp)]
        (is (== 200 (:status resp)))
        (is (vector? (:recalls body)))
        (is (nil? (:stats body)))
        (is (not (some #(= "haskell" (nth % 1)) (:recalls body))))      ; another user's label never appears
        (let [row (first (filter #(= ["domain" "clojure"] [(nth % 0) (nth % 1)]) (:recalls body)))]
          (is (== 5 (count row)))
          (is (== 2 (nth row 2))))))                                    ; count: two memories carry the pair
    (testing "a single-category filter returns only that category"
      (let [cats (->> (request :get "/recalls" {:user u :accept "application/json" :query "categories=tech"})
                      body-json :recalls (map first) set)]
        (is (= #{"tech"} cats))))
    (testing "a multi-category filter returns the union"
      (let [cats (->> (request :get "/recalls" {:user u :accept "application/json" :query "categories=domain,tech"})
                      body-json :recalls (map first) set)]
        (is (= #{"domain" "tech"} cats))))
    (testing "no filter returns every category"
      (let [cats (->> (request :get "/recalls" {:user u :accept "application/json"})
                      body-json :recalls (map first) set)]
        (is (= #{"domain" "tech"} cats))))))

(deftest handler-config-carries-value-set
  (let [raw  {:recall-half-life-days 14
              :configurations [{"scope" {:cardinality "?" :one-of ["global" "project"]} "domain" "+"}]}
        c    (assoc raw :tag-schema (config/compile-tag-schema raw))
        conn ts/*conn*
        app  (handler/app conn c (ts/tag-writer conn) (ts/mem-writer conn))]
    (let [body  (body-json (request app :get "/config" {:user "h-config" :accept "application/json"}))
          scope (get-in body [:configurations 0 :scope])]
      (testing "GET /config carries the map form for a value-set category"
        (is (= "?" (:cardinality scope)))
        (is (= ["global" "project"] (:one-of scope)))))))

(deftest error-logging-and-correlation-id
  (let [u "h-err"]
    (testing "every response carries a correlation-id header"
      (is (some? (get-in (request :get "/healthz" {:accept "application/json"})
                         [:headers "X-Engram-Request-Id"]))))
    (testing "a handler error returns 500 with the correlation id in body and header"
      ;; raise the level so the deliberate 500 does not log on the test thread
      (t/with-min-level :fatal
        (with-redefs [memory/recall-by-match (fn [& _] (throw (ex-info "boom" {})))]
          (let [resp (request :post "/memories/recall/by-tags"
                              {:user u :accept "application/json" :body {:match ["domain" "clojure"]}})
                body (body-json resp)]
            (is (== 500 (:status resp)))
            (is (some? (:id body)))
            (is (= (:id body) (get-in resp [:headers "X-Engram-Request-Id"])))))))))

(deftest healthz-reports-the-version
  (testing "/healthz returns the build version, dev in a source tree"
    (let [body (body-json (request :get "/healthz" {:accept "application/json"}))]
      (is (= "ok" (:status body)))
      (is (= "dev" (:version body))))))

;; ---------- handler: routes, streaming, 409 ----------

(deftest handler-flow
  (let [u "h-flow"]
    (testing "a request without the user header is rejected"
      (is (= 401 (:status (request :post "/memories"
                                   {:body {:content "x" :src "h-flow-s" :tags [["domain" "clojure"]]}
                                    :accept "application/json"})))))
    (testing "a valid create returns 201"
      (is (= 201 (:status (request :post "/memories"
                                   {:user u :accept "application/json"
                                    :body {:content "prefer ==" :src "h-flow-s1" :tags [["domain" "clojure"]]}})))))
    (testing "an invalid create returns 409 with the configurations"
      (let [resp (request :post "/memories"
                          {:user u :accept "application/json"
                           :body {:content "bad" :src "h-flow-s2" :tags [["tech" "datalevin"]]}})
            body (body-json resp)]
        (is (= 409 (:status resp)))
        (is (= "no-configuration" (:error body)))
        (is (some? (:configurations body)))))
    (testing "GET /config returns the configurations and the category descriptions"
      (let [resp (request :get "/config" {:user u :accept "application/json"})
            body (body-json resp)]
        (is (= 200 (:status resp)))
        (is (some? (:configurations body)))
        (is (map? (:categories body)))
        (is (= "the broad subject area" (get-in body [:categories :domain :description])))
        (is (some #{"clojure"} (get-in body [:categories :domain :examples])))))
    (testing "the recall call streams NDJSON when asked"
      (let [resp  (request :post "/memories/recall/by-tags"
                           {:user u :accept "application/x-ndjson" :body {:match ["domain" "clojure"]}})
            lines (->> (slurp (:body resp)) str/split-lines (remove str/blank?))]
        (is (= "application/x-ndjson" (get-in resp [:headers "Content-Type"])))
        (is (>= (count lines) 2))
        (is (true? (:header (json/read-value (first lines) json/keyword-keys-object-mapper))))
        (is (some #(= "prefer ==" (:content (json/read-value % json/keyword-keys-object-mapper))) (rest lines)))))))

(deftest handler-recall-streams-all-rows
  (let [conn ts/*conn* u "h-streamall" n 40]
    (dotimes [i n]
      (memory/create! conn u {:content (str "m" i) :src (str "h-streamall-s" i) :tags [["domain" "clojure"]]}))
    (let [resp  (request :post "/memories/recall/by-tags"
                         {:user u :accept "application/x-ndjson" :body {:match ["domain" "clojure"]}})
          lines (->> (slurp (:body resp)) str/split-lines (remove str/blank?))]
      (testing "every matched memory streams, not just the first chunk"
        (is (= (inc n) (count lines)))))))

(deftest handler-recall-by-ids-route
  (let [u "h-byids" other "h-byids-bob"
        a (:id (body-json (request :post "/memories"
                                   {:user u :accept "application/json"
                                    :body {:content "fact a" :src "h-byids-a"
                                           :tags [["domain" "clojure"]] :related ["h-byids-b"]}})))
        _ (request :post "/memories"
                   {:user u :accept "application/json"
                    :body {:content "fact b" :src "h-byids-b" :tags [["domain" "clojure"]]}})
        z (:id (body-json (request :post "/memories"
                                   {:user other :accept "application/json"
                                    :body {:content "secret" :src "h-byids-z" :tags [["domain" "clojure"]]}})))]
    (testing "the id recall streams NDJSON with the matches and their closure"
      (let [resp  (request :post "/memories/recall/by-ids"
                           {:user u :accept "application/x-ndjson" :body {:ids [a]}})
            lines (->> (slurp (:body resp)) str/split-lines (remove str/blank?))
            mems  (map #(json/read-value % json/keyword-keys-object-mapper) (rest lines))]
        (is (= "application/x-ndjson" (get-in resp [:headers "Content-Type"])))
        (is (true? (:header (json/read-value (first lines) json/keyword-keys-object-mapper))))
        (is (= #{"h-byids-a" "h-byids-b"} (set (map :src mems))))
        (testing "a row carries no score and no category projection"
          (is (not-any? :score mems)))))
    (testing "the JSON fallback carries memories, matching the selected closure"
      (let [body (body-json (request :post "/memories/recall/by-ids"
                                     {:user u :accept "application/json" :body {:ids [a]}}))]
        (is (vector? (:memories body)))
        (is (= #{"h-byids-a" "h-byids-b"} (set (map :src (:memories body)))))))
    (testing "a foreign id is skipped silently, so nothing matches"
      (let [body (body-json (request :post "/memories/recall/by-ids"
                                     {:user u :accept "application/json" :body {:ids [z]}}))]
        (is (empty? (:memories body)))))
    (testing "an empty ids list returns a 200 with a header-only stream"
      (let [resp  (request :post "/memories/recall/by-ids"
                           {:user u :accept "application/x-ndjson" :body {:ids []}})
            lines (->> (slurp (:body resp)) str/split-lines (remove str/blank?))]
        (is (== 200 (:status resp)))
        (is (= 1 (count lines)))))
    (testing "a non-uuid id is a 400 at coercion"
      (is (== 400 (:status (request :post "/memories/recall/by-ids"
                                    {:user u :accept "application/json" :body {:ids ["not-a-uuid"]}})))))
    (testing "a request without the user header is rejected"
      (is (== 401 (:status (request :post "/memories/recall/by-ids"
                                    {:accept "application/json" :body {:ids [a]}})))))))

(deftest handler-recall-by-src-route
  (let [u "h-bysrc" other "h-bysrc-bob"]
    (request :post "/memories"
             {:user u :accept "application/json"
              :body {:content "fact a" :src "h-bysrc-a" :tags [["domain" "clojure"]] :related ["h-bysrc-b"]}})
    (request :post "/memories"
             {:user u :accept "application/json"
              :body {:content "fact b" :src "h-bysrc-b" :tags [["domain" "clojure"]]}})
    (request :post "/memories"
             {:user other :accept "application/json"
              :body {:content "secret" :src "h-bysrc-z" :tags [["domain" "clojure"]]}})
    (testing "the src recall streams NDJSON with the matches and their closure"
      (let [resp  (request :post "/memories/recall/by-src"
                           {:user u :accept "application/x-ndjson" :body {:srcs ["h-bysrc-a"]}})
            lines (->> (slurp (:body resp)) str/split-lines (remove str/blank?))
            mems  (map #(json/read-value % json/keyword-keys-object-mapper) (rest lines))]
        (is (= "application/x-ndjson" (get-in resp [:headers "Content-Type"])))
        (is (true? (:header (json/read-value (first lines) json/keyword-keys-object-mapper))))
        (is (= #{"h-bysrc-a" "h-bysrc-b"} (set (map :src mems))))
        (testing "a row carries no score"
          (is (not-any? :score mems)))))
    (testing "the JSON fallback carries the selected memories and their closure"
      (let [body (body-json (request :post "/memories/recall/by-src"
                                     {:user u :accept "application/json" :body {:srcs ["h-bysrc-a"]}}))]
        (is (vector? (:memories body)))
        (is (= #{"h-bysrc-a" "h-bysrc-b"} (set (map :src (:memories body)))))
        (is (= ["h-bysrc-a"] (:srcs body)))))
    (testing "a foreign src is skipped silently, so nothing matches"
      (let [body (body-json (request :post "/memories/recall/by-src"
                                     {:user u :accept "application/json" :body {:srcs ["h-bysrc-z"]}}))]
        (is (empty? (:memories body)))))
    (testing "an empty srcs list returns a 200 with a header-only stream"
      (let [resp  (request :post "/memories/recall/by-src"
                           {:user u :accept "application/x-ndjson" :body {:srcs []}})
            lines (->> (slurp (:body resp)) str/split-lines (remove str/blank?))]
        (is (== 200 (:status resp)))
        (is (= 1 (count lines)))))
    (testing "a non-token src is a 400 at coercion"
      (is (== 400 (:status (request :post "/memories/recall/by-src"
                                    {:user u :accept "application/json" :body {:srcs ["Bad-Src"]}})))))
    (testing "a request without the user header is rejected"
      (is (== 401 (:status (request :post "/memories/recall/by-src"
                                    {:accept "application/json" :body {:srcs ["h-bysrc-a"]}})))))))

(deftest handler-memories-streams-all
  (let [conn ts/*conn* u "h-memall" other "h-memall-bob" n 40]
    (dotimes [i n]
      (memory/create! conn u {:content (str "m" i) :src (str "h-memall-s" i) :tags [["domain" "clojure"]]}))
    (memory/create! conn other {:content "secret" :src "h-memall-z" :tags [["domain" "clojure"]]})
    (let [resp  (request :get "/memories" {:user u :accept "application/x-ndjson"})
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
        (is (every? #(every? % [:id :content :src]) mems))))))

(deftest handler-nonconforming-streams
  (let [conn ts/*conn* u "h-nc" other "h-nc-bob"]
    (memory/create! conn u {:content "ok" :src "h-nc-ok" :tags [["domain" "clojure"]]})
    (memory/create! conn u {:content "bad" :src "h-nc-bad" :tags [["tech" "datalevin"]]})
    (memory/create! conn other {:content "other-bad" :src "h-nc-zz" :tags [["tech" "datalevin"]]})
    (let [resp  (request :get "/memories/nonconforming" {:user u :accept "application/x-ndjson"})
          lines (->> (slurp (:body resp)) str/split-lines (remove str/blank?))
          mems  (map #(json/read-value % json/keyword-keys-object-mapper) (rest lines))
          srcs  (set (map :src mems))]
      (testing "the response is an NDJSON stream with a header line"
        (is (= "application/x-ndjson" (get-in resp [:headers "Content-Type"])))
        (is (true? (:header (json/read-value (first lines) json/keyword-keys-object-mapper)))))
      (testing "only the caller's nonconforming memory appears"
        (is (= #{"h-nc-bad"} srcs))))))

(deftest handler-fetch-one
  (let [u "h-fetch" other "h-fetch-bob"
        id (:id (body-json (request :post "/memories"
                                    {:user u :accept "application/json"
                                     :body {:content "x" :src "h-fetch-s" :tags [["domain" "clojure"]]}})))]
    (testing "the owner reads the memory by id"
      (let [resp (request :get (str "/memories/" id) {:user u :accept "application/json"})
            body (body-json resp)]
        (is (== 200 (:status resp)))
        (is (= id (:id body)))
        (is (= "x" (:content body)))))
    (testing "another user gets 404"
      (is (== 404 (:status (request :get (str "/memories/" id) {:user other :accept "application/json"})))))
    (testing "a missing id gets 404"
      (is (== 404 (:status (request :get (str "/memories/" (java.util.UUID/randomUUID))
                                    {:user u :accept "application/json"})))))
    (testing "a malformed id is a 400 coercion failure"
      (is (== 400 (:status (request :get "/memories/not-a-uuid" {:user u :accept "application/json"})))))))

(deftest handler-delete
  (let [u "h-del" other "h-del-bob"
        id (:id (body-json (request :post "/memories"
                                    {:user u :accept "application/json"
                                     :body {:content "x" :src "h-del-s" :tags [["domain" "clojure"]]}})))]
    (testing "another user's delete returns 404"
      (is (== 404 (:status (request :delete (str "/memories/" id) {:user other :accept "application/json"})))))
    (testing "the owner's delete returns 200"
      (is (== 200 (:status (request :delete (str "/memories/" id) {:user u :accept "application/json"})))))))

(deftest handler-token-format
  (let [u "h-token"]
    (testing "a malformed src returns 400 (coercion)"
      (is (== 400 (:status (request :post "/memories"
                                    {:user u :accept "application/json"
                                     :body {:content "x" :src "Bad_Src" :tags [["domain" "clojure"]]}})))))
    (testing "a malformed related returns 400 (coercion)"
      (is (== 400 (:status (request :post "/memories"
                                    {:user u :accept "application/json"
                                     :body {:content "x" :src "h-token-ok" :tags [["domain" "clojure"]] :related ["Bad!"]}})))))))

(deftest handler-malformed-id
  (let [u "h-malid"]
    (testing "a non-uuid id on PUT is a coercion failure, returning 400"
      (is (== 400 (:status (request :put "/memories/not-a-uuid"
                                    {:user u :accept "application/json" :body {:content "x"}})))))
    (testing "a non-uuid id on DELETE is a coercion failure, returning 400"
      (is (== 400 (:status (request :delete "/memories/not-a-uuid" {:user u :accept "application/json"})))))))

(deftest handler-malformed-user
  (testing "a user id that is not a token is rejected with 400"
    (is (== 400 (:status (request :get "/config" {:user "alice bob" :accept "application/json"})))))
  (testing "a well-formed user id is accepted"
    (is (== 200 (:status (request :get "/config" {:user "h-maluser" :accept "application/json"}))))))

(deftest handler-batch
  (let [u "h-batch"]
    (testing "a grouped-map batch of creates returns 200 with ids and an applied count"
      (let [resp (request :post "/memories/batch"
                          {:user u :accept "application/json"
                           :body {:create [{:content "f1" :src "h-batch-a" :tags [["domain" "clojure"]]}
                                           {:content "f2" :src "h-batch-b" :tags [["domain" "clojure"]]}]}})
            body (body-json resp)]
        (is (== 200 (:status resp)))
        (is (== 2 (count (:ids body))))
        (is (== 2 (:applied body)))))
    (testing "a bad tag rejects the batch with 422 and the configurations on the failing entry"
      (let [resp (request :post "/memories/batch"
                          {:user u :accept "application/json"
                           :body {:create [{:content "bad" :src "h-batch-c" :tags [["tech" "datalevin"]]}]}})
            body (body-json resp)]
        (is (== 422 (:status resp)))
        (is (= "create" (:op (first (:errors body)))))
        (is (== 0 (:i (first (:errors body)))))
        (is (= "no-configuration" (:error (first (:errors body)))))
        (is (some? (:configurations (first (:errors body)))))))
    (testing "an id in both update and delete rejects with 422 and conflict on the wire"
      (let [id   (:id (body-json (request :post "/memories"
                                          {:user u :accept "application/json"
                                           :body {:content "orig" :src "h-batch-z" :tags [["domain" "clojure"]]}})))
            resp (request :post "/memories/batch"
                          {:user u :accept "application/json"
                           :body {:update [{:id id :content "after"}] :delete [id]}})
            body (body-json resp)]
        (is (== 422 (:status resp)))
        (is (= #{"conflict"} (set (map :error (:errors body)))))))
    (testing "an update of a missing id rejects with 422 and not-found on the wire"
      (let [resp (request :post "/memories/batch"
                          {:user u :accept "application/json"
                           :body {:update [{:id (str (java.util.UUID/randomUUID)) :content "x"}]}})
            body (body-json resp)]
        (is (== 422 (:status resp)))
        (is (= "not-found" (:error (first (:errors body)))))))
    (testing "an empty batch returns 200 with no ids"
      (let [resp (request :post "/memories/batch" {:user u :accept "application/json" :body {}})
            body (body-json resp)]
        (is (== 200 (:status resp)))
        (is (= [] (:ids body)))
        (is (== 0 (:applied body)))))
    (testing "a create payload missing a required field is malformed and returns 400"
      (is (== 400 (:status (request :post "/memories/batch"
                                    {:user u :accept "application/json" :body {:create [{:content "x"}]}})))))))

(deftest recall-json-fallback-shape
  (let [u "h-jsonfb"]
    (request :post "/memories"
             {:user u :accept "application/json"
              :body {:content "prefer ==" :src "h-jsonfb-s" :tags [["domain" "clojure"]]}})
    (let [resp (request :post "/memories/recall/by-tags"
                        {:user u :accept "application/json" :body {:match ["domain" "clojure"]}})
          body (body-json resp)]
      (testing "the JSON fallback echoes the match and returns memories"
        (is (== 200 (:status resp)))
        (is (= ["domain" "clojure"] (:match body)))
        (is (vector? (:memories body))))
      (testing "each memory carries the wire keys plus the freshness band, and an empty related is omitted"
        (let [m (first (:memories body))]
          (is (= #{:id :content :src :tags :freshness} (set (keys m))))
          (is (not (contains? m :related))))))
    (testing "the old pairs body key no longer matches, so it is a 400"
      (is (== 400 (:status (request :post "/memories/recall/by-tags"
                                    {:user u :accept "application/json" :body {:pairs [["domain" "clojure"]]}})))))))

;; ---------- search (ticket 11 and 22) ----------

(deftest search-ranks-content-and-src
  (let [conn ts/*conn* u "se-rank" other "se-rank-bob"]
    (memory/create! conn u {:content "deploy the server to the pi" :src "se-rank-engram-deploy" :tags [["domain" "clojure"]]})
    (memory/create! conn u {:content "a short note" :src "se-rank-deploy-notes"})
    (memory/create! conn u {:content "deploy deploy deploy everywhere" :src "se-rank-clojure-tips"})
    (memory/create! conn u {:content "nothing relevant here" :src "se-rank-unrelated"})
    (memory/create! conn other {:content "secret deploys things" :src "se-rank-bob-deploy"})
    (let [rows (memory/search conn u "deploy" 10)
          srcs (set (map :src rows))]
      (testing "every memory with the term in content or src matches, deduped"
        (is (== 3 (count rows)))
        (is (= #{"se-rank-engram-deploy" "se-rank-deploy-notes" "se-rank-clojure-tips"} srcs)))
      (testing "a src is searchable by a hyphen-split word"
        (is (contains? srcs "se-rank-deploy-notes")))
      (testing "a non-matching memory never appears"
        (is (not (contains? srcs "se-rank-unrelated"))))
      (testing "another user's match never appears"
        (is (not (contains? srcs "se-rank-bob-deploy"))))
      (testing "each row carries a numeric score and no related key"
        (is (every? #(number? (:score %)) rows))
        (is (not-any? :related rows)))
      (testing "rows are ranked by score, descending"
        (is (= (map :score rows) (sort > (map :score rows)))))
      (testing "the memory with the term three times outranks the single mentions"
        (is (= "se-rank-clojure-tips" (:src (first rows))))))
    (testing "the limit caps the count and keeps the top ranks"
      (let [top1 (memory/search conn u "deploy" 1)]
        (is (== 1 (count top1)))
        (is (= "se-rank-clojure-tips" (:src (first top1))))))
    (testing "a term that matches nothing returns an empty vector"
      (is (= [] (memory/search conn u "nonexistentterm" 10))))
    (testing "the matched word in a src carries over to the whole-src token too"
      (is (contains? (set (map :src (memory/search conn u "engram" 10))) "se-rank-engram-deploy")))))

(deftest search-by-expression
  (let [conn ts/*conn* u "se-expr"]
    (memory/create! conn u {:content "alpha beta" :src "se-expr-s1" :tags [["domain" "clojure"]]})
    (memory/create! conn u {:content "alpha only" :src "se-expr-s2" :tags [["domain" "clojure"]]})
    (let [srcs (fn [b] (set (map :src (:results (body-json (request :post "/memories/search"
                                                                   {:user u :accept "application/json" :body b}))))))]
      (testing "a bare string still searches"
        (is (= #{"se-expr-s1" "se-expr-s2"} (srcs {:search "alpha"}))))
      (testing "and with not narrows"
        (is (= #{"se-expr-s2"} (srcs {:search {:and ["alpha" {:not "beta"}]}}))))
      (testing "a phrase leaf matches adjacency"
        (is (= #{"se-expr-s1"} (srcs {:search {:phrase "alpha beta"}})))))
    (testing "a blank string is still a 400"
      (is (== 400 (:status (request :post "/memories/search" {:user u :body {:search "   "}})))))))

(deftest handler-search-route
  (let [conn ts/*conn* u "h-search" other "h-search-bob"]
    (memory/create! conn u {:content "deploy to the pi" :src "h-search-engram-deploy"
                            :tags [["domain" "clojure"] ["tech" "datalevin"]]})
    (memory/create! conn u {:content "unrelated" :src "h-search-other"})
    (memory/create! conn other {:content "deploy secret" :src "h-search-bob-deploy"})
    (testing "a search returns a bounded results array, ranked and user-scoped"
      (let [resp (request :post "/memories/search" {:user u :accept "application/json" :body {:search "deploy"}})
            body (body-json resp)]
        (is (== 200 (:status resp)))
        (is (vector? (:results body)))
        (is (= #{"h-search-engram-deploy"} (set (map :src (:results body)))))
        (let [row (first (:results body))]
          (is (number? (:score row)))
          (is (not (contains? row :related))))))
    (testing "with no categories, a row carries no tags"
      (let [row (first (:results (body-json (request :post "/memories/search"
                                                     {:user u :accept "application/json" :body {:search "deploy"}}))))]
        (is (not (contains? row :tags)))))
    (testing "an empty categories vector also yields no tags"
      (let [row (first (:results (body-json (request :post "/memories/search"
                                                     {:user u :accept "application/json" :body {:search "deploy" :categories []}}))))]
        (is (not (contains? row :tags)))))
    (testing "categories project the tags to the named ones only"
      (let [row (first (:results (body-json (request :post "/memories/search"
                                                     {:user u :accept "application/json" :body {:search "deploy" :categories ["domain"]}}))))]
        (is (= [["domain" "clojure"]] (:tags row)))))
    (testing "a blank search string is a 400"
      (is (== 400 (:status (request :post "/memories/search" {:user u :accept "application/json" :body {:search "   "}})))))
    (testing "a missing search string is a 400 (coercion)"
      (is (== 400 (:status (request :post "/memories/search" {:user u :accept "application/json" :body {:limit 5}})))))
    (testing "an over-cap limit is clamped, not rejected"
      (is (== 200 (:status (request :post "/memories/search" {:user u :accept "application/json" :body {:search "deploy" :limit 999}})))))
    (testing "a request without the user header is rejected"
      (is (== 401 (:status (request :post "/memories/search" {:accept "application/json" :body {:search "deploy"}})))))))

(deftest short-term-search-by-expression
  (let [u "se-st"]
    (request :post "/memories/short-term" {:user u :body {:thought-process "task-a" :text "write the datalevin migration"}})
    (request :post "/memories/short-term" {:user u :body {:thought-process "task-a" :text "review the pull request"}})
    (testing "an expression body streams only the matches"
      (let [resp  (request :post "/memories/short-term/search"
                           {:user u :query "thought_process=task-a" :body {:search {:and ["migration" {:not "review"}]}}})
            lines (->> (slurp (:body resp)) str/split-lines (remove str/blank?))
            notes (map #(json/read-value % json/keyword-keys-object-mapper) (rest lines))]
        (is (= ["write the datalevin migration"] (mapv :text notes)))))
    (testing "a malformed expression is a 400"
      (is (== 400 (:status (request :post "/memories/short-term/search" {:user u :body {:search {:and []}}})))))))

;; ---------- freshness, confirm, and related repair (ticket 14, 18c) ----------

(def ^:private day-ms 86400000)

(deftest last-confirmed-schema-and-stamping
  (let [conn ts/*conn* u "f-stamp"]
    (testing "migration 004 added the last-confirmed instant attribute"
      (is (= :db.type/instant (get-in (d/schema conn) [:memory/last-confirmed :db/valueType]))))
    (let [id (memory/create! conn u {:content "x" :src "f-stamp-s" :tags [["domain" "clojure"]]})]
      (testing "a create stamps last-confirmed equal to created-at and updated-at"
        (is (= (ts/created-at conn id) (ts/updated-at conn id) (ts/last-confirmed conn id))))
      (Thread/sleep 5)
      (memory/update! conn u id {:content "y"})
      (testing "an update moves updated-at and last-confirmed to a later instant together"
        (is (= (ts/updated-at conn id) (ts/last-confirmed conn id)))
        (is (.after ^java.util.Date (ts/last-confirmed conn id) (ts/created-at conn id))))
      (testing "the invariant created-at <= updated-at <= last-confirmed holds"
        (is (not (.after ^java.util.Date (ts/created-at conn id) (ts/updated-at conn id))))
        (is (not (.after ^java.util.Date (ts/updated-at conn id) (ts/last-confirmed conn id))))))))

(deftest bare-update-confirms-without-touching-updated-at
  (let [conn ts/*conn* u "f-bare"
        id   (memory/create! conn u {:content "x" :src "f-bare-s" :tags [["domain" "clojure"]]})
        upd0 (ts/updated-at conn id)
        lc0  (ts/last-confirmed conn id)]
    (Thread/sleep 5)
    (testing "an empty-payload update confirms, returning the id"
      (is (= id (memory/update! conn u id {}))))
    (testing "the bare update moved last-confirmed forward"
      (is (.after ^java.util.Date (ts/last-confirmed conn id) lc0)))
    (testing "the bare update left updated-at untouched"
      (is (= upd0 (ts/updated-at conn id))))
    (Thread/sleep 5)
    (memory/update! conn u id {:content "y"})
    (testing "a field update still moves updated-at and last-confirmed together"
      (is (= (ts/updated-at conn id) (ts/last-confirmed conn id)))
      (is (.after ^java.util.Date (ts/updated-at conn id) upd0)))))

(deftest confirm-via-empty-put-and-bare-batch-update
  (let [conn ts/*conn* u "f-emptyput"
        id   (:id (body-json (request :post "/memories"
                                      {:user u :accept "application/json"
                                       :body {:content "x" :src "f-emptyput-s" :tags [["domain" "clojure"]]}})))
        upd0 (ts/updated-at conn id)
        lc0  (ts/last-confirmed conn id)]
    (Thread/sleep 5)
    (testing "an empty-body PUT confirms, returning 200 and the id"
      (let [resp (request :put (str "/memories/" id) {:user u :accept "application/json" :body {}})]
        (is (== 200 (:status resp)))
        (is (= id (:id (body-json resp))))))
    (testing "the empty PUT moved last-confirmed forward and left updated-at"
      (is (.after ^java.util.Date (ts/last-confirmed conn id) lc0))
      (is (= upd0 (ts/updated-at conn id))))
    (let [lc1 (ts/last-confirmed conn id)]
      (Thread/sleep 5)
      (testing "a bare :id update inside a batch confirms, with an applied count of one"
        (let [resp (request :post "/memories/batch" {:user u :accept "application/json" :body {:update [{:id id}]}})
              body (body-json resp)]
          (is (== 200 (:status resp)))
          (is (== 1 (:applied body)))))
      (testing "the bare batch update moved last-confirmed forward and left updated-at"
        (is (.after ^java.util.Date (ts/last-confirmed conn id) lc1))
        (is (= upd0 (ts/updated-at conn id)))))))

(deftest src-correction-put-and-batch
  (let [u     "f-srccorr"
        id    (:id (body-json (request :post "/memories"
                                       {:user u :accept "application/json"
                                        :body {:content "x" :src "f-srccorr-old" :tags [["domain" "clojure"]]}})))
        fetch (fn [] (body-json (request :get (str "/memories/" id) {:user u :accept "application/json"})))]
    (testing "a PUT that carries a new src replaces the src, returns 200 and the id"
      (let [resp (request :put (str "/memories/" id) {:user u :accept "application/json" :body {:src "f-srccorr-new"}})]
        (is (== 200 (:status resp)))
        (is (= id (:id (body-json resp))))))
    (testing "the memory reads back with the new src, content intact"
      (let [m (fetch)]
        (is (= "f-srccorr-new" (:src m)))
        (is (= "x" (:content m)))))
    (testing "an update that omits src leaves the current src unchanged"
      (request :put (str "/memories/" id) {:user u :accept "application/json" :body {:content "y"}})
      (let [m (fetch)]
        (is (= "f-srccorr-new" (:src m)))
        (is (= "y" (:content m)))))
    (testing "a malformed src on a PUT is a 400 at coercion"
      (is (== 400 (:status (request :put (str "/memories/" id) {:user u :accept "application/json" :body {:src "Bad_Src"}})))))
    (testing "a batch update that carries a src replaces the src in the same batch"
      (let [resp (request :post "/memories/batch" {:user u :accept "application/json" :body {:update [{:id id :src "f-srccorr-batch"}]}})
            body (body-json resp)]
        (is (== 200 (:status resp)))
        (is (== 1 (:applied body)))
        (is (= "f-srccorr-batch" (:src (fetch))))))))

(deftest related-repair-delete
  (let [conn ts/*conn* u "rr-del"]
    (testing "deleting the sole holder of a src removes the dangling related (action 3)"
      (let [a (memory/create! conn u {:content "a" :src "rr-del-a"})
            r (memory/create! conn u {:content "r" :src "rr-del-r" :related ["rr-del-a"]})]
        (memory/delete! conn u a)
        (is (= #{} (ts/related-set conn r)))))
    (testing "deleting one holder when a sibling keeps the src leaves the related intact"
      (let [b1 (memory/create! conn u {:content "b1" :src "rr-del-b"})
            _  (memory/create! conn u {:content "b2" :src "rr-del-b"})
            r  (memory/create! conn u {:content "rb" :src "rr-del-rb" :related ["rr-del-b"]})]
        (memory/delete! conn u b1)
        (is (= #{"rr-del-b"} (ts/related-set conn r)))))))

(deftest related-repair-rename
  (let [conn ts/*conn* u "rr-rename"]
    (testing "renaming the sole holder replaces the old src with the new (action 2)"
      (let [m (memory/create! conn u {:content "m" :src "rr-rn-old"})
            r (memory/create! conn u {:content "r" :src "rr-rn-r" :related ["rr-rn-old"]})]
        (memory/update! conn u m {:src "rr-rn-new"})
        (is (= "rr-rn-new" (ts/src-val conn m)))
        (is (= #{"rr-rn-new"} (ts/related-set conn r)))))
    (testing "renaming one holder while a sibling keeps the old src leaves referrers alone"
      (let [m1 (memory/create! conn u {:content "m1" :src "rr-rn-shared"})
            _  (memory/create! conn u {:content "m2" :src "rr-rn-shared"})
            r  (memory/create! conn u {:content "rs" :src "rr-rn-rs" :related ["rr-rn-shared"]})]
        (memory/update! conn u m1 {:src "rr-rn-moved"})
        (is (= #{"rr-rn-shared"} (ts/related-set conn r)))))
    (testing "a rename that also rewrites the memory's own related repairs others but keeps the caller's related"
      (let [m (memory/create! conn u {:content "m" :src "rr-rn-solo" :related ["rr-rn-x"]})
            r (memory/create! conn u {:content "r" :src "rr-rn-r2" :related ["rr-rn-solo"]})]
        (memory/update! conn u m {:src "rr-rn-solo2" :related ["rr-rn-y"]})
        (is (= "rr-rn-solo2" (ts/src-val conn m)))
        (is (= #{"rr-rn-y"} (ts/related-set conn m)))
        (is (= #{"rr-rn-solo2"} (ts/related-set conn r)))))))

(deftest related-repair-batch-net-effect
  (let [conn ts/*conn* u "rr-batch"]
    (testing "a batch that deletes a src and recreates it leaves referrers intact"
      (let [a (memory/create! conn u {:content "a" :src "rr-b-a"})
            r (memory/create! conn u {:content "r" :src "rr-b-r" :related ["rr-b-a"]})
            res (memory/apply-batch! conn u {:create [{:content "a-again" :src "rr-b-a"}] :delete [a]}
                                     (constantly nil) (constantly nil))]
        (is (:ok? res))
        (is (= #{"rr-b-a"} (ts/related-set conn r)))))
    (testing "a batch delete that empties a src repairs the referrer in the one transaction"
      (let [x (memory/create! conn u {:content "x" :src "rr-b-x"})
            r (memory/create! conn u {:content "rx" :src "rr-b-rx" :related ["rr-b-x"]})
            res (memory/apply-batch! conn u {:delete [x]} (constantly nil) (constantly nil))]
        (is (:ok? res))
        (is (= #{} (ts/related-set conn r)))))
    (testing "a batch update that renames the sole holder replaces the referrer's src"
      (let [m (memory/create! conn u {:content "m" :src "rr-b-p"})
            r (memory/create! conn u {:content "rp" :src "rr-b-rp" :related ["rr-b-p"]})
            res (memory/apply-batch! conn u {:update [{:id m :src "rr-b-q"}]} (constantly nil) (constantly nil))]
        (is (:ok? res))
        (is (= #{"rr-b-q"} (ts/related-set conn r)))))
    (testing "a batch src swap leaves both referrers alone, no over-reach"
      (let [x  (memory/create! conn u {:content "x" :src "rr-b-sa"})
            y  (memory/create! conn u {:content "y" :src "rr-b-sb"})
            ra (memory/create! conn u {:content "ra" :src "rr-b-ra" :related ["rr-b-sa"]})
            rb (memory/create! conn u {:content "rb" :src "rr-b-rb2" :related ["rr-b-sb"]})
            res (memory/apply-batch! conn u {:update [{:id x :src "rr-b-sb"} {:id y :src "rr-b-sa"}]}
                                     (constantly nil) (constantly nil))]
        (is (:ok? res))
        (is (= #{"rr-b-sa"} (ts/related-set conn ra)))
        (is (= #{"rr-b-sb"} (ts/related-set conn rb)))))))

(deftest related-repair-per-user
  (let [conn ts/*conn* u "rr-user" other "rr-user-bob"]
    (testing "a repair for one user never touches another user's same-named src"
      (let [a-u (memory/create! conn u {:content "a" :src "rr-user-a"})
            r-u (memory/create! conn u {:content "ra" :src "rr-user-ra" :related ["rr-user-a"]})
            _   (memory/create! conn other {:content "a" :src "rr-user-a"})
            r-o (memory/create! conn other {:content "rb" :src "rr-user-rb" :related ["rr-user-a"]})]
        (memory/delete! conn u a-u)
        (is (= #{} (ts/related-set conn r-u)))
        (is (= #{"rr-user-a"} (ts/related-set conn r-o)))))))

(deftest related-repair-only-on-src-change
  (let [conn ts/*conn* u "rr-only"
        m (memory/create! conn u {:content "m" :src "rr-only-s"})
        r (memory/create! conn u {:content "r" :src "rr-only-r" :related ["rr-only-s"]})]
    (testing "a content-only update does no repair and leaves the src"
      (memory/update! conn u m {:content "m2"})
      (is (= "rr-only-s" (ts/src-val conn m)))
      (is (= #{"rr-only-s"} (ts/related-set conn r))))
    (testing "a bare confirm does no repair"
      (memory/update! conn u m {})
      (is (= #{"rr-only-s"} (ts/related-set conn r))))))

(deftest plan-repair-for-batch-unit
  (let [conn ts/*conn* u "rr-plan"
        plan (fn [moves] (#'memory/plan-repair-for-batch (d/db conn) u moves))
        eid  (fn [id] (#'memory/eid-of (d/db conn) u id))
        m    (memory/create! conn u {:content "m" :src "rr-plan-a"})
        r    (memory/create! conn u {:content "r" :src "rr-plan-r" :related ["rr-plan-a"]})
        em   (eid m)
        er   (eid r)]
    (testing "a delete that empties the src retracts it from the referrer"
      (is (= [[:db/retract er :memory/related "rr-plan-a"]]
             (plan {:deletes [{:eid em :src "rr-plan-a"}]}))))
    (testing "a rename that empties the src repoints the referrer"
      (is (= [[:db/retract er :memory/related "rr-plan-a"]
              [:db/add er :memory/related "rr-plan-b"]]
             (plan {:renames [{:old "rr-plan-a" :new "rr-plan-b"}]}))))
    (testing "an exempt referrer is left to the batch"
      (is (empty? (plan {:deletes [{:eid em :src "rr-plan-a"}] :exempt #{er}}))))
    (testing "an empty moves map plans nothing"
      (is (empty? (plan {}))))
    (testing "a delete offset by a create at the same src is not dead, so no repair"
      (is (empty? (plan {:deletes [{:eid em :src "rr-plan-a"}] :created-srcs ["rr-plan-a"]}))))
    (memory/create! conn u {:content "m2" :src "rr-plan-a"})
    (testing "a src a sibling keeps is not dead, so no repair"
      (is (empty? (plan {:deletes [{:eid em :src "rr-plan-a"}]}))))))

(deftest tx-function-read-is-atomic-no-lost-update
  ;; the mechanism engram's writes lean on: a read-modify-write inside a
  ;; :db.fn/call commits atomically, so concurrent increments lose nothing. The
  ;; src is globally unique, because the inner query is not user-scoped.
  (let [conn ts/*conn*
        src  "ws-atomic-c"
        eid-by-src (fn [] (d/q '[:find ?e . :in $ ?s :where [?e :memory/src ?s]] (d/db conn) src))]
    (memory/create! conn "ws-atomic" {:content "c" :src src})
    (let [threads 8
          per     50
          inc!    (fn []
                    (d/transact! conn
                                 [[:db.fn/call
                                   (fn [db]
                                     (let [e   (d/q '[:find ?e . :in $ ?s :where [?e :memory/src ?s]] db src)
                                           cur (:memory/recall-lifetime (d/pull db [:memory/recall-lifetime] e))]
                                       [{:db/id e :memory/recall-lifetime (inc (or cur 0))}]))]]))
          fs (doall (repeatedly threads #(future (dotimes [_ per] (inc!)))))]
      (doseq [f fs] @f)
      (testing "concurrent read-modify-write through a transaction function loses no update"
        (is (== (* threads per)
                (:memory/recall-lifetime (d/pull (d/db conn) [:memory/recall-lifetime] (eid-by-src)))))))))

(deftest writes-racing-a-delete-leave-no-ghost
  (let [conn   ts/*conn* u "ws-ghost"
        ghost? (fn [] (seq (d/q '[:find [?e ...]
                                  :where [?e :memory/last-confirmed _]
                                         (not-join [?e] [?e :memory/user _])]
                                (d/db conn))))]
    (dotimes [_ 60]
      (let [id (memory/create! conn u {:content "m" :src "ws-ghost-s" :tags [["domain" "clojure"]]})
            f1 (future (dotimes [_ 4] (memory/confirm! conn u [id])))
            f2 (future (dotimes [_ 4] (memory/update! conn u id {:content "x"})))
            f3 (future (memory/delete! conn u id))]
        @f1 @f2 @f3))
    (testing "a confirm or an update racing a delete never resurrects a ghost memory"
      (is (not (ghost?))))))

(deftest concurrent-sibling-deletes-repair-the-referrer
  (let [conn      ts/*conn* u "ws-siblings"
        src-count (fn [src] (count (d/q '[:find [?e ...] :in $ ?u ?s
                                          :where [?e :memory/user ?u] [?e :memory/src ?s]]
                                        (d/db conn) u src)))]
    (dotimes [_ 60]
      (let [a1 (memory/create! conn u {:content "a1" :src "ws-sib-a"})
            a2 (memory/create! conn u {:content "a2" :src "ws-sib-a"})
            r  (memory/create! conn u {:content "r" :src "ws-sib-r" :related ["ws-sib-a"]})
            f1 (future (memory/delete! conn u a1))
            f2 (future (memory/delete! conn u a2))]
        @f1 @f2
        (testing "both siblings gone, so the referrer keeps no dangling link to the src"
          (is (zero? (src-count "ws-sib-a")))
          (is (= #{} (ts/related-set conn r))))
        (memory/delete! conn u r)))))

(deftest mem-recall-flush-skips-a-deleted-memory
  (let [conn ts/*conn* u "ws-skipdel"
        id   (memory/create! conn u {:content "m" :src "ws-skipdel-s"})]
    (memory/delete! conn u id)
    (testing "a recall-count flush for a gone memory plans nothing, so no ghost"
      (is (empty? (recall-count/plan-memory-recalls (d/db conn) 14 {id 3} (System/currentTimeMillis)))))))

(deftest confirm-stamps-owned-only
  (let [conn ts/*conn* u "ws-confirm" other "ws-confirm-bob"
        a (memory/create! conn u {:content "a" :src "ws-conf-a" :tags [["domain" "clojure"]]})
        b (memory/create! conn u {:content "b" :src "ws-conf-b" :tags [["domain" "clojure"]]})
        z (memory/create! conn other {:content "z" :src "ws-conf-z" :tags [["domain" "clojure"]]})
        a-upd0 (ts/updated-at conn a)
        a-lc0  (ts/last-confirmed conn a)
        z-lc0  (ts/last-confirmed conn z)]
    (Thread/sleep 5)
    (testing "confirm stamps the owned ids, drops a foreign and a missing id, collapses a repeat"
      (is (== 2 (memory/confirm! conn u [a a b z (str (java.util.UUID/randomUUID))]))))
    (testing "a confirmed memory's last-confirmed moved forward"
      (is (.after ^java.util.Date (ts/last-confirmed conn a) a-lc0)))
    (testing "confirm did not touch updated-at"
      (is (= a-upd0 (ts/updated-at conn a))))
    (testing "a foreign memory was never stamped"
      (is (= z-lc0 (ts/last-confirmed conn z))))
    (testing "a confirm of only foreign or missing ids stamps nothing"
      (is (== 0 (memory/confirm! conn u [z (str (java.util.UUID/randomUUID))]))))))

(deftest freshness-on-recall-wire-and-confirm-route
  (let [u "ws-freshwire" other "ws-freshwire-bob"
        id (:id (body-json (request :post "/memories"
                                    {:user u :accept "application/json"
                                     :body {:content "x" :src "ws-freshwire-s" :tags [["domain" "clojure"]]}})))]
    (testing "a just-created memory recalls with a fresh band"
      (let [m (first (:memories (body-json (request :post "/memories/recall/by-tags"
                                                    {:user u :accept "application/json" :body {:match ["domain" "clojure"]}}))))]
        (is (= "fresh" (:freshness m)))))
    (testing "the confirm route stamps the owned id and returns the count"
      (let [body (body-json (request :post "/memories/confirm" {:user u :accept "application/json" :body {:ids [id]}}))]
        (is (== 1 (:confirmed body)))))
    (testing "a foreign caller confirms nothing"
      (let [body (body-json (request :post "/memories/confirm" {:user other :accept "application/json" :body {:ids [id]}}))]
        (is (== 0 (:confirmed body)))))
    (testing "a non-uuid confirm id is a 400 at coercion"
      (is (== 400 (:status (request :post "/memories/confirm" {:user u :accept "application/json" :body {:ids ["not-a-uuid"]}})))))
    (testing "a confirm without the user header is rejected"
      (is (== 401 (:status (request :post "/memories/confirm" {:accept "application/json" :body {:ids [id]}})))))))

(deftest freshness-band-reflects-age-on-the-wire
  (let [conn ts/*conn* u "ws-band"
        c    (assoc ts/cfg :freshness-half-life-days 10)
        app  (handler/app conn c (ts/tag-writer conn) (ts/mem-writer conn))
        id   (:id (body-json (request app :post "/memories"
                                      {:user u :accept "application/json"
                                       :body {:content "x" :src "ws-band-s" :tags [["domain" "clojure"]]}})))]
    ;; push last-confirmed 25 days back: 2.5 half-lives against a 10-day half-life -> stale
    (d/transact! conn [{:memory/id (java.util.UUID/fromString id)
                        :memory/last-confirmed (java.util.Date. (- (System/currentTimeMillis) (* 25 day-ms)))}])
    (testing "an old memory bands as stale, and fetch omits the raw timestamps"
      (let [m (body-json (request app :get (str "/memories/" id) {:user u :accept "application/json"}))]
        (is (= "stale" (:freshness m)))
        (is (not (contains? m :last-confirmed)))
        (is (not (contains? m :updated-at)))
        (is (not (contains? m :created-at)))))))

(deftest freshness-falls-back-to-updated-at
  (let [conn ts/*conn* u "ws-fallback"
        c    (assoc ts/cfg :freshness-half-life-days 10)
        app  (handler/app conn c (ts/tag-writer conn) (ts/mem-writer conn))
        id   (java.util.UUID/randomUUID)
        old  (java.util.Date. (- (System/currentTimeMillis) (* 25 day-ms)))]
    ;; a memory that predates the field: updated-at 25 days old, no last-confirmed
    (d/transact! conn [{:memory/id id :memory/user u :memory/content "x" :memory/src "ws-fallback-s"
                        :memory/created-at old :memory/updated-at old
                        :memory/tag [{:tag/category "domain" :tag/label "clojure"}]}])
    (testing "with no last-confirmed, freshness falls back to updated-at, so 25 days is stale at a 10-day half-life"
      (let [m (body-json (request app :get (str "/memories/" (str id)) {:user u :accept "application/json"}))]
        (is (= "stale" (:freshness m)))))))

;; ---------- stale route (ticket 16) ----------

(deftest stale-orders-by-confirm-priority
  (let [conn ts/*conn* u "st-order" other "st-order-bob"]
    ;; freshness half-life 30 (stale at 60 days or older), recall half-life 14.
    (ts/seed-stale! conn u "st-order-used" 90 4.0)    ; stale, recent 4 -> high priority
    (ts/seed-stale! conn u "st-order-old"  300 1.0)   ; very stale, recent 1
    (ts/seed-stale! conn u "st-order-cold" 90 nil)    ; stale, never recalled -> priority 0
    (ts/seed-stale! conn u "st-order-fresh" 10 5.0)   ; not stale (10 days) -> excluded
    ;; created long ago but confirmed now: passes the created-at pre-filter, yet
    ;; its effective last-confirmed is now, so the stale test drops it.
    (d/transact! conn [{:memory/id (java.util.UUID/randomUUID)
                        :memory/user u :memory/content "revived" :memory/src "st-order-revived"
                        :memory/created-at (java.util.Date. (- (System/currentTimeMillis) (* 300 86400000)))
                        :memory/updated-at (java.util.Date.)
                        :memory/last-confirmed (java.util.Date.)
                        :memory/recall-decayed 5.0 :memory/last-recalled (java.util.Date.)
                        :memory/tag [{:tag/category "domain" :tag/label "clojure"}]}])
    (ts/seed-stale! conn other "st-order-secret" 90 2.0)
    (let [srcs (map :src (memory/stale conn u 30 14))]
      (testing "the caller's stale memories come back ordered by confirm-priority"
        (is (= ["st-order-used" "st-order-old" "st-order-cold"] srcs)))
      (testing "a fresh memory is excluded"
        (is (not (some #{"st-order-fresh"} (set srcs)))))
      (testing "an old-created but recently-confirmed memory is dropped by the stale test"
        (is (not (some #{"st-order-revived"} (set srcs)))))
      (testing "another user's stale memory never appears"
        (is (not (some #{"st-order-secret"} (set srcs))))))))

(deftest handler-stale-route
  (let [conn ts/*conn* u "st-route" other "st-route-bob"]
    (ts/seed-stale! conn u "st-route-used" 90 4.0)
    (ts/seed-stale! conn u "st-route-cold" 90 nil)
    (ts/seed-stale! conn u "st-route-fresh" 10 5.0)
    (ts/seed-stale! conn other "st-route-secret" 90 2.0)
    (let [resp  (request :get "/memories/stale" {:user u :accept "application/x-ndjson"})
          lines (->> (slurp (:body resp)) str/split-lines (remove str/blank?))
          mems  (map #(json/read-value % json/keyword-keys-object-mapper) (rest lines))
          srcs  (map :src mems)]
      (testing "the response is an NDJSON stream with a header line"
        (is (= "application/x-ndjson" (get-in resp [:headers "Content-Type"])))
        (is (true? (:header (json/read-value (first lines) json/keyword-keys-object-mapper)))))
      (testing "only the caller's stale memories appear, the used one before the cold one"
        (is (= ["st-route-used" "st-route-cold"] srcs)))
      (testing "every returned memory carries the stale freshness band"
        (is (every? #(= "stale" (:freshness %)) mems)))
      (testing "a fresh memory and another user's memory never appear"
        (is (not (some #{"st-route-fresh" "st-route-secret"} (set srcs))))))
    (testing "a request without the user header is rejected"
      (is (== 401 (:status (request :get "/memories/stale" {:accept "application/x-ndjson"})))))))

;; ---------- short-term memories (ticket 19) ----------

(deftest short-term-crud
  (let [u "st-crud" other "st-crud-bob"]
    (testing "a create returns 201 and a server-assigned id"
      (let [resp (request :post "/memories/short-term" {:user u :body {:thought-process "task-a" :text "first note"}})]
        (is (== 201 (:status resp)))
        (is (string? (:id (body-json resp))))))
    (testing "a non-kebab thought-process and an empty text are 400"
      (is (== 400 (:status (request :post "/memories/short-term" {:user u :body {:thought-process "Bad_Name" :text "x"}}))))
      (is (== 400 (:status (request :post "/memories/short-term" {:user u :body {:thought-process "task-a" :text ""}})))))
    (testing "a read returns the note, and a stranger cannot read it"
      (let [id  (:id (body-json (request :post "/memories/short-term" {:user u :body {:thought-process "task-a" :text "readable"}})))
            got (body-json (request :get (str "/memories/short-term/" id) {:user u}))]
        (is (= "readable" (:text got)))
        (is (= "task-a" (:thought-process got)))
        (is (string? (:created-at got)))
        (is (string? (:updated-at got)))
        (is (== 404 (:status (request :get (str "/memories/short-term/" id) {:user other}))))))
    (testing "an update replaces the text and stamps updated-at, leaving created-at"
      (let [id     (:id (body-json (request :post "/memories/short-term" {:user u :body {:thought-process "task-a" :text "before"}})))
            before (body-json (request :get (str "/memories/short-term/" id) {:user u}))
            _      (is (== 200 (:status (request :put (str "/memories/short-term/" id) {:user u :body {:text "after"}}))))
            after  (body-json (request :get (str "/memories/short-term/" id) {:user u}))]
        (is (= "after" (:text after)))
        (is (= (:created-at before) (:created-at after)))
        (is (not (neg? (compare (:updated-at after) (:created-at after)))))))
    (testing "an update on a stranger's note is 404"
      (let [id (:id (body-json (request :post "/memories/short-term" {:user u :body {:thought-process "task-a" :text "mine"}})))]
        (is (== 404 (:status (request :put (str "/memories/short-term/" id) {:user other :body {:text "theirs"}}))))))
    (testing "a delete removes the note, and a second delete is 404"
      (let [id (:id (body-json (request :post "/memories/short-term" {:user u :body {:thought-process "task-a" :text "doomed"}})))]
        (is (= {:deleted id} (body-json (request :delete (str "/memories/short-term/" id) {:user u}))))
        (is (== 404 (:status (request :delete (str "/memories/short-term/" id) {:user u}))))))))

(deftest short-term-ordering
  (let [conn ts/*conn* u "st-ord"
        t (fn [ms] (java.util.Date. (long ms)))]
    (ts/st-insert! conn u "task-a" "middle" (t 2000))
    (ts/st-insert! conn u "task-a" "oldest" (t 1000))
    (ts/st-insert! conn u "task-a" "newest" (t 3000))
    (testing "a bucket streams oldest first"
      (is (= ["oldest" "middle" "newest"]
             (mapv :text (short-term/stream-bucket conn u "task-a")))))))

(deftest short-term-search-aggregate
  (let [u "st-agg" other "st-agg-bob"]
    (request :post "/memories/short-term" {:user u :body {:thought-process "task-a" :text "write the datalevin migration"}})
    (request :post "/memories/short-term" {:user u :body {:thought-process "task-a" :text "review the pull request"}})
    (request :post "/memories/short-term" {:user u :body {:thought-process "task-b" :text "buy some milk"}})
    (request :post "/memories/short-term" {:user other :body {:thought-process "task-a" :text "secret migration note"}})
    (testing "an empty body returns each bucket with its note count, scoped to the caller"
      (is (= {:thought-processes {:task-a 2 :task-b 1}}
             (body-json (request :post "/memories/short-term/search" {:user u})))))
    (testing "a non-empty body adds the number of matching notes"
      (is (= {:thought-processes {:task-a 2 :task-b 1} :matches 1}
             (body-json (request :post "/memories/short-term/search" {:user u :body {:search "migration"}})))))
    (testing "a blank or non-string search is 400"
      (is (== 400 (:status (request :post "/memories/short-term/search" {:user u :body {:search "   "}}))))
      (is (== 400 (:status (request :post "/memories/short-term/search" {:user u :body {:search 5}})))))))

(deftest short-term-search-stream
  (let [u "st-stream" other "st-stream-bob"]
    (request :post "/memories/short-term" {:user u :body {:thought-process "task-a" :text "write the datalevin migration"}})
    (request :post "/memories/short-term" {:user u :body {:thought-process "task-a" :text "review the pull request"}})
    (request :post "/memories/short-term" {:user other :body {:thought-process "task-a" :text "secret migration note"}})
    (testing "a thought-process with an empty body streams the whole bucket as NDJSON"
      (let [resp  (request :post "/memories/short-term/search" {:user u :query "thought_process=task-a"})
            lines (->> (slurp (:body resp)) str/split-lines (remove str/blank?))
            hdr   (json/read-value (first lines) json/keyword-keys-object-mapper)
            notes (map #(json/read-value % json/keyword-keys-object-mapper) (rest lines))]
        (is (= "application/x-ndjson" (get-in resp [:headers "Content-Type"])))
        (is (true? (:header hdr)))
        (is (= "task-a" (:thought-process hdr)))
        (is (= 2 (count notes)))))
    (testing "a thought-process with a search term streams only the matches, header carrying the term"
      (let [resp  (request :post "/memories/short-term/search" {:user u :query "thought_process=task-a" :body {:search "migration"}})
            lines (->> (slurp (:body resp)) str/split-lines (remove str/blank?))
            hdr   (json/read-value (first lines) json/keyword-keys-object-mapper)
            notes (map #(json/read-value % json/keyword-keys-object-mapper) (rest lines))]
        (is (= "migration" (:search hdr)))
        (is (= ["write the datalevin migration"] (mapv :text notes)))))))

(deftest short-term-wipe
  (let [u "st-wipe" other "st-wipe-bob"]
    (request :post "/memories/short-term" {:user u :body {:thought-process "task-a" :text "one"}})
    (request :post "/memories/short-term" {:user u :body {:thought-process "task-a" :text "two"}})
    (request :post "/memories/short-term" {:user u :body {:thought-process "task-b" :text "keep"}})
    (request :post "/memories/short-term" {:user other :body {:thought-process "task-a" :text "another keeps this"}})
    (testing "a wipe deletes the whole bucket and reports the count"
      (is (= {:deleted 2} (body-json (request :delete "/memories/short-term" {:user u :query "thought_process=task-a"})))))
    (testing "the wiped bucket is gone, the caller's other bucket stays, and the stranger's bucket is untouched"
      (is (= {:thought-processes {:task-b 1}} (body-json (request :post "/memories/short-term/search" {:user u}))))
      (is (= {:thought-processes {:task-a 1}} (body-json (request :post "/memories/short-term/search" {:user other})))))
    (testing "wiping an empty bucket reports zero"
      (is (= {:deleted 0} (body-json (request :delete "/memories/short-term" {:user u :query "thought_process=gone"})))))
    (testing "a wipe without a thought_process is 400"
      (is (== 400 (:status (request :delete "/memories/short-term" {:user u})))))))

(deftest short-term-isolated-from-memories-and-search
  (let [conn ts/*conn* u "st-iso"
        mid  (memory/create! conn u {:content "pipeline deploy fact" :src "st-iso-s" :tags [["domain" "clojure"]]})
        sid  (:id (body-json (request :post "/memories/short-term" {:user u :body {:thought-process "task-a" :text "pipeline task note"}})))]
    (testing "a short-term note never appears in GET /memories"
      (let [lines (->> (slurp (:body (request :get "/memories" {:user u :accept "application/x-ndjson"})))
                       str/split-lines (remove str/blank?))
            mems  (map #(json/read-value % json/keyword-keys-object-mapper) (rest lines))
            ids   (set (map :id mems))]
        (is (contains? ids mid))
        (is (not (contains? ids sid)))))
    (testing "a short-term full-text search never matches a Memory's content"
      (is (= 1 (short-term/match-count conn u "pipeline"))))))

;; ---------- short-term batch (ticket 25c) ----------

(deftest short-term-batch-crud
  (let [conn ts/*conn* u "stb-crud"
        a    (short-term/create! conn u "proc" "a text")
        b    (short-term/create! conn u "proc" "b text")
        resp (request :post "/memories/short-term/batch"
                      {:user u :accept "application/json"
                       :body {:create [{:thought-process "proc" :text "c text"}]
                              :update [{:id a :text "a edited"}]
                              :delete [b]}})
        body (body-json resp)]
    (testing "a mixed batch returns 200 with one new id and the applied count"
      (is (== 200 (:status resp)))
      (is (= 1 (count (:ids body))))
      (is (= 3 (:applied body))))
    (testing "the update, the delete, and the create all applied"
      (is (= "a edited" (:text (short-term/fetch conn u a))))
      (is (nil? (short-term/fetch conn u b)))
      (is (= 2 (get (short-term/buckets conn u) "proc"))))))

(deftest short-term-batch-conflict-rejects
  (let [conn ts/*conn* u "stb-conflict"
        a    (short-term/create! conn u "proc" "a")
        resp (request :post "/memories/short-term/batch"
                      {:user u :accept "application/json" :body {:update [{:id a :text "x"}] :delete [a]}})
        body (body-json resp)]
    (is (== 422 (:status resp)))
    (is (some #(= "conflict" (:error %)) (:errors body)))
    (testing "nothing was written"
      (is (= "a" (:text (short-term/fetch conn u a)))))))

(deftest short-term-batch-duplicate-update-id-rejects
  (let [conn ts/*conn* u "stb-dup"
        a    (short-term/create! conn u "proc" "a")
        resp (request :post "/memories/short-term/batch"
                      {:user u :accept "application/json" :body {:update [{:id a :text "x"} {:id a :text "y"}]}})
        errs (:errors (body-json resp))
        dups (filter #(= "duplicate-id" (:error %)) errs)]
    (is (== 422 (:status resp)))
    (testing "every occurrence of the duplicated id is flagged, by index"
      (is (= 2 (count dups)))
      (is (= #{0 1} (set (map :i dups)))))
    (testing "nothing was written"
      (is (= "a" (:text (short-term/fetch conn u a)))))))

(deftest short-term-batch-not-found-and-isolation
  (let [conn ts/*conn* u "stb-nf" other "stb-nf-bob"
        a     (short-term/create! conn other "proc" "theirs")
        ghost (str (java.util.UUID/randomUUID))
        resp  (request :post "/memories/short-term/batch"
                       {:user u :accept "application/json" :body {:update [{:id a :text "x"}] :delete [ghost]}})
        errs  (:errors (body-json resp))]
    (is (== 422 (:status resp)))
    (testing "a foreign note id and an unknown id both report not-found"
      (is (= 2 (count (filter #(= "not-found" (:error %)) errs)))))
    (testing "the other user's note is untouched"
      (is (= "theirs" (:text (short-term/fetch conn other a)))))))

(deftest short-term-batch-delete-dedups
  (let [conn ts/*conn* u "stb-dedup"
        a    (short-term/create! conn u "proc" "a")
        resp (request :post "/memories/short-term/batch" {:user u :accept "application/json" :body {:delete [a a]}})
        body (body-json resp)]
    (is (== 200 (:status resp)))
    (testing "a repeated delete counts once and removes the note"
      (is (= 1 (:applied body)))
      (is (nil? (short-term/fetch conn u a))))))

(deftest short-term-batch-conflict-beats-duplicate-id
  (let [conn ts/*conn* u "stb-beats"
        a     (short-term/create! conn u "proc" "a")
        resp  (request :post "/memories/short-term/batch"
                       {:user u :accept "application/json"
                        :body {:update [{:id a :text "x"} {:id a :text "y"}] :delete [a]}})
        codes (->> (:errors (body-json resp)) (filter #(= "update" (:op %))) (map :error) set)]
    (is (== 422 (:status resp)))
    (testing "an id both repeated in update and present in delete reports conflict, not duplicate-id"
      (is (= #{"conflict"} codes)))))
