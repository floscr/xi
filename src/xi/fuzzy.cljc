(ns xi.fuzzy
  "Shared fuzzy subsequence matcher for filter UIs (e.g. the web file finder).

   Case-insensitive: the query splits on whitespace into independent terms
   (fzf-style), and every char of each term must appear in order in the text.
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

(defn- tokens
  "Lower-cased whitespace-separated terms of `query` (none when blank)."
  [query]
  (->> (str/split (str/lower-case (str query)) #"\s+")
       (remove str/blank?)))

(defn- match-token?
  "True when every char of lower-cased `q` appears in order in lower-cased `t`."
  [q t]
  (let [qlen (count q) tlen (count t)]
    (loop [qi 0 ti 0]
      (cond
        (>= qi qlen) true
        (>= ti tlen) false
        (= (.charAt q qi) (.charAt t ti)) (recur (inc qi) (inc ti))
        :else (recur qi (inc ti))))))

(defn match?
  "True when every whitespace-separated term of `query` appears as an in-order
   subsequence (case-insensitive) in `text` — spaces split independent terms
   (fzf-style), so \"plan md\" matches plans/notes.md. A blank query matches
   everything."
  [query text]
  (let [qs (tokens query)]
    (or (empty? qs)
        (let [t (str/lower-case (str text))]
          (every? #(match-token? % t) qs)))))

(defn- score-token
  "Greedy lower-is-better score of one lower-cased term `q` against
   lower-cased `t`, or nil when it is not a subsequence."
  [q t]
  (let [qlen (count q) tlen (count t)]
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
        (recur qi (inc ti) score last-match)))))

(defn score
  "Greedy lower-is-better score — the sum over `query`'s whitespace-separated
   terms, each matched independently against `text` — or nil when any term is
   not a subsequence. Blank query scores 0."
  [query text]
  (let [qs (tokens query)]
    (if (empty? qs)
      0
      (let [t (str/lower-case (str text))]
        (reduce (fn [acc q]
                  (if-let [sc (score-token q t)]
                    (+ acc sc)
                    (reduced nil)))
                0 qs)))))

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
