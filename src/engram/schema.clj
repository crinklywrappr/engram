(ns engram.schema
  "The wire schemas for the reitit REST API: request bodies and response bodies.

  Request schemas are validated by malli coercion, so a malformed shape returns
  400. Response schemas are coerced too, and coercion strips any key a schema does
  not name, so each response schema names every key its body must keep."
  (:require [engram.config :as config]
            [malli.core :as m]))

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
   [:src {:optional true} config/Token]
   [:tags {:optional true} [:vector config/Pair]]
   [:related {:optional true} [:vector config/Token]]])

;; A match expression (ticket 22): a nested boolean rule over tag pairs. A leaf
;; is a [category label] pair. :and and :or take one or more children. :not takes
;; exactly one child. The recursive shape is a malli registry ref, so coercion
;; rejects a malformed expression (a zero-arg :and, a multi-child :not, a stray
;; key) with 400. Each operator map is closed, so it carries exactly one key.
(def Match
  [:schema {:registry {::m [:or
                            config/Pair
                            [:map {:closed true} [:and [:vector {:min 1} [:ref ::m]]]]
                            [:map {:closed true} [:or  [:vector {:min 1} [:ref ::m]]]]
                            [:map {:closed true} [:not [:ref ::m]]]]}}
   [:ref ::m]])

(def RecallByTagsBody
  [:map [:match Match]])

;; A search expression (ticket 22): the full-text analogue of a match expression.
;; A leaf is a non-empty term string or a {:phrase "..."} object. :and, :or, and
;; :not nest to any depth, mapping onto Datalevin's native search grammar. A bare
;; string is a whole expression, the simple search path.
(def SearchExpr
  [:schema {:registry {::s [:or
                            [:string {:min 1}]
                            [:map {:closed true} [:phrase [:string {:min 1}]]]
                            [:map {:closed true} [:and [:vector {:min 1} [:ref ::s]]]]
                            [:map {:closed true} [:or  [:vector {:min 1} [:ref ::s]]]]
                            [:map {:closed true} [:not [:ref ::s]]]]}}
   [:ref ::s]])

(defn valid-search?
  "True when `x` is a well-formed search expression. The short-term search route
  reads an optional raw body, so it validates programmatically rather than by
  coercion."
  [x]
  (m/validate SearchExpr x))

;; /memories/search request. The search string is required and non-empty, so a
;; missing or empty value coerces to 400. The handler also rejects a blank
;; (whitespace) string. limit and categories are optional: the handler defaults
;; limit to 20 and clamps it to 100, and projects the result tags to the named
;; categories. A categories entry is a bare category string, not a pair.
(def SearchBody
  [:map
   [:search SearchExpr]
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

;; /memories/recall/by-src request. A recall selected by a list of source names.
;; Each src is a kebab-case token, so a non-token src is a 400 at coercion. An
;; empty vector is well-formed and selects nothing.
(def RecallBySrcBody
  [:map [:srcs [:vector config/Token]]])

;; /memories/confirm request. A batch of memory ids to restamp as confirmed.
;; Each id is the IdStr form, so a non-uuid id is a 400 at coercion. An empty
;; vector is well-formed and stamps nothing.
(def ConfirmBody
  [:map [:ids [:vector IdStr]]])

(def UpdatePayload
  [:map
   [:id IdStr]
   [:content {:optional true} [:string {:min 1}]]
   [:src {:optional true} config/Token]
   [:tags {:optional true} [:vector config/Pair]]
   [:related {:optional true} [:vector config/Token]]])

;; /memories/short-term create. A note under a thought-process. The
;; thought-process is a kebab-case Token, so a non-kebab value coerces to 400.
;; The text is non-empty, so an empty text coerces to 400.
(def ShortTermCreateBody
  [:map
   [:thought-process config/Token]
   [:text [:string {:min 1}]]])

;; /memories/short-term/:id update. A PUT replaces the note's text only.
(def ShortTermUpdateBody
  [:map [:text [:string {:min 1}]]])

;; The batch body: a map of grouped operations, each key optional. Order carries
;; no meaning across groups (see docs/adr/0002-batch-request-grouped-map.md). A
;; wrong-typed group is a malformed request -> 400 at coercion.
(def BatchBody
  [:map
   [:create {:optional true} [:vector CreateBody]]
   [:update {:optional true} [:vector UpdatePayload]]
   [:delete {:optional true} [:vector IdStr]]])

;; An index entry request (ticket 25, amended by 25b): one stored recall body,
;; tagged by kind. A by-tags request carries a Match expression, a by-src request
;; a list of source names, and a thought-process request a kebab-case process
;; name. The payload shape is validated per kind by a :multi dispatch, so a by-src
;; payload under a by-tags kind coerces to 400. The required? flag is optional and
;; defaults to false in the handler. Each branch map is closed, so a stray key is
;; a 400. The index carries no by-ids request: a migrated or curated source is
;; named by src, which by-src pins without a search.
(def IndexRequest
  [:multi {:dispatch :kind}
   ["by-tags"         [:map {:closed true}
                       [:kind [:= "by-tags"]]
                       [:payload Match]
                       [:required {:optional true} :boolean]]]
   ["by-src"          [:map {:closed true}
                       [:kind [:= "by-src"]]
                       [:payload [:vector config/Token]]
                       [:required {:optional true} :boolean]]]
   ["thought-process" [:map {:closed true}
                       [:kind [:= "thought-process"]]
                       [:payload config/Token]
                       [:required {:optional true} :boolean]]]])

;; POST /index and PUT /index/:id request. An entry carries a kebab-case title, a
;; non-empty free-text hook, and one or more requests. The server mints the id, so
;; the body never carries one.
(def IndexEntryBody
  [:map
   [:title config/Token]
   [:hook [:string {:min 1}]]
   [:requests [:vector {:min 1} IndexRequest]]])

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
;; GET /index returns the caller's live entries. A request hands its payload back
;; as the parsed JSON it stored, which is a match tree, an id list, or a string
;; by kind, so :payload is :any. :required is always present on the wire.
(def IndexRequestOut
  [:map
   [:kind [:enum "by-tags" "by-src" "thought-process"]]
   [:payload :any]
   [:required :boolean]])
(def IndexEntryOut
  [:map
   [:id :string] [:title :string] [:hook :string]
   [:requests [:vector IndexRequestOut]]])
(def IndexOut [:map [:entries [:vector IndexEntryOut]]])
;; A short-term note on the wire: kebab-case keys and ISO-8601 timestamps.
(def ShortTermOut
  [:map
   [:id :string] [:thought-process :string] [:text :string]
   [:created-at :string] [:updated-at :string]])
;; A bucket wipe echoes the number of notes it deleted, which can be 0.
(def ShortTermWipedOut [:map [:deleted :int]])
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
