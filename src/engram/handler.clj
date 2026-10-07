(ns engram.handler
  "reitit REST API with a Swagger page. Every route below /healthz and the docs
  requires the trusted X-Engram-User header that engram-proxy injects. Request
  bodies are validated by malli coercion, so a malformed shape returns 400. A
  memory's tags are validated against the admin configurations, so a mismatch
  returns 409. The recall route streams NDJSON when asked, so an arbitrarily large
  response never has to be held whole in memory.

  The wire schemas live in `engram.schema`. The cross-cutting middleware lives in
  `engram.middleware`. This namespace holds the handlers, the route table, and the
  `app` entry point."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [engram.build-info :as build-info]
            [engram.config :as config]
            [engram.errors :as errors]
            [engram.memory :as memory]
            [engram.middleware :as mw]
            [engram.schema :as schema]
            [engram.stats :as stats]
            [engram.stats.writer :as stat-writer]
            [jsonista.core :as json]
            [muuntaja.core :as mc]
            [reitit.coercion.malli :as rcm]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as rrc]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [reitit.swagger :as swagger]
            [reitit.swagger-ui :as swagger-ui]
            [ring.util.io :as rio]
            [taoensso.telemere :as t]))

(defn- ndjson-response
  "Stream a header line, then one JSON line per memory, without truncation and
  without holding the whole result in memory."
  [header mems]
  {:status 200
   :headers {"Content-Type" "application/x-ndjson"}
   :body (rio/piped-input-stream
          (fn [out]
            (with-open [w (io/writer out)]
              (.write w (json/write-value-as-string header))
              (.write w "\n")
              (doseq [m mems]
                (.write w (json/write-value-as-string m))
                (.write w "\n")))))})

(defn- wants-ndjson? [req]
  (boolean (some-> (get-in req [:headers "accept"])
                   (str/includes? "application/x-ndjson"))))

;; ---------- handlers (bodies are already coerced into :parameters/:body) ------

(defn- recall-handler [conn writer req]
  (let [user  (:engram/user req)
        pairs (get-in req [:parameters :body :pairs])]
    (stat-writer/record! writer user pairs)
    (let [mems (memory/recall conn user pairs)]
      (if (wants-ndjson? req)
        (ndjson-response {:header true :pairs pairs} mems)
        {:status 200 :body {:pairs pairs :memories (vec mems)}}))))

(defn- memories-handler [conn req]
  (let [user (:engram/user req)]
    (ndjson-response {:header true} (memory/all-memories conn user))))

(defn- tag-reject
  "The conformance reject predicate for `cfg`: nil when a tag set conforms, an
  error map when it does not."
  [cfg]
  (fn [tags] (config/tag-error (:tag-schema cfg) tags)))

(defn- nonconforming-handler [conn cfg req]
  (let [user (:engram/user req)]
    (ndjson-response {:header true} (memory/nonconforming conn user (tag-reject cfg)))))

(defn- recall-row
  "The positional recall-count row the wire carries: category, label, count,
  lifetime, recent."
  [{:keys [category label count lifetime recent]}]
  [category label count lifetime recent])

(defn- parse-categories
  "Split a comma-separated `categories` parameter into a set, or nil when absent."
  [s]
  (when (seq s)
    (not-empty (set (remove str/blank? (str/split s #","))))))

(defn- recalls-handler [conn cfg req]
  (let [user (:engram/user req)
        cats (parse-categories (get-in req [:parameters :query :categories]))
        rows (cond->> (stats/catalog conn user (:half-life-days cfg))
               cats (filter (comp cats :category)))]
    {:status 200 :body {:recalls (mapv recall-row rows)}}))

(defn- trunc4
  "Truncate a number to four decimal places, no rounding."
  [x]
  (/ (Math/floor (* (double x) 10000.0)) 10000.0))

(defn- stats-handler [conn cfg req]
  (let [user (:engram/user req)]
    {:status 200
     :body {:stats {:link-density       (stats/link-density conn user)
                    :conforming-fraction (trunc4 (memory/conforming-fraction conn user (tag-reject cfg)))}}}))

(defn- reject-409
  "Log a rejected write and return the 409 body with the current configurations
  attached, so the client can refresh its cache."
  [user cfg err]
  (t/log! {:level :warn :id ::rejected :data {:user user :error (:code err)}} "rejected")
  {:status 409 :body (errors/->wire (assoc err :configurations (:configurations cfg)))})

(defn- create-handler [conn cfg req]
  (let [user (:engram/user req)
        {:keys [content src tags related]} (get-in req [:parameters :body])]
    (if-let [err (config/create-tag-error (:tag-schema cfg) tags)]
      (reject-409 user cfg err)
      {:status 201
       :body {:id (memory/create! conn user {:content content :src src :tags tags
                                             :related related})}})))

(defn- update-handler [conn cfg req]
  (let [user (:engram/user req)
        id   (get-in req [:parameters :path :id])
        {:keys [content tags related]} (get-in req [:parameters :body])]
    (if-let [err (config/update-tag-error (:tag-schema cfg) tags)]
      (reject-409 user cfg err)
      (if (memory/update! conn user id {:content content :tags tags :related related})
        {:status 200 :body {:id id}}
        {:status 404 :body {:error "not found"}}))))

(defn- fetch-handler [conn req]
  (let [user (:engram/user req)
        id   (get-in req [:parameters :path :id])]
    (if-let [m (memory/fetch conn user id)]
      {:status 200 :body m}
      {:status 404 :body {:error "not found"}})))

(defn- delete-handler [conn req]
  (let [user (:engram/user req)
        id   (get-in req [:parameters :path :id])]
    (if (memory/delete! conn user id)
      {:status 200 :body {:deleted id}}
      {:status 404 :body {:error "not found"}})))

(defn- batch-handler [conn cfg req]
  (let [user   (:engram/user req)
        batch  (get-in req [:parameters :body])
        result (memory/apply-batch! conn user batch
                                    #(config/create-tag-error (:tag-schema cfg) %)
                                    #(config/update-tag-error (:tag-schema cfg) %))]
    (if (:ok? result)
      {:status 200 :body {:ids (:ids result) :applied (:applied result)}}
      (let [entries (mapv (fn [e]
                            (-> (cond-> e
                                  (= errors/no-configuration (:code e))
                                  (assoc :configurations (:configurations cfg)))
                                errors/->wire))
                          (:errors result))]
        (t/log! {:level :warn :id ::batch-rejected
                 :data {:user user :failed (count entries)}} "batch rejected")
        {:status 422 :body {:errors entries}}))))

(defn- routes
  "The reitit route table, closing over the db conn, the loaded config, and the
  stat-write consumer."
  [conn cfg writer]
  [["/swagger.json"
    {:get {:no-doc true
           :swagger {:info {:title "engram" :version build-info/version
                            :description "Per-user atomic-fact memory server."}}
           :handler (swagger/create-swagger-handler)}}]
   ["/healthz" {:get {:responses {200 {:body [:map [:status :string] [:version :string]]}}
                      :handler (fn [_] {:status 200 :body {:status "ok" :version build-info/version}})}}]
   ;; The "" prefix adds no path segment; it groups the child routes so that
   ;; wrap-user gates all of them and leaves /healthz and /swagger.json open.
   ["" {:middleware [mw/wrap-user]}
    ["/config" {:get {:responses {200 {:body schema/ConfigOut}}
                      :handler (fn [_]
                                 {:status 200
                                  :body (cond-> {:configurations (:configurations cfg)}
                                          (:categories cfg) (assoc :categories (:categories cfg)))})}}]
    ["/stats"  {:get {:responses {200 {:body schema/StatsOut}}
                      :handler (fn [req] (stats-handler conn cfg req))}}]
    ["/recalls" {:get {:parameters {:query [:map [:categories {:optional true} :string]]}
                       :responses  {200 {:body schema/RecallsOut}}
                       :handler (fn [req] (recalls-handler conn cfg req))}}]
    ;; No :responses on GET: the NDJSON stream cannot be response-coerced (see /memories/recall).
    ["/memories"       {:get  {:handler (fn [req] (memories-handler conn req))}
                        :post {:parameters {:body schema/CreateBody}
                               :responses  {201 {:body schema/IdOut} 409 {:body schema/Conflict}}
                               :handler (fn [req] (create-handler conn cfg req))}}]
    ["/memories/batch" {:post {:parameters {:body schema/BatchBody}
                               :responses  {200 {:body schema/BatchOut} 422 {:body schema/BatchError}}
                               :handler (fn [req] (batch-handler conn cfg req))}}]
    ;; No :responses: the NDJSON stream cannot be response-coerced (see schema/RecallOut).
    ["/memories/recall" {:post {:parameters {:body schema/RecallBody}
                                :handler (fn [req] (recall-handler conn writer req))}}]
    ;; Static path, so it resolves ahead of /memories/:id. No :responses: NDJSON stream.
    ["/memories/nonconforming" {:get {:handler (fn [req] (nonconforming-handler conn cfg req))}}]
    ["/memories/:id"   {:get    {:parameters {:path [:map [:id schema/IdStr]]}
                                 :responses  {200 {:body schema/MemoryOut} 404 {:body schema/ErrorOut}}
                                 :handler (fn [req] (fetch-handler conn req))}
                        :put    {:parameters {:path [:map [:id schema/IdStr]] :body schema/UpdateBody}
                                 :responses  {200 {:body schema/IdOut} 404 {:body schema/ErrorOut} 409 {:body schema/Conflict}}
                                 :handler (fn [req] (update-handler conn cfg req))}
                        :delete {:parameters {:path [:map [:id schema/IdStr]]}
                                 :responses {200 {:body schema/DeletedOut} 404 {:body schema/ErrorOut}}
                                 :handler (fn [req] (delete-handler conn req))}}]]])

(defn app
  "Build the ring handler over the (opaque) db conn, the loaded config, and the
  stat-write consumer."
  [conn cfg writer]
  (-> (ring/ring-handler
       (ring/router
        (routes conn cfg writer)
        {:conflicts nil
         :data {:coercion   rcm/coercion
                :muuntaja   mc/instance
                :middleware [parameters/parameters-middleware
                             muuntaja/format-middleware
                             mw/exception-mw
                             rrc/coerce-response-middleware
                             rrc/coerce-request-middleware]}})
       (ring/routes
        (swagger-ui/create-swagger-ui-handler {:path "/api-docs" :url "/swagger.json"})
        (ring/create-default-handler)))
      mw/wrap-log
      mw/wrap-request-id))
