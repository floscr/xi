(ns xi.markdown.diff
  "Rendered-markdown diffs. A line diff of a markdown file is unreadable as
   source (long soft-wrapped paragraphs, markup noise), so the web client
   re-diffs it at the block level instead: each hunk's old side (context +
   deleted lines) and new side (context + added lines) are parsed as markdown
   (xi.markdown.parse), list items are split into blocks of their own, and the
   two block sequences are LCS-diffed. Unchanged blocks render as context,
   removed / added blocks get a red / green band, and inside a changed run the
   words that actually differ are marked (`mark-words`)."
  (:require [clojure.string :as str]
            [xi.markdown.parse :as parse]))

(def markdown-exts
  "File extensions treated as markdown."
  #{"md" "markdown" "mdown" "markdn" "mkd" "mdx"})

(defn markdown-path?
  "True when `path` names a markdown file (by extension)."
  [path]
  (boolean (when-let [[_ ext] (re-find #"\.([^./]+)$" (or path ""))]
             (contains? markdown-exts (str/lower-case ext)))))

;; ── Edit script ──────────────────────────────────────────────────────────────

(def ^:private max-lcs-cells
  "Largest LCS table (rows × cols, after trimming the common prefix/suffix)
   edit-script builds; past it the middle is reported as one replace."
  4000000)

(defn- lcs-ops
  "Edit ops for the (already prefix/suffix-trimmed) vectors `a` → `b`."
  [a b k]
  (let [m (count a) n (count b)]
    (if (> (* m n) max-lcs-cells)
      (concat (map (fn [x] [:- x]) a) (map (fn [x] [:+ x]) b))
      (let [ka (mapv k a) kb (mapv k b)
            ;; dp[i][j] = LCS length of a[i..] and b[j..]
            dp (let [rows (make-array (inc m))]
                 (dotimes [i (inc m)] (aset rows i (js/Int32Array. (inc n))))
                 (loop [i (dec m)]
                   (when (>= i 0)
                     (loop [j (dec n)]
                       (when (>= j 0)
                         (aset (aget rows i) j
                               (if (= (nth ka i) (nth kb j))
                                 (inc (aget (aget rows (inc i)) (inc j)))
                                 (max (aget (aget rows (inc i)) j)
                                      (aget (aget rows i) (inc j)))))
                         (recur (dec j))))
                     (recur (dec i))))
                 rows)]
        (loop [i 0 j 0 out (transient [])]
          (cond
            (and (< i m) (< j n) (= (nth ka i) (nth kb j)))
            (recur (inc i) (inc j) (conj! out [:= (nth b j)]))

            (and (< i m) (or (>= j n)
                             (>= (aget (aget dp (inc i)) j) (aget (aget dp i) (inc j)))))
            (recur (inc i) j (conj! out [:- (nth a i)]))

            (< j n)
            (recur i (inc j) (conj! out [:+ (nth b j)]))

            :else (persistent! out)))))))

(defn- deletes-first
  "Within every run of changes, move the deletions ahead of the insertions, so
   a changed run reads as \"old, then new\"."
  [ops]
  (->> ops
       (partition-by #(= := (first %)))
       (mapcat (fn [run]
                 (if (= := (ffirst run))
                   run
                   (concat (filter #(= :- (first %)) run)
                           (filter #(= :+ (first %)) run)))))))

(defn edit-script
  "LCS edit script from `a` to `b`: a vector of [op x], op one of := (x taken
   from `b`), :- (from `a`) and :+ (from `b`). Items compare by `(k x)`
   (default identity). Deletions precede insertions within a changed run."
  ([a b] (edit-script a b identity))
  ([a b k]
   (let [a (vec a) b (vec b)
         m (count a) n (count b)
         pre (loop [p 0]
               (if (and (< p m) (< p n) (= (k (nth a p)) (k (nth b p)))) (recur (inc p)) p))
         suf (loop [s 0]
               (if (and (< s (- m pre)) (< s (- n pre))
                        (= (k (nth a (- m s 1))) (k (nth b (- n s 1)))))
                 (recur (inc s))
                 s))]
     (vec (concat (map (fn [x] [:= x]) (subvec b 0 pre))
                  (deletes-first (lcs-ops (subvec a pre (- m suf)) (subvec b pre (- n suf)) k))
                  (map (fn [x] [:= x]) (subvec b (- n suf))))))))

;; ── Hunks ────────────────────────────────────────────────────────────────────

(defn tool-diff-hunks
  "Hunks of an edit/write tool's diff text (xi.tools.util/unified-diff: `- `,
   `+ `, `  ` prefixed lines, `...` between hunks; the path line, the
   `(created new file)` note and the `[file-hash: …]` trailer are skipped).
   Each hunk is a vector of {:type :add|:delete|:context :text}."
  [text]
  (->> (str/split-lines (or text ""))
       (reduce (fn [hunks l]
                 (let [add (fn [type] (update hunks (dec (count hunks)) conj
                                              {:type type :text (subs l 2)}))]
                   (cond
                     (contains? #{"..." "…"} l)  (conj hunks [])
                     (str/starts-with? l "+ ")  (add :add)
                     (str/starts-with? l "- ")  (add :delete)
                     (str/starts-with? l "  ")  (add :context)
                     :else                      hunks)))
               [[]])
       (filterv seq)))

(defn unified-hunks
  "Hunks of one parsed git diff file (xi.diff/parse-diff-text), in the
   tool-diff-hunks shape."
  [{:keys [hunks]}]
  (->> hunks
       (map (fn [h] (filterv #(not= :meta (:type %)) (:lines h))))
       (filterv seq)))

(defn- explode
  "Split a parsed block into diff units: every list item becomes a one-item
   list of its own (an ordered item keeps its number as :start), so editing
   one bullet doesn't mark the whole list as replaced."
  [block]
  (case (first block)
    (:ul :ol)
    (let [[tag items children] block]
      (map-indexed (fn [i item]
                     (let [child (nth children i nil)]
                       (cond-> {:block (cond-> [tag [item]] child (conj [child]))}
                         (= :ol tag) (assoc :start (inc i)))))
                   items))

    :checkbox-list
    (map (fn [item] {:block [:checkbox-list [item]]}) (second block))

    [{:block block}]))

(defn- side-units [lines types]
  (->> lines
       (filter #(contains? types (:type %)))
       (map :text)
       (str/join "\n")
       parse/parse
       (mapcat explode)))

(defn hunk-items
  "Block-level diff of one hunk: a vector of {:status :ctx|:del|:add :block
   <parsed markdown block> :start n?}."
  [lines]
  (mapv (fn [[op unit]]
          (assoc unit :status (case op := :ctx :- :del :+ :add)))
        (edit-script (side-units lines #{:context :delete})
                     (side-units lines #{:context :add})
                     :block)))

(defn diff-segments
  "Render segments for a set of hunks: {:kind :ctx :items [items]} (a run of
   unchanged blocks), {:kind :change :del [items] :add [items]} (a run of
   changed blocks) and {:kind :gap} between hunks. Hunks whose blocks didn't
   change (whitespace-only edits) drop out."
  [hunks]
  (let [per-hunk (fn [lines]
                   (->> (hunk-items lines)
                        (partition-by #(= :ctx (:status %)))
                        (map (fn [run]
                               (if (= :ctx (:status (first run)))
                                 {:kind :ctx :items (vec run)}
                                 {:kind :change
                                  :del (filterv #(= :del (:status %)) run)
                                  :add (filterv #(= :add (:status %)) run)})))))]
    (->> hunks
         (map per-hunk)
         (filter #(some (comp #{:change} :kind) %))
         (interpose [{:kind :gap}])
         (apply concat)
         vec)))

;; ── Word marks ───────────────────────────────────────────────────────────────

(defn- hiccup-children
  "Child nodes of hiccup element `node` (skipping its attribute map)."
  [node]
  (let [[_ a & kids] node]
    (if (map? a) kids (cons a kids))))

(defn- leaves
  "The text leaves of hiccup `nodes`, in document order."
  [nodes]
  (mapcat (fn [n]
            (cond (string? n) [n]
                  (and (vector? n) (keyword? (first n))) (leaves (hiccup-children n))
                  (seq? n) (leaves n)
                  :else nil))
          nodes))

(def ^:private token-re
  "Words (letters, digits, _), whitespace runs, or single other characters."
  "[\\p{L}\\p{N}_]+|\\s+|[^\\p{L}\\p{N}_\\s]")

(defn- tokens [s]
  (vec (or (.match s (js/RegExp. token-re "gu")) #js [])))

(defn- blank-token? [t] (str/blank? t))

(defn- changed-ranges
  "Char ranges [start end) of the word tokens of `toks` not in the common
   subsequence `kept?` (a vector of booleans, one per token). Whitespace is
   marked only between two marked words, so adjacent changed words merge into
   one band and the spacing around a change stays unmarked."
  [toks kept?]
  (let [n      (count toks)
        word?  (mapv (complement blank-token?) toks)
        marked (mapv (fn [w k] (and w (not k))) word? kept?)
        ;; marked-ness of the nearest word before / after each token
        scan   (fn [is] (first (reduce (fn [[acc last-w] i]
                                         [(assoc acc i last-w)
                                          (if (word? i) (marked i) last-w)])
                                       [(vec (repeat n false)) false]
                                       is)))
        before (scan (range n))
        after  (scan (range (dec n) -1 -1))
        mark?  (fn [i] (or (marked i)
                           (and (not (word? i)) (before i) (after i))))]
    (loop [i 0 pos 0 out []]
      (if (= i n)
        out
        (let [end (+ pos (count (nth toks i)))]
          (recur (inc i) end
                 (if (mark? i)
                   (if (and (seq out) (= pos (second (peek out))))
                     (conj (pop out) [(first (peek out)) end])
                     (conj out [pos end]))
                   out)))))))

(defn- split-leaf
  "`s` (starting at char `off` of the joined text) cut by `ranges` into plain
   strings and [:span.cls] marks; s itself when nothing in it is marked."
  [s off ranges cls]
  (let [end (+ off (count s))
        hits (filter (fn [[a b]] (and (< a end) (> b off))) ranges)]
    (if (empty? hits)
      s
      (loop [pos off [[a b] & more] hits out []]
        (if (nil? a)
          (seq (cond-> out (< pos end) (conj (subs s (- pos off)))))
          (let [a (max a off) b (min b end)]
            (recur b more
                   (cond-> out
                     (< pos a) (conj (subs s (- pos off) (- a off)))
                     :always   (conj [:span {:class cls} (subs s (- a off) (- b off))])))))))))

(defn- mark-nodes
  "Rewrite the text leaves of `nodes` (walked in `leaves` order), wrapping the
   parts inside `ranges` (char offsets into the joined leaf text) in
   [:span {:class cls}]."
  [nodes ranges cls]
  (let [off (volatile! 0)
        walk (fn walk [n]
               (cond (string? n)
                     (let [o @off]
                       (vswap! off + (count n))
                       (split-leaf n o ranges cls))
                     (and (vector? n) (keyword? (first n)))
                     (let [[tag a] n
                           attrs? (map? a)]
                       (into (if attrs? [tag a] [tag])
                             (map walk)
                             (hiccup-children n)))
                     (seq? n) (doall (map walk n))
                     :else n))]
    (mapv walk nodes)))

(def ^:private min-word-similarity
  "Fraction of the shorter side's words the sides must share; below it a
   changed run counts as rewritten, where marking nearly every word would only
   add noise. Measured against the shorter side so a block that grew a lot
   (old text kept, much added) still gets its insertions marked."
  0.5)

(def ^:private max-word-cells
  "Largest token LCS table mark-words builds before giving up on marks."
  1000000)

(defn mark-words
  "Mark the words that differ between the rendered hiccup of a changed run's
   deleted blocks (`del-nodes`) and added blocks (`add-nodes`): returns
   [del-nodes add-nodes] with the differing words wrapped in
   span.md-diff-word--del / span.md-diff-word--add. Unchanged when either side
   is empty, the sides are too large, or they share too few words."
  [del-nodes add-nodes]
  (let [old-t (tokens (apply str (leaves del-nodes)))
        new-t (tokens (apply str (leaves add-nodes)))]
    (if (or (empty? old-t) (empty? new-t)
            (> (* (count old-t) (count new-t)) max-word-cells))
      [del-nodes add-nodes]
      (let [idx   (fn [ts] (vec (map-indexed vector ts)))
            ops   (edit-script (idx old-t) (idx new-t) second)
            ;; := keeps b's [i tok]; recover the matched a-indices by walking
            ;; the ops in order alongside a.
            [old-kept new-kept]
            (loop [[op & more] ops ai 0 ok (vec (repeat (count old-t) false))
                   nk (vec (repeat (count new-t) false))]
              (if (nil? op)
                [ok nk]
                (case (first op)
                  := (recur more (inc ai) (assoc ok ai true) (assoc nk (first (second op)) true))
                  :- (recur more (inc ai) ok nk)
                  :+ (recur more ai ok nk))))
            words  (fn [ts kept] (keep-indexed (fn [i t] (when-not (blank-token? t) (nth kept i))) ts))
            nw     (fn [ts] (count (remove blank-token? ts)))
            shared (count (filter true? (words new-t new-kept)))
            sim    (/ shared (max 1 (min (nw old-t) (nw new-t))))]
        (if (< sim min-word-similarity)
          [del-nodes add-nodes]
          [(mark-nodes del-nodes (changed-ranges old-t old-kept) "md-diff-word--del")
           (mark-nodes add-nodes (changed-ranges new-t new-kept) "md-diff-word--add")])))))
