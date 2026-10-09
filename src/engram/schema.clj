(ns engram.schema
  "The wire schemas for the reitit REST API: request bodies and response bodies.

  Request schemas are validated by malli coercion, so a malformed shape returns
  400. Response schemas are coerced too, and coercion strips any key a schema does
  not name, so each response schema names every key its body must keep."
  (:require [engram.config :as config]))

;; ---------- request schemas (the static shape; malli coercion -> 400) ----------

(def CreateBody
  [:map
   [:content [:string {:min 1}]]
   [:src config/Token]
   [:tags {:optional true} [:vector config/Pair]]
   [:related {:optional true} [:vector config/Token]]])

(def UpdateBody
  [:map
   [:content {:optional true} [:string {:min 1}]]
   [:tags {:optional true} [:vector config/Pair]]
   [:related {:optional true} [:vector config/Token]]])

(def RecallByTagsBody
  [:map [:tags [:vector config/Pair]]])

;; /memories/search request. The search string is required and non-empty, so a
;; missing or empty value coerces to 400. The handler also rejects a blank
;; (whitespace) string. limit and categories are optional: the handler defaults
;; limit to 20 and clamps it to 100, and projects the result tags to the named
;; categories. A categories entry is a bare category string, not a pair.
(def SearchBody
  [:map
   [:search [:string {:min 1}]]
   [:limit {:optional true} [:int {:min 1}]]
   [:categories {:optional true} [:vector :string]]])

;; A memory id on the wire: the string form of a UUID. Anchored, because malli
;; `:re` uses `re-find`. A non-uuid id is a malformed op -> 400 at coercion.
(def IdStr
  [:re #"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"])

;; /memories/recall/by-ids request. A recall selected by a list of memory ids.
;; Each id is the IdStr form, so a non-uuid id is a 400 at coercion. An empty
;; vector is well-formed and selects nothing.
(def RecallByIdsBody
  [:map [:ids [:vector IdStr]]])

;; /memories/confirm request. A batch of memory ids to restamp as confirmed.
;; Each id is the IdStr form, so a non-uuid id is a 400 at coercion. An empty
;; vector is well-formed and stamps nothing.
(def ConfirmBody
  [:map [:ids [:vector IdStr]]])

(def UpdatePayload
  [:map
   [:id IdStr]
   [:content {:optional true} [:string {:min 1}]]
   [:tags {:optional true} [:vector config/Pair]]
   [:related {:optional true} [:vector config/Token]]])

;; The batch body: a map of grouped operations, each key optional. Order carries
;; no meaning across groups (see docs/adr/0002-batch-request-grouped-map.md). A
;; wrong-typed group is a malformed request -> 400 at coercion.
(def BatchBody
  [:map
   [:create {:optional true} [:vector CreateBody]]
   [:update {:optional true} [:vector UpdatePayload]]
   [:delete {:optional true} [:vector IdStr]]])

;; ---------- response schemas (malli coercion -> validated + swagger) ----------

;; The recall wire shape. `->wire` omits an empty tags or related, so both are
;; optional here; response coercion keeps a present one and tolerates an absent one.
;; `:freshness` is the derived band, always present on a route-served memory, one
;; of fresh, aging, or stale.
(def MemoryOut
  [:map
   [:id :string] [:content :string] [:src :string]
   [:freshness [:enum "fresh" "aging" "stale"]]
   [:related {:optional true} [:vector :string]]
   [:tags {:optional true} [:vector [:tuple :string :string]]]])

;; A configuration on the wire maps each category to a cardinality shorthand or a
;; value-set map. The map carries :cardinality and an optional :one-of vector of
;; acceptable labels (ticket 10). Response coercion strips unnamed keys, so both
;; map keys are named.
(def Configuration
  [:map-of :string
   [:or :string
    [:map
     [:cardinality :string]
     [:one-of {:optional true} [:vector :string]]]]])
(def Configurations [:vector Configuration])

;; /config carries the configurations and, when the admin describes them, the
;; optional :categories map. Response coercion strips undeclared keys, so both
;; are named here.
(def ConfigOut
  [:map
   [:configurations Configurations]
   [:categories {:optional true}
    [:map-of :string [:map
                      [:description {:optional true} :string]
                      [:examples {:optional true} [:vector :string]]]]]])
;; /stats nests its aggregates under :stats so a new aggregate can join without
;; breaking the envelope. The recall rows moved to /recalls, so :stats no longer
;; carries them. :link-density is the mean src out-degree and the largest
;; weakly-connected component as a fraction of the src nodes.
;; :conforming-fraction is the share of the caller's memories that satisfy a
;; configuration, a single decimal truncated to four places. :mean-freshness,
;; :use-weighted-freshness, and :hot-and-stale-fraction are the ticket-15
;; freshness aggregates, each a single decimal truncated to four places.
(def StatsOut
  [:map [:stats [:map
                 [:link-density [:map
                                 [:avg-out-degree number?]
                                 [:largest-wcc-fraction number?]]]
                 [:conforming-fraction number?]
                 [:mean-freshness number?]
                 [:use-weighted-freshness number?]
                 [:hot-and-stale-fraction number?]]]])
;; /recalls carries the bare recall rows with no :stats wrapper, each a positional
;; tuple [category label count lifetime recent]. The /stats envelope keeps its own
;; copy until ticket 08 moves the rows here.
(def RecallsOut [:map [:recalls [:vector [:tuple :string :string :int :int number?]]]])
;; /memories/search returns a bounded, ranked candidate list. Each row carries
;; the memory id, src, content, a raw-double relevance score, and tags projected
;; to the requested categories (the :tags key is absent when the client named no
;; category or none match). Bounded by the client limit, so unlike the recall
;; stream it can be response-coerced and shown in Swagger.
(def SearchRow
  [:map
   [:id :string] [:src :string] [:content :string]
   [:score number?]
   [:tags {:optional true} [:vector [:tuple :string :string]]]])
(def SearchOut [:map [:results [:vector SearchRow]]])
(def IdOut      [:map [:id :string]])
(def DeletedOut [:map [:deleted :string]])
;; /memories/confirm echoes the number of the caller's memories it stamped.
(def ConfirmOut [:map [:confirmed :int]])
(def ErrorOut   [:map [:error :string]])
;; 409 keeps :configurations so the client can refresh; coercion strips undeclared
;; keys, so the schema must name every key the body carries.
(def Conflict   [:map [:error :string] [:message :string]
                       [:configurations Configurations]])
;; The NDJSON recall is a stream, which response coercion cannot check, so the
;; recall route declares no :responses. This is the JSON fallback shape.
(def RecallOut   [:map [:tags [:vector [:tuple :string :string]]]
                      [:memories [:vector MemoryOut]]])
;; A passed batch echoes the new ids in create order plus an applied count. A
;; rejected batch reports only the failing ops; declare every key an entry can
;; carry, because response coercion strips the rest.
(def BatchOut   [:map [:ids [:vector :string]] [:applied :int]])
(def BatchError
  [:map [:errors [:vector [:map
                           [:i :int] [:op :string] [:error :string]
                           [:message {:optional true} :string]
                           [:configurations {:optional true} Configurations]]]]])
