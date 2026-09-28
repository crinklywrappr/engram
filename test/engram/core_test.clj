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
            [engram.stats :as stats]
            [jsonista.core :as json]
            [malli.core :as m]
            [syncopate.core :as sc])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def cfg (let [c (config/load-config "deploy/engram-config.example.edn")]
           (assoc c :tag-schema (config/compile-tag-schema c))))

(defn fresh-conn
  "A migrated, reopened connection on a fresh temp dir (mirrors the system)."
  []
  (let [dir (.toString (Files/createTempDirectory "engram-test" (make-array FileAttribute 0)))
        c   (d/get-conn dir)]
    (sc/migrate-all! (sc/store c) migrations/migrations)
    (d/close c)
    (d/get-conn dir)))

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

;; ---------- memory: create, transitive query, isolation ----------

(deftest transitive-query-and-isolation
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
      (let [rows (memory/query conn "alice" [["domain" "clojure"]])
            srcs (set (map :src rows))]
        (testing "the pair match is returned"
          (is (contains? srcs "a")))
        (testing "the transitive related-by-src memory is pulled in"
          (is (contains? srcs "b")))
        (testing "another user's memory is never returned"
          (is (not (contains? srcs "z")))
          (is (not (some #(= "bob secret" (:content %)) rows)))))
      (finally (d/close conn)))))

(deftest query-traversal-shapes
  (testing "a cycle terminates and returns both nodes"
    (let [conn (fresh-conn)]
      (try
        (memory/create! conn "alice" {:content "c1" :src "c1" :tags [["domain" "clojure"]] :related ["c2"]})
        (memory/create! conn "alice" {:content "c2" :src "c2" :tags [["misc" "m"]] :related ["c1"]})
        (is (= #{"c1" "c2"} (set (map :src (memory/query conn "alice" [["domain" "clojure"]])))))
        (finally (d/close conn)))))
  (testing "a diamond returns the shared child once"
    (let [conn (fresh-conn)]
      (try
        (memory/create! conn "alice" {:content "m1" :src "m1" :tags [["domain" "clojure"]] :related ["x"]})
        (memory/create! conn "alice" {:content "m2" :src "m2" :tags [["domain" "clojure"]] :related ["x"]})
        (memory/create! conn "alice" {:content "x" :src "x" :tags [["misc" "m"]]})
        (let [srcs (map :src (memory/query conn "alice" [["domain" "clojure"]]))]
          (is (= #{"m1" "m2" "x"} (set srcs)))
          (is (== 1 (count (filter #{"x"} srcs)))))
        (finally (d/close conn)))))
  (testing "a self-loop returns the memory once and terminates"
    (let [conn (fresh-conn)]
      (try
        (memory/create! conn "alice" {:content "s" :src "s" :tags [["domain" "clojure"]] :related ["s"]})
        (is (= ["s"] (map :src (memory/query conn "alice" [["domain" "clojure"]]))))
        (finally (d/close conn)))))
  (testing "a related pointing at a nonexistent src yields nothing extra"
    (let [conn (fresh-conn)]
      (try
        (memory/create! conn "alice" {:content "g" :src "g" :tags [["domain" "clojure"]] :related ["ghost"]})
        (is (= #{"g"} (set (map :src (memory/query conn "alice" [["domain" "clojure"]])))))
        (finally (d/close conn))))))

(deftest delete-removes-memory
  (let [conn (fresh-conn)]
    (try
      (let [id (memory/create! conn "alice" {:content "temp" :src "s"
                                             :tags [["domain" "clojure"]]})]
        (testing "the memory is present before the delete"
          (is (== 1 (count (memory/query conn "alice" [["domain" "clojure"]])))))
        (testing "delete removes it and returns the id"
          (is (= id (memory/delete! conn "alice" id)))
          (is (empty? (memory/query conn "alice" [["domain" "clojure"]])))))
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
          (is (= 1 (count (memory/query conn "alice" [["domain" "clojure"]]))))))
      (finally (d/close conn)))))

;; ---------- memory: batch apply ----------

(def ^:private create-tag-err #(config/create-tag-error (:tag-schema cfg) %))
(def ^:private update-tag-err #(config/update-tag-error (:tag-schema cfg) %))

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
                                   (memory/query conn "alice" [["domain" "clojure"]])))]
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
          (is (empty? (memory/query conn "alice" [["domain" "clojure"]])))))
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
          (is (== 1 (count (memory/query conn "alice" [["domain" "clojure"]]))))))
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
          (let [m (first (memory/query conn "alice" [["domain" "clojure"]]))]
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
          (is (= "orig" (:content (first (memory/query conn "alice" [["domain" "clojure"]])))))))
      (finally (d/close conn)))))

(deftest batch-duplicate-delete-tolerated
  (let [conn (fresh-conn)]
    (try
      (let [x   (memory/create! conn "alice" {:content "orig" :src "x" :tags [["domain" "clojure"]]})
            res (memory/apply-batch! conn "alice" {:delete [x x]} create-tag-err update-tag-err)]
        (testing "a repeated delete id is deduplicated, deletes once, no error"
          (is (true? (:ok? res)))
          (is (== 1 (:applied res)))
          (is (empty? (memory/query conn "alice" [["domain" "clojure"]])))))
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
          (is (= "orig" (:content (first (memory/query conn "alice" [["domain" "clojure"]])))))
          (is (== 1 (count (memory/query conn "alice" [["domain" "clojure"]]))))))
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
      (stats/record-fetch! conn "alice" 14 [["domain" "clojure"]])
      (stats/record-fetch! conn "alice" 14 [["domain" "clojure"]])
      (let [row (first (stats/stats conn "alice" 14))]
        (testing "lifetime counts every fetch"
          (is (= 2 (:lifetime row))))
        (testing "recent decay count is positive and bounded by lifetime"
          (is (pos? (:recent row)))
          (is (<= (:recent row) 2.0))))
      (finally (d/close conn)))))

(deftest stat-writer-serializes-and-drains
  (let [conn (fresh-conn)
        w    (stats/writer conn 14)]
    (try
      ;; 10 threads each record the same pair 5 times, concurrently
      (let [fs (doall (repeatedly 10 #(future (dotimes [_ 5]
                                                (stats/record! w "alice" [["domain" "clojure"]])))))]
        (run! deref fs))
      (stats/drain! w)
      (testing "every concurrent increment lands, none lost to a race"
        (is (== 50 (:lifetime (first (stats/stats conn "alice" 14))))))
      (finally (d/close conn)))))

(deftest stat-write-failure-is-isolated
  (let [conn (fresh-conn)
        w    (stats/writer conn 14)]
    (d/close conn)                                   ; every flush now fails
    (testing "recording never throws to the caller when the write fails"
      (is (nil? (stats/record! w "alice" [["domain" "clojure"]]))))
    (testing "draining a failing writer does not throw"
      (is (nil? (stats/drain! w))))))

(deftest plan-fetch-pure-and-coalesced
  (let [conn (fresh-conn)]
    (try
      (stats/record-fetch! conn "alice" 14 [["domain" "clojure"]])   ; seed one row
      (let [db (d/db conn)]
        (testing "a coalesced count adds to the prior lifetime and decayed weight"
          (let [tx (stats/plan-fetch db "alice" 14 {["domain" "clojure"] 3} (System/currentTimeMillis))]
            (is (== 1 (count tx)))
            (is (== 4 (:stat/lifetime (first tx))))
            (is (< 3.0 (:stat/decayed (first tx)) 4.001))))
        (testing "a pair with no prior row starts at the count"
          (let [tx (stats/plan-fetch db "bob" 14 {["domain" "clojure"] 2} (System/currentTimeMillis))]
            (is (== 2 (:stat/lifetime (first tx))))
            (is (== 2.0 (:stat/decayed (first tx))))))
        (testing "an empty pair-counts plans nothing"
          (is (= [] (stats/plan-fetch db "alice" 14 {} (System/currentTimeMillis)))))
        (testing "the planner writes nothing: the stored lifetime is still one"
          (is (== 1 (:lifetime (first (stats/stats conn "alice" 14)))))))
      (finally (d/close conn)))))

(deftest stats-injective-across-user-space
  (let [conn (fresh-conn)]
    (try
      (stats/record-fetch! conn "alice one" 14 [["domain" "clojure"]])
      (stats/record-fetch! conn "alice" 14 [["domain" "clojure"]])
      (stats/record-fetch! conn "alice" 14 [["domain" "clojure"]])
      (testing "user ids that differ by a space keep separate rows via the composite tuple"
        (is (= 1 (:lifetime (first (stats/stats conn "alice one" 14)))))
        (is (= 2 (:lifetime (first (stats/stats conn "alice" 14))))))
      (finally (d/close conn)))))

;; ---------- handler: auth, 409, streaming ----------

(defn- request
  [app method uri {:keys [user body accept]}]
  (app (cond-> {:request-method method :uri uri
                :headers (cond-> {}
                           user   (assoc "x-engram-user" user)
                           accept (assoc "accept" accept)
                           body   (assoc "content-type" "application/json"))}
         body (assoc :body (io/input-stream (.getBytes (json/write-value-as-string body) "UTF-8"))))))

(defn- body-json [resp]
  (json/read-value (slurp (:body resp)) json/keyword-keys-object-mapper))

(deftest handler-flow
  (let [conn (fresh-conn)
        app  (handler/app conn cfg (stats/writer conn (:half-life-days cfg)))]
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
      (testing "GET /config returns the configurations"
        (is (= 200 (:status (request app :get "/config" {:user "alice" :accept "application/json"})))))
      (testing "the fetch call streams NDJSON when asked"
        (let [resp  (request app :post "/memories/query"
                             {:user "alice" :accept "application/x-ndjson"
                              :body {:pairs [["domain" "clojure"]]}})
              lines (->> (slurp (:body resp)) str/split-lines (remove str/blank?))]
          (is (= "application/x-ndjson" (get-in resp [:headers "Content-Type"])))
          (is (>= (count lines) 2))                       ; header line + at least one memory
          (is (true? (:header (json/read-value (first lines) json/keyword-keys-object-mapper))))
          (is (some #(= "prefer ==" (:content (json/read-value % json/keyword-keys-object-mapper)))
                    (rest lines)))))
      (finally (d/close conn)))))

(deftest handler-delete
  (let [conn (fresh-conn)
        app  (handler/app conn cfg (stats/writer conn (:half-life-days cfg)))]
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
        app  (handler/app conn cfg (stats/writer conn (:half-life-days cfg)))]
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
        app  (handler/app conn cfg (stats/writer conn (:half-life-days cfg)))]
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
        app  (handler/app conn cfg (stats/writer conn (:half-life-days cfg)))]
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
        app  (handler/app conn cfg (stats/writer conn (:half-life-days cfg)))]
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

(deftest query-json-fallback-shape
  (let [conn (fresh-conn)
        app  (handler/app conn cfg (stats/writer conn (:half-life-days cfg)))]
    (try
      (request app :post "/memories"
               {:user "alice" :accept "application/json"
                :body {:content "prefer ==" :src "s" :tags [["domain" "clojure"]]}})
      (let [resp (request app :post "/memories/query"
                          {:user "alice" :accept "application/json"
                           :body {:pairs [["domain" "clojure"]]}})
            body (body-json resp)]
        (testing "the JSON fallback returns pairs and memories"
          (is (== 200 (:status resp)))
          (is (vector? (:pairs body)))
          (is (vector? (:memories body))))
        (testing "each memory carries the wire keys"
          (is (= #{:id :content :src :related :tags :created-at :updated-at}
                 (set (keys (first (:memories body))))))))
      (finally (d/close conn)))))

(deftest error-logging-and-correlation-id
  (let [conn (fresh-conn)
        app  (handler/app conn cfg (stats/writer conn (:half-life-days cfg)))]
    (try
      (testing "every response carries a correlation-id header"
        (is (some? (get-in (request app :get "/healthz" {:accept "application/json"})
                           [:headers "X-Engram-Request-Id"]))))
      (testing "a handler error returns 500 with the correlation id in body and header"
        (with-redefs [memory/query (fn [& _] (throw (ex-info "boom" {})))]
          (let [resp (request app :post "/memories/query"
                              {:user "alice" :accept "application/json"
                               :body {:pairs [["domain" "clojure"]]}})
                body (body-json resp)]
            (is (== 500 (:status resp)))
            (is (some? (:id body)))
            (is (= (:id body) (get-in resp [:headers "X-Engram-Request-Id"]))))))
      (finally (d/close conn)))))
