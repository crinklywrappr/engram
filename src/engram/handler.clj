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
            [engram.expr :as expr]
            [engram.index :as index]
            [engram.memory :as memory]
            [engram.middleware :as mw]
            [engram.recall-count :as recall-count]
            [engram.recall-count.writer :as rc-writer]
            [engram.schema :as schema]
            [engram.short-term :as short-term]
            [engram.stats :as stats]
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
  without holding the whole result in memory. With `after-ids`, tap each
  delivered memory's id and hand the delivered id vector to `after-ids` once the
  stream ends, so the write side gets only the ids and a partial delivery still
  reports its delivered prefix."
  ([header mems] (ndjson-response header mems nil))
  ([header mems after-ids]
   {:status 200
    :headers {"Content-Type" "application/x-ndjson"}
    :body (rio/piped-input-stream
           (fn [out]
             (with-open [w (io/writer out)]
               (.write w (json/write-value-as-string header))
               (.write w "\n")
               (let [ids (volatile! (transient []))]
                 (try
                   (doseq [m mems]
                     (.write w (json/write-value-as-string m))
                     (.write w "\n")
                     (vswap! ids conj! (:id m)))
                   (finally
                     (when after-ids (after-ids (persistent! @ids)))))))))}))

(defn- wants-ndjson? [req]
  (boolean (some-> (get-in req [:headers "accept"])
                   (str/includes? "application/x-ndjson"))))

;; ---------- handlers (bodies are already coerced into :parameters/:body) ------

(defn- positive-pairs
  "The leaf pairs of a match expression under positive polarity. A :not flips the
  polarity of every leaf beneath it, so a negated leaf is dropped and a
  double-negated leaf is kept. These are the pairs the recall leaned on, recorded
  for the recall count. The fold is stack-safe, so a deep expression never
  overflows. Each leaf carries its own polarity up, and a :not flips the set it
  receives, which is the parity of the :not nodes on the path."
  [match]
  (->> (expr/transform
        (fn [leaf] [[leaf true]])
        (fn [op kids]
          (let [leaves (apply concat kids)]
            (if (= :not op)
              (map (fn [[pair pos?]] [pair (not pos?)]) leaves)
              leaves)))
        match)
       (keep (fn [[pair pos?]] (when pos? pair)))))

(defn- recall-by-match-handler [conn cfg tag-writer mem-writer req]
  (let [user   (:engram/user req)
        match  (get-in req [:parameters :body :match])
        mems   (memory/recall-by-match conn user match (:freshness-half-life-days cfg))
        record (fn [ids] (rc-writer/record-memories! mem-writer user ids))]
    (rc-writer/record-pairs! tag-writer user (distinct (positive-pairs match)))
    (if (wants-ndjson? req)
      (ndjson-response {:header true :match match} mems record)
      (let [v (vec mems)]
        (record (mapv :id v))
        {:status 200 :body {:match match :memories v}}))))

(defn- recall-by-ids-handler [conn cfg mem-writer req]
  (let [user   (:engram/user req)
        ids    (get-in req [:parameters :body :ids])
        mems   (memory/recall-by-ids conn user ids (:freshness-half-life-days cfg))
        record (fn [rids] (rc-writer/record-memories! mem-writer user rids))]
    (if (wants-ndjson? req)
      (ndjson-response {:header true :ids ids} mems record)
      (let [v (vec mems)]
        (record (mapv :id v))
        {:status 200 :body {:ids ids :memories v}}))))

(defn- memories-handler [conn cfg req]
  (let [user (:engram/user req)]
    (ndjson-response {:header true}
                     (memory/all-memories conn user (:freshness-half-life-days cfg)))))

(defn- tag-reject
  "The conformance reject predicate for `cfg`: nil when a tag set conforms, an
  error map when it does not."
  [cfg]
  (fn [tags] (config/tag-error (:tag-schema cfg) tags)))

(defn- nonconforming-handler [conn cfg req]
  (let [user (:engram/user req)]
    (ndjson-response {:header true}
                     (memory/nonconforming conn user (tag-reject cfg)
                                           (:freshness-half-life-days cfg)))))

(defn- stale-handler [conn cfg req]
  (let [user (:engram/user req)]
    (ndjson-response {:header true}
                     (memory/stale conn user
                                   (:freshness-half-life-days cfg)
                                   (:recall-half-life-days cfg)))))

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
        rows (cond->> (recall-count/catalog conn user (:recall-half-life-days cfg))
               cats (filter (comp cats :category)))]
    {:status 200 :body {:recalls (mapv recall-row rows)}}))

(defn- trunc4
  "Truncate a number to four decimal places, no rounding."
  [x]
  (/ (Math/floor (* (double x) 10000.0)) 10000.0))

(defn- stats-handler [conn cfg req]
  (let [user (:engram/user req)
        agg  (stats/memory-aggregates conn user (tag-reject cfg)
                                      (:freshness-half-life-days cfg)
                                      (:recall-half-life-days cfg))]
    {:status 200
     :body {:stats {:link-density           (stats/link-density conn user)
                    :conforming-fraction    (trunc4 (:conforming-fraction agg))
                    :mean-freshness         (trunc4 (:mean-freshness agg))
                    :use-weighted-freshness (trunc4 (:use-weighted-freshness agg))
                    :hot-and-stale-fraction (trunc4 (:hot-and-stale-fraction agg))}}}))

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
        {:keys [content src tags related]} (get-in req [:parameters :body])]
    (if-let [err (config/update-tag-error (:tag-schema cfg) tags)]
      (reject-409 user cfg err)
      (if (memory/update! conn user id {:content content :src src :tags tags :related related})
        {:status 200 :body {:id id}}
        {:status 404 :body {:error "not found"}}))))

(defn- project-tags
  "Project a search row's tags to the categories the client named. Keep only a
  pair whose category is in `cats`; drop the :tags key when the client named no
  category or none match."
  [cats row]
  (let [kept (when cats (filterv (fn [[c _]] (cats c)) (:tags row)))]
    (if (seq kept) (assoc row :tags kept) (dissoc row :tags))))

(defn- ->search-query
  "Transform a search expression into Datalevin's native search query. A string
  term and a {:phrase s} leaf pass through unchanged. A one-key :and/:or/:not map
  becomes the [op ...] vector Datalevin parses. The fold is stack-safe, so a deep
  expression never overflows."
  [search]
  (expr/transform
   identity
   (fn [op kids] (if (= :not op) [:not (first kids)] (into [op] kids)))
   search))

(defn- search-handler [conn req]
  (let [user (:engram/user req)
        {:keys [search limit categories]} (get-in req [:parameters :body])]
    (if (and (string? search) (str/blank? search))
      {:status 400 :body {:error "search must not be blank"}}
      (let [lim  (min 100 (or limit 20))
            cats (not-empty (set categories))
            rows (mapv (partial project-tags cats)
                       (memory/search conn user (->search-query search) lim))]
        {:status 200 :body {:results rows}}))))

(defn- fetch-handler [conn cfg req]
  (let [user (:engram/user req)
        id   (get-in req [:parameters :path :id])]
    (if-let [m (memory/fetch conn user id (:freshness-half-life-days cfg))]
      {:status 200 :body m}
      {:status 404 :body {:error "not found"}})))

(defn- confirm-handler [conn req]
  (let [user (:engram/user req)
        ids  (get-in req [:parameters :body :ids])]
    {:status 200 :body {:confirmed (memory/confirm! conn user ids)}}))

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

;; ---------- short-term memory handlers ----------

(defn- short-term-create-handler [conn req]
  (let [user (:engram/user req)
        {:keys [thought-process text]} (get-in req [:parameters :body])]
    {:status 201 :body {:id (short-term/create! conn user thought-process text)}}))

(defn- short-term-fetch-handler [conn req]
  (let [user (:engram/user req)
        id   (get-in req [:parameters :path :id])]
    (if-let [m (short-term/fetch conn user id)]
      {:status 200 :body m}
      {:status 404 :body {:error "not found"}})))

(defn- short-term-update-handler [conn req]
  (let [user (:engram/user req)
        id   (get-in req [:parameters :path :id])
        text (get-in req [:parameters :body :text])]
    (if (short-term/update! conn user id text)
      {:status 200 :body {:id id}}
      {:status 404 :body {:error "not found"}})))

(defn- short-term-delete-handler [conn req]
  (let [user (:engram/user req)
        id   (get-in req [:parameters :path :id])]
    (if (short-term/delete! conn user id)
      {:status 200 :body {:deleted id}}
      {:status 404 :body {:error "not found"}})))

(defn- short-term-wipe-handler [conn req]
  (let [user    (:engram/user req)
        process (get-in req [:parameters :query :thought_process])]
    (if process
      {:status 200 :body {:deleted (short-term/wipe! conn user process)}}
      {:status 400 :body {:error "thought_process is required"}})))

(defn- short-term-search-handler
  "Without a thought-process, aggregate across buckets as JSON: the per-bucket
  counts, plus the number of matching notes when the body carries a search
  expression. With a thought-process, stream that bucket's notes as NDJSON: every
  note when the body is empty, only the matches when it carries an expression.
  The search lives in the raw body, so an absent body reads as nil. The route
  declares no body schema, so it validates the expression here. A present blank
  string, or a malformed expression, is a 400."
  [conn req]
  (let [user    (:engram/user req)
        process (get-in req [:parameters :query :thought_process])
        raw     (get-in req [:body-params :search])]
    (if (and (some? raw)
             (or (and (string? raw) (str/blank? raw))
                 (not (schema/valid-search? raw))))
      {:status 400 :body {:error "search must be a non-blank term or a valid expression"}}
      (let [q (when (some? raw) (->search-query raw))]
        (cond
          (and process q) (ndjson-response {:header true :thought-process process :search raw}
                                           (short-term/stream-matches conn user process q))
          process         (ndjson-response {:header true :thought-process process}
                                           (short-term/stream-bucket conn user process))
          q               {:status 200 :body {:thought-processes (short-term/buckets conn user)
                                              :matches (short-term/match-count conn user q)}}
          :else           {:status 200 :body {:thought-processes (short-term/buckets conn user)}})))))

;; ---------- memory index handlers (ticket 25) ----------

(defn- index-read-handler
  "Read the caller's live index. This is the garbage-collection point: it probes
  every required request in existence mode and reaps the lapsed entries before it
  returns the live ones."
  [conn req]
  (let [user (:engram/user req)]
    {:status 200 :body {:entries (index/read-live! conn user)}}))

(defn- index-create-handler [conn req]
  (let [user  (:engram/user req)
        entry (get-in req [:parameters :body])]
    {:status 201 :body {:id (index/create! conn user entry)}}))

(defn- index-replace-handler [conn req]
  (let [user  (:engram/user req)
        id    (get-in req [:parameters :path :id])
        entry (get-in req [:parameters :body])]
    (if (index/replace! conn user id entry)
      {:status 200 :body {:id id}}
      {:status 404 :body {:error "not found"}})))

(defn- index-delete-handler [conn req]
  (let [user (:engram/user req)
        id   (get-in req [:parameters :path :id])]
    (if (index/delete! conn user id)
      {:status 200 :body {:deleted id}}
      {:status 404 :body {:error "not found"}})))

(defn- routes
  "The reitit route table, closing over the db conn, the loaded config, and the
  two stat-write consumers: the tag writer for pair counts and the memory writer
  for per-memory counts."
  [conn cfg tag-writer mem-writer]
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
    ;; The per-user memory index (ticket 25). GET reaps lapsed entries, then
    ;; returns the live ones. Static paths, so /index/:id never collides with a
    ;; memory route.
    ["/index" {:get  {:responses  {200 {:body schema/IndexOut}}
                      :handler (fn [req] (index-read-handler conn req))}
               :post {:parameters {:body schema/IndexEntryBody}
                      :responses  {201 {:body schema/IdOut}}
                      :handler (fn [req] (index-create-handler conn req))}}]
    ["/index/:id" {:put    {:parameters {:path [:map [:id schema/IdStr]] :body schema/IndexEntryBody}
                            :responses  {200 {:body schema/IdOut} 404 {:body schema/ErrorOut}}
                            :handler (fn [req] (index-replace-handler conn req))}
                   :delete {:parameters {:path [:map [:id schema/IdStr]]}
                            :responses  {200 {:body schema/DeletedOut} 404 {:body schema/ErrorOut}}
                            :handler (fn [req] (index-delete-handler conn req))}}]
    ;; No :responses on GET: the NDJSON stream cannot be response-coerced (see /memories/recall/by-tags).
    ["/memories"       {:get  {:handler (fn [req] (memories-handler conn cfg req))}
                        :post {:parameters {:body schema/CreateBody}
                               :responses  {201 {:body schema/IdOut} 409 {:body schema/Conflict}}
                               :handler (fn [req] (create-handler conn cfg req))}}]
    ["/memories/batch" {:post {:parameters {:body schema/BatchBody}
                               :responses  {200 {:body schema/BatchOut} 422 {:body schema/BatchError}}
                               :handler (fn [req] (batch-handler conn cfg req))}}]
    ;; A recall selects either by a match expression over category:label pairs
    ;; (by-tags) or by a list of ids (by-ids). No :responses: the NDJSON stream
    ;; cannot be response-coerced (see schema/RecallOut). Both are static paths,
    ;; resolving ahead of /memories/:id.
    ["/memories/recall/by-tags" {:post {:parameters {:body schema/RecallByTagsBody}
                                        :handler (fn [req] (recall-by-match-handler conn cfg tag-writer mem-writer req))}}]
    ["/memories/recall/by-ids" {:post {:parameters {:body schema/RecallByIdsBody}
                                       :handler (fn [req] (recall-by-ids-handler conn cfg mem-writer req))}}]
    ;; Restamp a batch of the caller's memories as confirmed. Static path, so it
    ;; resolves ahead of /memories/:id.
    ["/memories/confirm" {:post {:parameters {:body schema/ConfirmBody}
                                 :responses  {200 {:body schema/ConfirmOut}}
                                 :handler (fn [req] (confirm-handler conn req))}}]
    ;; Static path, so it resolves ahead of /memories/:id. No :responses: NDJSON stream.
    ["/memories/nonconforming" {:get {:handler (fn [req] (nonconforming-handler conn cfg req))}}]
    ;; Static path, resolves ahead of /memories/:id. No :responses: NDJSON stream.
    ["/memories/stale" {:get {:handler (fn [req] (stale-handler conn cfg req))}}]
    ;; Static path, resolves ahead of /memories/:id. Bounded result, so it is response-coerced.
    ["/memories/search" {:post {:parameters {:body schema/SearchBody}
                                :responses  {200 {:body schema/SearchOut} 400 {:body schema/ErrorOut}}
                                :handler (fn [req] (search-handler conn req))}}]
    ;; Short-term memories live under /memories/short-term. The collection and the
    ;; /search child are static, so they resolve ahead of /memories/:id, and the
    ;; /memories/short-term/:id child sits a segment deeper, so it never collides
    ;; with /memories/:id. The search POST mixes a JSON body and an NDJSON stream,
    ;; so it declares no :responses.
    ["/memories/short-term" {:post   {:parameters {:body schema/ShortTermCreateBody}
                                      :responses  {201 {:body schema/IdOut}}
                                      :handler (fn [req] (short-term-create-handler conn req))}
                             :delete {:parameters {:query [:map [:thought_process {:optional true} config/Token]]}
                                      :responses  {200 {:body schema/ShortTermWipedOut} 400 {:body schema/ErrorOut}}
                                      :handler (fn [req] (short-term-wipe-handler conn req))}}]
    ["/memories/short-term/search" {:post {:parameters {:query [:map [:thought_process {:optional true} config/Token]]}
                                           :handler (fn [req] (short-term-search-handler conn req))}}]
    ["/memories/short-term/:id" {:get    {:parameters {:path [:map [:id schema/IdStr]]}
                                          :responses  {200 {:body schema/ShortTermOut} 404 {:body schema/ErrorOut}}
                                          :handler (fn [req] (short-term-fetch-handler conn req))}
                                 :put    {:parameters {:path [:map [:id schema/IdStr]] :body schema/ShortTermUpdateBody}
                                          :responses  {200 {:body schema/IdOut} 404 {:body schema/ErrorOut}}
                                          :handler (fn [req] (short-term-update-handler conn req))}
                                 :delete {:parameters {:path [:map [:id schema/IdStr]]}
                                          :responses  {200 {:body schema/DeletedOut} 404 {:body schema/ErrorOut}}
                                          :handler (fn [req] (short-term-delete-handler conn req))}}]
    ["/memories/:id"   {:get    {:parameters {:path [:map [:id schema/IdStr]]}
                                 :responses  {200 {:body schema/MemoryOut} 404 {:body schema/ErrorOut}}
                                 :handler (fn [req] (fetch-handler conn cfg req))}
                        :put    {:parameters {:path [:map [:id schema/IdStr]] :body schema/UpdateBody}
                                 :responses  {200 {:body schema/IdOut} 404 {:body schema/ErrorOut} 409 {:body schema/Conflict}}
                                 :handler (fn [req] (update-handler conn cfg req))}
                        :delete {:parameters {:path [:map [:id schema/IdStr]]}
                                 :responses {200 {:body schema/DeletedOut} 404 {:body schema/ErrorOut}}
                                 :handler (fn [req] (delete-handler conn req))}}]]])

(defn app
  "Build the ring handler over the (opaque) db conn, the loaded config, and the
  two stat-write consumers, the tag writer and the memory writer."
  [conn cfg tag-writer mem-writer]
  (-> (ring/ring-handler
       (ring/router
        (routes conn cfg tag-writer mem-writer)
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
