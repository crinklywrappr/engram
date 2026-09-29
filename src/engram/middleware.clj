(ns engram.middleware
  "Cross-cutting HTTP plumbing for the reitit REST API: the user gate, the
  per-request log line, the correlation id, and the exception handlers.

  wrap-user requires the trusted X-Engram-User header that engram-proxy injects,
  so no route below it ever sees an unauthenticated or malformed user id."
  (:require [engram.config :as config]
            [reitit.ring.middleware.exception :as exception]
            [taoensso.telemere :as t]))

(defn wrap-user
  "Require the trusted X-Engram-User header and hold it to the token shape. A
  missing header is a 401. A malformed user id is a 400, so a space or any other
  out-of-shape value never reaches a stat key or a recall."
  [handler]
  (fn [req]
    (let [u (get-in req [:headers "x-engram-user"])]
      (cond
        (nil? u)                      {:status 401 :body {:error "missing X-Engram-User"}}
        (not (config/valid-token? u)) {:status 400 :body {:error "malformed X-Engram-User"}}
        :else                         (handler (assoc req :engram/user u))))))

(defn wrap-log
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

(defn wrap-request-id
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

(def exception-mw
  (exception/create-exception-middleware
   (assoc exception/default-handlers
          ::exception/default error-response
          ::exception/wrap    log-exception)))
