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

;; A memory id on the wire: the string form of a UUID. Anchored, because malli
;; `:re` uses `re-find`. A non-uuid id is a malformed op -> 400 at coercion.
(def ^:private IdStr
  [:re #"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"])

(def ^:private UpdatePayload
  [:map
   [:id IdStr]
   [:content {:optional true} [:string {:min 1}]]
   [:tags {:optional true} [:vector config/Pair]]
   [:related {:optional true} [:vector config/Token]]])

;; The batch body: an ordered list of grouped [verb, payloads] runs. The verb
;; dispatches the payload shape, so a malformed op or an unknown verb is a 400.
(def ^:private BatchBody
  [:map
   [:ops [:vector
          [:multi {:dispatch first}
           ["create" [:tuple [:= "create"] [:vector CreateBody]]]
           ["update" [:tuple [:= "update"] [:vector UpdatePayload]]]
           ["delete" [:tuple [:= "delete"] [:vector IdStr]]]]]]])

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
;; A passed batch echoes the new ids in create order plus an applied count. A
;; rejected batch reports only the failing ops; declare every key an entry can
;; carry, because response coercion strips the rest.
(def ^:private BatchOut   [:map [:ids [:vector :string]] [:applied :int]])
(def ^:private BatchError
  [:map [:errors [:vector [:map
                           [:i :int] [:op :string] [:error :string]
                           [:message {:optional true} :string]
                           [:configurations {:optional true} [:vector [:map-of :string :string]]]]]]])

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
               :data {:request-id (get req :engram/request-id "-")
                      :user       (get-in req [:headers "x-engram-user"] "-")
                      :method     (name (:request-method req))
                      :path       (:uri req)
                      :status     (:status resp)
                      :ms         ms}}
              "request")
      resp)))

(defn- wrap-request-id
  "Give each request a short-hex correlation id and echo it on every response as
  the X-Engram-Request-Id header, so a user can quote it."
  [handler]
  (fn [req]
    (let [id   (subs (str (java.util.UUID/randomUUID)) 0 8)
          resp (handler (assoc req :engram/request-id id))]
      (assoc-in resp [:headers "X-Engram-Request-Id"] id))))

(defn- log-exception
  "reitit ::exception/wrap: log a 5xx error with full context, then delegate to
  the matched handler. A 4xx (a coercion failure) is left to the per-request line."
  [handler e request]
  (let [resp (handler e request)]
    (when (<= 500 (:status resp))
      (t/log! {:level :error :id ::error :error e
               :data {:request-id (:engram/request-id request)
                      :user       (get-in request [:headers "x-engram-user"] "-")
                      :method     (name (:request-method request))
                      :path       (:uri request)
                      :status     (:status resp)
                      :body       (:body-params request)}}
              "handler error"))
    resp))

(defn- error-response
  "reitit ::exception/default: a 500 that carries the correlation id."
  [_e request]
  {:status 500 :body {:error "internal error" :id (:engram/request-id request)}})

(def ^:private exception-mw
  (exception/create-exception-middleware
   (assoc exception/default-handlers
          ::exception/default error-response
          ::exception/wrap    log-exception)))

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

(defn- flatten-ops
  "Flatten grouped [verb payloads] runs into an ordered op seq, giving each op a
  running 0-based index i across all payloads in listed order."
  [ops]
  (first
   (reduce (fn [[acc i] [verb payloads]]
             [(into acc (map-indexed (fn [j p] {:i (+ i j) :op verb :payload p}) payloads))
              (+ i (count payloads))])
           [[] 0] ops)))

(defn- batch-handler [conn cfg req]
  (let [user   (:engram/user req)
        ops    (flatten-ops (get-in req [:parameters :body :ops]))
        result (memory/apply-batch! conn user ops #(config/tag-error (:tag-schema cfg) %))]
    (if (:ok? result)
      {:status 200 :body {:ids (:ids result) :applied (:applied result)}}
      (let [errors (mapv (fn [e]
                           (cond-> e
                             (= "no-configuration" (:error e))
                             (assoc :configurations (:configurations cfg))))
                         (:errors result))]
        (t/log! {:level :warn :id ::batch-rejected
                 :data {:user user :failed (count errors)}} "batch rejected")
        {:status 422 :body {:errors errors}}))))

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
    ["/memories/batch" {:post {:parameters {:body BatchBody}
                               :responses  {200 {:body BatchOut} 422 {:body BatchError}}
                               :handler (fn [req] (batch-handler conn cfg req))}}]
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
                             exception-mw
                             rrc/coerce-response-middleware
                             rrc/coerce-request-middleware]}})
       (ring/routes
        (swagger-ui/create-swagger-ui-handler {:path "/api-docs" :url "/swagger.json"})
        (ring/create-default-handler)))
      wrap-log
      wrap-request-id))
