(ns engram.freshness
  "The freshness kernel: how current a memory is, measured from its last
  confirmation. Pure and stateless.

  The freshness value is `0.5 ^ (age / freshness-half-life)`. It is 1.0 at age
  zero, 0.5 at one half-life, and halves again each further half-life. The band
  is the coarse bucket the value falls in: fresh under one half-life, aging from
  one up to two, stale at two or beyond.

  The recall wire carries the band. Ticket 15 reads the value for its freshness
  aggregates, and ticket 16 reads one minus the value as staleness.")

(def ^:private ms-per-day 86400000.0)

(defn value
  "The freshness of a memory last confirmed `age-ms` milliseconds before now,
  given the half-life in days. 1.0 at age zero, 0.5 at one half-life, halving
  again each further half-life."
  [half-life-days age-ms]
  (Math/pow 0.5 (/ (/ (double age-ms) ms-per-day) (double half-life-days))))

(defn band
  "The freshness band for `age-ms` at the half-life in days. The band is `fresh`
  for an age under one half-life, where the value stays above 0.5. The band is
  `aging` for an age from one up to two half-lives, where the value runs from 0.5
  down to 0.25. The band is `stale` for an age at two half-lives or beyond, where
  the value is 0.25 or less."
  [half-life-days age-ms]
  (let [v (value half-life-days age-ms)]
    (cond
      (> v 0.5)  "fresh"
      (> v 0.25) "aging"
      :else      "stale")))
