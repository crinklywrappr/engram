(ns engram.expr
  "The match and search expression algebra, shared by the recall compiler in
  `engram.memory` and the search transform in `engram.handler`.

  An expression is a leaf, or an operator node. A node is a one-key map: `{:and
  [child ...]}`, `{:or [child ...]}`, or `{:not child}`. Everything else is a
  leaf, including a search `{:phrase ...}` map, because its key is not an operator.

  `transform` folds an expression bottom-up over an explicit work stack, so an
  arbitrarily deep expression never grows the call stack. A plain recursive walk
  would, because the recursion sits in a non-tail position, so a deep enough
  expression would overflow. The fold keeps the whole traversal on the heap.")

(def ^:private operators #{:and :or :not})

(defn op-node?
  "True when `x` is an operator node: a map whose single key is :and, :or, or
  :not."
  [x]
  (and (map? x) (contains? operators (key (first x)))))

(defn- children
  "The child expressions of an operator node, as a vector. An :and or :or node
  carries a vector of children. A :not node carries a single child."
  [x]
  (let [[op arg] (first x)]
    (if (= :not op) [arg] (vec arg))))

(defn transform
  "Fold `expr` bottom-up, stack-safe. `leaf` maps a leaf to a result. `node` maps
  an operator keyword and the vector of its already-transformed children to a
  result. A :not node hands `node` a one-element child vector. The traversal
  rides an explicit work stack, so depth never touches the call stack."
  [leaf node expr]
  (loop [work    [[:visit expr]]
         results []]
    (if (empty? work)
      (peek results)
      (let [[tag a b] (peek work)
            work      (pop work)]
        (case tag
          ;; Push the build marker first, then the children reversed, so the
          ;; children pop in order and land on `results` ahead of the build.
          :visit (if (op-node? a)
                   (let [kids (children a)]
                     (recur (into (conj work [:build (key (first a)) (count kids)])
                                  (map (fn [k] [:visit k]) (rseq kids)))
                            results))
                   (recur work (conj results (leaf a))))
          ;; The build marker's n children are the top n results, in order.
          :build (let [cut  (- (count results) b)
                       kids (subvec results cut)]
                   (recur work (conj (subvec results 0 cut) (node a kids)))))))))
