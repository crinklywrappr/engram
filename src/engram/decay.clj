(ns engram.decay
  "The recall-recency decay kernel. Pure, stateless, and schema-free: the
  exponential `0.5 ^ (elapsed / half-life)` curve that ages a recall count. The
  tag counts in `engram.stats` and the per-memory count in `engram.memory` both
  decay through it. Confirmation freshness has the same shape but keeps its own
  curve in `engram.freshness`, so the two senses stay free to diverge."
  (:import [java.util Date]))

(def ^:private ms-per-day 86400000.0)

(defn- fraction
  "The fraction of a weight that remains after `elapsed-ms`, given the half-life
  in days. 1.0 at zero elapsed, 0.5 at one half-life, halving again each further
  half-life."
  [half-life-days elapsed-ms]
  (Math/pow 0.5 (/ (/ (double elapsed-ms) ms-per-day) (double half-life-days))))

(defn project
  "Project a stored decayed `weight` to `clock`: the weight scaled by the decay
  since `last`, a stored Date. The caller guards a missing `last`."
  [half-life-days weight ^Date last clock]
  (* (double weight) (fraction half-life-days (- clock (.getTime last)))))
