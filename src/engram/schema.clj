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

(def RecallBody
  [:map [:pairs [:vector config/Pair]]])

;; A memory id on the wire: the string form of a UUID. Anchored, because malli
;; `:re` uses `re-find`. A non-uuid id is a malformed op -> 400 at coercion.
(def IdStr
  [:re #"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"])

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
(def MemoryOut
  [:map
   [:id :string] [:content :string] [:src :string]
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
;; configuration, a single decimal truncated to four places.
(def StatsOut
  [:map [:stats [:map
                 [:link-density [:map
                                 [:avg-out-degree number?]
                                 [:largest-wcc-fraction number?]]]
                 [:conforming-fraction number?]]]])
;; /recalls carries the bare recall rows with no :stats wrapper, each a positional
;; tuple [category label count lifetime recent]. The /stats envelope keeps its own
;; copy until ticket 08 moves the rows here.
(def RecallsOut [:map [:recalls [:vector [:tuple :string :string :int :int number?]]]])
(def IdOut      [:map [:id :string]])
(def DeletedOut [:map [:deleted :string]])
(def ErrorOut   [:map [:error :string]])
;; 409 keeps :configurations so the client can refresh; coercion strips undeclared
;; keys, so the schema must name every key the body carries.
(def Conflict   [:map [:error :string] [:message :string]
                       [:configurations Configurations]])
;; The NDJSON recall is a stream, which response coercion cannot check, so the
;; recall route declares no :responses. This is the JSON fallback shape.
(def RecallOut   [:map [:pairs [:vector [:tuple :string :string]]]
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
