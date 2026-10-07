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

(def MemoryOut
  [:map
   [:id :string] [:content :string] [:src :string]
   [:related [:vector :string]]
   [:tags [:vector [:tuple :string :string]]]])

;; /config carries the configurations and, when the admin describes them, the
;; optional :categories map. Response coercion strips undeclared keys, so both
;; are named here.
(def ConfigOut
  [:map
   [:configurations [:vector [:map-of :string :string]]]
   [:categories {:optional true}
    [:map-of :string [:map
                      [:description {:optional true} :string]
                      [:examples {:optional true} [:vector :string]]]]]])
;; /stats nests the recall rows under :stats then :recalls, so a later stat type
;; can sit beside recalls without breaking the envelope. Each row is a positional
;; tuple [category label count lifetime recent]. The pair and count come from the
;; caller's memories, so a never-recalled pair still appears with a lifetime of 0
;; and a recent of 0.0. :link-density sits beside :recalls: the mean src out-degree
;; and the largest weakly-connected component as a fraction of the src nodes.
(def StatsOut
  [:map [:stats [:map
                 [:recalls [:vector [:tuple :string :string :int :int number?]]]
                 [:link-density [:map
                                 [:avg-out-degree number?]
                                 [:largest-wcc-fraction number?]]]]]])
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
                       [:configurations [:vector [:map-of :string :string]]]])
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
                           [:configurations {:optional true} [:vector [:map-of :string :string]]]]]]])
