(ns engram.memory
  "Memory storage over Datalevin. Stateless: every function takes the connection
  as its first argument. Lifecycle lives in engram.system.

  A memory is one atomic fact owned by a user. Reads and writes always filter by
  user, so one user never sees another's memories. A query matches memories by
  category:label pairs, then walks the related-by-src graph transitively to pull
  in linked memories the pairs did not name."
  (:require [datalevin.core :as d])
  (:import [java.util UUID Date]))

(def ^:private pull-pattern
  '[:memory/id :memory/content :memory/src :memory/related
    :memory/created-at :memory/updated-at
    {:memory/tag [:tag/category :tag/label]}])

(defn- ->wire
  "Shape a pulled memory into the JSON wire form (dates as strings)."
  [m]
  {:id         (str (:memory/id m))
   :content    (:memory/content m)
   :src        (:memory/src m)
   :related    (vec (:memory/related m))
   :tags       (mapv (fn [t] [(:tag/category t) (:tag/label t)]) (:memory/tag m))
   :created-at (some-> ^Date (:memory/created-at m) .toInstant str)
   :updated-at (some-> ^Date (:memory/updated-at m) .toInstant str)})

(defn- tag-tx [[category label]] {:tag/category category :tag/label label})

;; ---------- writes ----------

(defn create!
  "Create one atomic fact for `user`. `supersedes` is a seq of memory-id strings
  to mark superseded (hidden from reads). Returns the new id as a string."
  [conn user {:keys [content src tags related supersedes]}]
  (let [id  (UUID/randomUUID)
        now (Date.)
        mem (cond-> {:memory/id id
                     :memory/user user
                     :memory/content content
                     :memory/src src
                     :memory/superseded? false
                     :memory/created-at now
                     :memory/updated-at now}
              (seq tags)    (assoc :memory/tag (mapv tag-tx tags))
              (seq related) (assoc :memory/related (vec related)))
        supersede-tx (for [sid supersedes]
                       {:memory/id (UUID/fromString sid) :memory/superseded? true})]
    (d/transact! conn (into [mem] supersede-tx))
    (str id)))

(defn- eid-of [db user id-str]
  (d/q '[:find ?e .
         :in $ ?id ?u
         :where [?e :memory/id ?id] [?e :memory/user ?u]]
       db (UUID/fromString id-str) user))

(defn update!
  "Correct a fact in place. Replaces content, tags, and related when supplied.
  Returns the id string, or nil when the memory does not exist for this user."
  [conn user id {:keys [content tags related]}]
  (let [db  (d/db conn)
        eid (eid-of db user id)]
    (when eid
      (let [old-tags (d/q '[:find [?t ...] :in $ ?e :where [?e :memory/tag ?t]] db eid)
            old-rel  (d/q '[:find [?r ...] :in $ ?e :where [?e :memory/related ?r]] db eid)
            retracts (concat (map (fn [t] [:db/retractEntity t]) old-tags)
                             (map (fn [r] [:db/retract eid :memory/related r]) old-rel))
            base (cond-> {:db/id eid :memory/updated-at (Date.)}
                   (some? content) (assoc :memory/content content)
                   (seq tags)      (assoc :memory/tag (mapv tag-tx tags))
                   (seq related)   (assoc :memory/related (vec related)))]
        (d/transact! conn (concat retracts [base]))
        id))))

;; ---------- query ----------

(defn- eids-by-pair [db user [category label]]
  (d/q '[:find [?e ...]
         :in $ ?u ?c ?l
         :where [?e :memory/user ?u]
                [?e :memory/superseded? false]
                [?e :memory/tag ?t]
                [?t :tag/category ?c]
                [?t :tag/label ?l]]
       db user category label))

(defn- eids-by-srcs [db user srcs]
  (if (seq srcs)
    (d/q '[:find [?e ...]
           :in $ ?u [?s ...]
           :where [?e :memory/user ?u]
                  [?e :memory/superseded? false]
                  [?e :memory/src ?s]]
         db user (vec srcs))
    []))

(defn- related-srcs [db eids]
  (if (seq eids)
    (into #{} (d/q '[:find [?r ...]
                     :in $ [?e ...]
                     :where [?e :memory/related ?r]]
                   db (vec eids)))
    #{}))

(defn query
  "Return a lazy seq of wire memories: the pair matches for `user` plus the
  transitive related-by-src closure, deduped. Responses are not truncated."
  [conn user pairs]
  (let [db   (d/db conn)
        seed (into #{} (mapcat #(eids-by-pair db user %) pairs))]
    (loop [result    seed
           seen-srcs #{}
           frontier  (related-srcs db seed)]
      (let [new-srcs (remove seen-srcs frontier)]
        (if (empty? new-srcs)
          (map (fn [e] (->wire (d/pull db pull-pattern e))) result)
          (let [more (remove result (eids-by-srcs db user new-srcs))]
            (recur (into result more)
                   (into seen-srcs new-srcs)
                   (related-srcs db more))))))))
