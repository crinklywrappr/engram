(ns engram.errors
  "The vocabulary of write-failure codes. Each failure kind has one namespaced
  keyword, owned here. Other namespaces refer to these vars rather than spelling
  a code themselves, so the vocabulary lives in one place. The wire form of a
  code is the keyword name, so the JSON error body carries a stable short string
  and the client contract does not depend on the internal keyword.")

(def no-configuration
  "A memory has tags that match no acceptable configuration."
  ::no-configuration)

(def conflict
  "An id appears in both the update group and the delete group of a batch."
  ::conflict)

;; This code is for the short-term batch and the index batch only, not the memory
;; batch. The memory batch folds repeated updates; these two reject them.
(def duplicate-id
  "An id appears more than once in the update group of a batch."
  ::duplicate-id)

(def not-found
  "An id names no memory for this user."
  ::not-found)

(defn ->wire
  "Shape an internal error map into its JSON wire body. The :code keyword becomes
  an :error short string, so the client sees a stable code."
  [m]
  (-> m (assoc :error (name (:code m))) (dissoc :code)))
