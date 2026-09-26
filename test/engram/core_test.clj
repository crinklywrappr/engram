(ns engram.core-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [engram.config :as config]
            [engram.handler :as handler]
            [engram.memory :as memory]
            [engram.migrations :as migrations]
            [engram.stats :as stats]
            [jsonista.core :as json]
            [syncopate.core :as sc])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def cfg (config/load-config "deploy/engram-config.example.edn"))

(defn fresh-conn
  "A migrated, reopened connection on a fresh temp dir (mirrors the system)."
  []
  (let [dir (.toString (Files/createTempDirectory "engram-test" (make-array FileAttribute 0)))
        c   (d/get-conn dir)]
    (sc/migrate-all! (sc/store c) migrations/migrations)
    (d/close c)
    (d/get-conn dir)))

;; ---------- config validation ----------

(deftest config-validation
  (testing "a valid domain fact passes"
    (is (nil? (config/validate cfg [["domain" "clojure"]]))))
  (testing "a required category (domain +) missing fails"
    (is (= "no-configuration" (:error (config/validate cfg [["tech" "datalevin"]])))))
  (testing "an unknown category fails (categories are closed)"
    (is (= "no-configuration" (:error (config/validate cfg [["framework" "reitit"]])))))
  (testing "a non-kebab label fails"
    (is (= "label-format" (:error (config/validate cfg [["domain" "Clojure_X"]]))))))

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

(deftest delete-removes-memory
  (let [conn (fresh-conn)]
    (try
      (let [id (memory/create! conn "alice" {:content "temp" :src "s"
                                             :tags [["domain" "clojure"]]})]
        (testing "the memory is present before the delete"
          (is (= 1 (count (memory/query conn "alice" [["domain" "clojure"]])))))
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
        app  (handler/app conn cfg)]
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
                             :body {:content "bad" :src "s2" :tags [["tech" "datalevin"]]}})]
          (is (= 409 (:status resp)))
          (is (some? (:configurations (body-json resp))))))
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
        app  (handler/app conn cfg)]
    (try
      (let [resp (request app :post "/memories"
                          {:user "alice" :accept "application/json"
                           :body {:content "x" :src "s" :tags [["domain" "clojure"]]}})
            id   (:id (body-json resp))]
        (testing "another user's delete returns 404"
          (is (= 404 (:status (request app :delete (str "/memories/" id)
                                       {:user "bob" :accept "application/json"})))))
        (testing "the owner's delete returns 200"
          (is (= 200 (:status (request app :delete (str "/memories/" id)
                                       {:user "alice" :accept "application/json"}))))))
      (finally (d/close conn)))))
