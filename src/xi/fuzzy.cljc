(ns xi.fuzzy
  "Shared fuzzy subsequence matcher for filter UIs (e.g. the web file finder).

   Case-insensitive: every char of the query must appear in order in the text.
   Scoring is lower-is-better and greedy (left-to-right), favouring matches that
   start early, sit at word boundaries (after / - _ . space), run contiguously,
   and live in shorter strings — so typing \"core\" ranks .../web/core.cljs
   above a path where the letters are merely scattered."
  (:require [clojure.string :as str]))

(def ^:private boundary-chars #{\/ \\ \- \_ \. \space})

(defn- boundary?
  "True when position `i` in `t` begins a word (start, or preceded by a
   separator)."
  [t i]
  (or (zero? i) (contains? boundary-chars (.charAt t (dec i)))))

(defn match?
  "True when every char of `query` appears in order (case-insensitive) in
   `text`. A blank query matches everything."
  [query text]
  (let [q (str/lower-case (str query))]
    (if (str/blank? q)
      true
      (let [t (str/lower-case (str text))
            qlen (count q) tlen (count t)]
        (loop [qi 0 ti 0]
          (cond
            (>= qi qlen) true
            (>= ti tlen) false
            (= (.charAt q qi) (.charAt t ti)) (recur (inc qi) (inc ti))
            :else (recur qi (inc ti))))))))

(defn score
  "Greedy lower-is-better score, or nil when `query` is not a subsequence of
   `text`. Blank query scores 0."
  [query text]
  (let [q (str/lower-case (str query))]
    (if (str/blank? q)
      0
      (let [t (str/lower-case (str text))
            qlen (count q) tlen (count t)]
        (loop [qi 0 ti 0 score 0 last-match -1]
          (cond
            (>= qi qlen)
            ;; Prefer shorter strings once the query is consumed.
            (+ score (quot tlen 8))

            (>= ti tlen)
            nil

            (= (.charAt q qi) (.charAt t ti))
            (let [gap   (if (neg? last-match) ti (- ti last-match 1))
                  bonus (cond
                          (boundary? t ti) -6   ;; matched at a word boundary
                          (zero? gap)      -2   ;; contiguous with previous
                          :else            0)]
              (recur (inc qi) (inc ti) (+ score gap bonus) ti))

            :else
            (recur qi (inc ti) score last-match)))))))

(defn rank
  "Return the items of `coll` whose string (via `key-fn`, default identity)
   fuzzy-matches `query`, best-first. Ties break by shorter string then
   alphabetically, so ordering is stable. A blank query returns `coll` in its
   original order. `limit` caps the number of results."
  ([query coll] (rank query coll nil))
  ([query coll {:keys [key-fn limit] :or {key-fn identity}}]
   (let [q (str query)
         result
         (if (str/blank? q)
           (vec coll)
           (->> coll
                (keep (fn [item]
                        (let [s (key-fn item)]
                          (when-let [sc (score q s)]
                            [sc s item]))))
                (sort-by (fn [[sc s _]] [sc (count s) s]))
                (mapv (fn [[_ _ item]] item))))]
     (if limit (vec (take limit result)) result))))
