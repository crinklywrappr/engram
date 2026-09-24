(ns engram.handler
  "reitit REST API with a Swagger page. Every route below /healthz and the docs
  requires the trusted X-Engram-User header that engram-proxy injects. The fetch
  route streams NDJSON when asked, so an arbitrarily large response never has to
  be held whole in memory."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [engram.config :as config]
            [engram.memory :as memory]
            [engram.stats :as stats]
            [jsonista.core :as json]
            [muuntaja.core :as mc]
            [reitit.ring :as ring]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [reitit.swagger :as swagger]
            [reitit.swagger-ui :as swagger-ui]
            [ring.util.io :as rio]))

(defn- wrap-user
  "Require the trusted X-Engram-User header. Its value is the request's user id."
  [handler]
  (fn [req]
    (if-let [u (get-in req [:headers "x-engram-user"])]
      (handler (assoc req :engram/user u))
      {:status 401 :body {:error "missing X-Engram-User"}})))

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

(defn- query-handler [conn cfg req]
  (let [user  (:engram/user req)
        pairs (get-in req [:body-params :pairs])]
    (stats/record-fetch! conn user (:half-life-days cfg) pairs)
    (let [mems (memory/query conn user pairs)]
      (if (wants-ndjson? req)
        (ndjson-response {:header true :pairs pairs} mems)
        {:status 200 :body {:pairs pairs :memories (vec mems)}}))))

(defn- create-handler [conn cfg req]
  (let [user (:engram/user req)
        {:keys [content src tags related supersedes]} (:body-params req)]
    (cond
      (str/blank? content) {:status 400 :body {:error "content is required"}}
      (str/blank? src)     {:status 400 :body {:error "src is required"}}
      :else
      (if-let [err (config/validate cfg (or tags []))]
        {:status 409 :body (assoc err :configurations (:configurations cfg))}
        {:status 201
         :body {:id (memory/create! conn user {:content content :src src :tags tags
                                               :related related :supersedes supersedes})}}))))

(defn- update-handler [conn cfg req]
  (let [user (:engram/user req)
        id   (get-in req [:path-params :id])
        {:keys [content tags related]} (:body-params req)]
    (if-let [err (and tags (config/validate cfg tags))]
      {:status 409 :body (assoc err :configurations (:configurations cfg))}
      (if (memory/update! conn user id {:content content :tags tags :related related})
        {:status 200 :body {:id id}}
        {:status 404 :body {:error "not found"}}))))

(defn app
  "Build the ring handler over the (opaque) db conn and the loaded config."
  [conn cfg]
  (ring/ring-handler
   (ring/router
    [["/swagger.json"
      {:get {:no-doc true
             :swagger {:info {:title "engram" :version "0.1.0"
                              :description "Per-user atomic-fact memory server."}}
             :handler (swagger/create-swagger-handler)}}]
     ["/healthz" {:get (fn [_] {:status 200 :body {:status "ok"}})}]
     ["" {:middleware [wrap-user]}
      ["/config" {:get (fn [_] {:status 200 :body {:configurations (:configurations cfg)}})}]
      ["/stats"  {:get (fn [req] {:status 200
                                  :body {:stats (vec (stats/stats conn (:engram/user req)
                                                                  (:half-life-days cfg)))}})}]
      ["/memories"       {:post (fn [req] (create-handler conn cfg req))}]
      ["/memories/query" {:post (fn [req] (query-handler conn cfg req))}]
      ["/memories/:id"   {:put  (fn [req] (update-handler conn cfg req))}]]]
    {:conflicts nil
     :data {:muuntaja mc/instance
            :middleware [parameters/parameters-middleware
                         muuntaja/format-middleware]}})
   (ring/routes
    (swagger-ui/create-swagger-ui-handler {:path "/api-docs" :url "/swagger.json"})
    (ring/create-default-handler))))
