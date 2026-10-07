(ns engram.search-config
  "The full-text search engine configuration, shared by the running system and
  the tests so both index memories the same way.

  Datalevin builds one search index over every :db/fulltext attribute, which for
  engram is a memory's content and its src. The default analyzer keeps a hyphen
  inside a token, so a kebab src like engram-deploy would match only as its whole
  slug. This analyzer adds the hyphen to the token separators, so the same src is
  searchable by its words, for example deploy. :include-text? is on because
  re-index needs the stored text, and :index-position? is on so a later phrase
  search needs no second re-index."
  (:require [datalevin.search-utils :as su]))

;; Datalevin's default English punctuation separators, plus the hyphen. The
;; hyphen is what the default analyzer keeps inside a token, so adding it here is
;; the whole point: it splits a kebab src into its words.
(def ^:private token-separators
  #"[\s:/\.;,!=?\"'\(\)\[\]{}|<>&@#\^\*\\~`\-]+")

(def analyzer
  (su/create-analyzer
   {:tokenizer     (su/create-regexp-tokenizer token-separators)
    :token-filters [su/lower-case-token-filter su/en-stop-words-token-filter]}))

(def engine-opts
  "The Datalevin search-engine options: the hyphen-splitting analyzer, stored
  text for re-index, and term positions for a future phrase search."
  {:analyzer analyzer :include-text? true :index-position? true})

(def conn-opts
  "The get-conn option map. It carries engine-opts under the :search-opts key,
  which is where Datalevin looks for the default search domain's options."
  {:search-opts engine-opts})

;; ---------- re-index decision ----------
;;
;; Datalevin offers no "is the index stale?" check, but it does persist the
;; search-opts (analyzer included, as an inter-fn) in its own opts store, and the
;; full-text attribute set is readable from the schema. So the boot can decide
;; whether a rebuild is due by comparing intent against persisted state, rather
;; than re-indexing every time. See .scratch/engram-effectiveness/
;; reindex-detection-research.md for the source trail.

(defn fulltext-attrs
  "The set of attributes a schema marks full-text. Pass `(datalevin.core/schema
  conn)`."
  [schema]
  (into #{} (keep (fn [[attr m]] (when (:db/fulltext m) attr))) schema))

(defn opts-signature
  "A comparable fingerprint of a search-opts map: the analyzer rendered to its
  source form, plus the two index-structure flags. Two option maps that build the
  same index share a signature. The analyzer is an inter-fn, so its printed form
  is stable and comparable; a plain function would not persist at all."
  [search-opts]
  {:analyzer        (some-> (:analyzer search-opts) pr-str)
   :include-text?   (boolean (:include-text? search-opts))
   :index-position? (boolean (:index-position? search-opts))})

(defn reindex?
  "Whether the persisted index is stale against engram's intended search
  configuration. `persisted-search-opts` is the `:search-opts` from
  `(datalevin.core/opts conn)` read before the serving open overwrites it.
  `fulltext-before` and `fulltext-after` are the full-text attribute sets around
  the migration run. A rebuild is due when the analyzer or an index-structure
  flag changed, or when the migration changed which attributes are full-text."
  [persisted-search-opts fulltext-before fulltext-after]
  (or (not= (opts-signature persisted-search-opts) (opts-signature engine-opts))
      (not= fulltext-before fulltext-after)))
