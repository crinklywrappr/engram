(ns engram.handler
  "reitit REST API with a Swagger page. Every route below /healthz and the docs
  requires the trusted X-Engram-User header that engram-proxy injects. Request
  bodies are validated by malli coercion, so a malformed shape returns 400. A
  memory's tags are validated against the admin configurations, so a mismatch
  returns 409. The fetch route streams NDJSON when asked, so an arbitrarily large
  response never has to be held whole in memory."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [engram.config :as config]
            [engram.memory :as memory]
            [engram.stats :as stats]
            [jsonista.core :as json]
            [muuntaja.core :as mc]
            [reitit.coercion.malli :as rcm]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as rrc]
            [reitit.ring.middleware.exception :as exception]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [reitit.swagger :as swagger]
            [reitit.swagger-ui :as swagger-ui]
            [ring.util.io :as rio]
            [taoensso.telemere :as t]))

;; ---------- request schemas (the static shape; malli coercion -> 400) ----------

(def ^:private CreateBody
  [:map
   [:content [:string {:min 1}]]
   [:src config/Token]
   [:tags {:optional true} [:vector config/Pair]]
   [:related {:optional true} [:vector config/Token]]])

(def ^:private UpdateBody
  [:map
   [:content {:optional true} [:string {:min 1}]]
   [:tags {:optional true} [:vector config/Pair]]
   [:related {:optional true} [:vector config/Token]]])

(def ^:private QueryBody
  [:map [:pairs [:vector config/Pair]]])

;; ---------- response schemas (malli coercion -> validated + swagger) ----------

(def ^:private MemoryOut
  [:map
   [:id :string] [:content :string] [:src :string]
   [:related [:vector :string]]
   [:tags [:vector [:tuple :string :string]]]
   [:created-at [:maybe :string]] [:updated-at [:maybe :string]]])

(def ^:private ConfigOut  [:map [:configurations [:vector [:map-of :string :string]]]])
(def ^:private StatsOut   [:map [:stats [:vector [:map [:category :string] [:label :string]
                                                  [:lifetime :int] [:recent number?]]]]])
(def ^:private IdOut      [:map [:id :string]])
(def ^:private DeletedOut [:map [:deleted :string]])
(def ^:private ErrorOut   [:map [:error :string]])
;; 409 keeps :configurations so the client can refresh; coercion strips undeclared
;; keys, so the schema must name every key the body carries.
(def ^:private Conflict   [:map [:error :string] [:message :string]
                                 [:configurations [:vector [:map-of :string :string]]]])
;; The NDJSON fetch is a stream, which response coercion cannot check, so the
;; query route declares no :responses. This is the JSON fallback shape.
(def ^:private QueryOut   [:map [:pairs [:vector [:tuple :string :string]]]
                                [:memories [:vector MemoryOut]]])

;; ---------- middleware ----------

(defn- wrap-user
  "Require the trusted X-Engram-User header. Its value is the request's user id."
  [handler]
  (fn [req]
    (if-let [u (get-in req [:headers "x-engram-user"])]
      (handler (assoc req :engram/user u))
      {:status 401 :body {:error "missing X-Engram-User"}})))

(defn- wrap-log
  "Log one info line per request: user, method, path, status, and duration in
  milliseconds. It logs no memory content and no private data beyond the user id."
  [handler]
  (fn [req]
    (let [start (System/nanoTime)
          resp  (handler req)
          ms    (quot (- (System/nanoTime) start) 1000000)]
      (t/log! {:level :info :id ::request
               :data {:user   (get-in req [:headers "x-engram-user"] "-")
                      :method (name (:request-method req))
                      :path   (:uri req)
                      :status (:status resp)
                      :ms     ms}}
              "request")
      resp)))

(defn- wrap-error
  "Catch an unhandled error, log it, and return 500. Logs the user and path, not
  the request body."
  [handler]
  (fn [req]
    (try
      (handler req)
      (catch Throwable e
        (t/log! {:level :error :id ::error :error e
                 :data {:user (get-in req [:headers "x-engram-user"] "-")
                        :path (:uri req)}}
                "handler error")
        {:status 500
         :headers {"Content-Type" "application/json"}
         :body (json/write-value-as-string {:error "internal error"})}))))

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

(defn- query-handler [conn cfg req]
  (let [user  (:engram/user req)
        pairs (get-in req [:parameters :body :pairs])]
    (stats/record-fetch! conn user (:half-life-days cfg) pairs)
    (let [mems (memory/query conn user pairs)]
      (if (wants-ndjson? req)
        (ndjson-response {:header true :pairs pairs} mems)
        {:status 200 :body {:pairs pairs :memories (vec mems)}}))))

(defn- create-handler [conn cfg req]
  (let [user (:engram/user req)
        {:keys [content src tags related]} (get-in req [:parameters :body])]
    (if-let [err (config/tag-error (:tag-schema cfg) (or tags []))]
      (do (t/log! {:level :warn :id ::rejected :data {:user user :error (:error err)}} "rejected")
          {:status 409 :body (assoc err :configurations (:configurations cfg))})
      {:status 201
       :body {:id (memory/create! conn user {:content content :src src :tags tags
                                             :related related})}})))

(defn- update-handler [conn cfg req]
  (let [user (:engram/user req)
        id   (get-in req [:path-params :id])
        {:keys [content tags related]} (get-in req [:parameters :body])]
    (if-let [err (and (seq tags) (config/tag-error (:tag-schema cfg) tags))]
      (do (t/log! {:level :warn :id ::rejected :data {:user user :error (:error err)}} "rejected")
          {:status 409 :body (assoc err :configurations (:configurations cfg))})
      (if (memory/update! conn user id {:content content :tags tags :related related})
        {:status 200 :body {:id id}}
        {:status 404 :body {:error "not found"}}))))

(defn- delete-handler [conn req]
  (let [user (:engram/user req)
        id   (get-in req [:path-params :id])]
    (if (memory/delete! conn user id)
      {:status 200 :body {:deleted id}}
      {:status 404 :body {:error "not found"}})))

(defn- routes
  "The reitit route table, closing over the db conn and the loaded config."
  [conn cfg]
  [["/swagger.json"
    {:get {:no-doc true
           :swagger {:info {:title "engram" :version "0.1.0"
                            :description "Per-user atomic-fact memory server."}}
           :handler (swagger/create-swagger-handler)}}]
   ["/healthz" {:get {:responses {200 {:body [:map [:status :string]]}}
                      :handler (fn [_] {:status 200 :body {:status "ok"}})}}]
   ;; The "" prefix adds no path segment; it groups the child routes so that
   ;; wrap-user gates all of them and leaves /healthz and /swagger.json open.
   ["" {:middleware [wrap-user]}
    ["/config" {:get {:responses {200 {:body ConfigOut}}
                      :handler (fn [_] {:status 200 :body {:configurations (:configurations cfg)}})}}]
    ["/stats"  {:get {:responses {200 {:body StatsOut}}
                      :handler (fn [req] {:status 200
                                          :body {:stats (vec (stats/stats conn (:engram/user req)
                                                                          (:half-life-days cfg)))}})}}]
    ["/memories"       {:post {:parameters {:body CreateBody}
                               :responses  {201 {:body IdOut} 409 {:body Conflict}}
                               :handler (fn [req] (create-handler conn cfg req))}}]
    ;; No :responses: the NDJSON stream cannot be response-coerced (see QueryOut).
    ["/memories/query" {:post {:parameters {:body QueryBody}
                               :handler (fn [req] (query-handler conn cfg req))}}]
    ["/memories/:id"   {:put    {:parameters {:body UpdateBody}
                                 :responses  {200 {:body IdOut} 404 {:body ErrorOut} 409 {:body Conflict}}
                                 :handler (fn [req] (update-handler conn cfg req))}
                        :delete {:responses {200 {:body DeletedOut} 404 {:body ErrorOut}}
                                 :handler (fn [req] (delete-handler conn req))}}]]])

(defn app
  "Build the ring handler over the (opaque) db conn and the loaded config."
  [conn cfg]
  (-> (ring/ring-handler
       (ring/router
        (routes conn cfg)
        {:conflicts nil
         :data {:coercion   rcm/coercion
                :muuntaja   mc/instance
                :middleware [parameters/parameters-middleware
                             muuntaja/format-middleware
                             exception/exception-middleware
                             rrc/coerce-response-middleware
                             rrc/coerce-request-middleware]}})
       (ring/routes
        (swagger-ui/create-swagger-ui-handler {:path "/api-docs" :url "/swagger.json"})
        (ring/create-default-handler)))
      wrap-error
      wrap-log))
