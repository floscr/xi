(ns xi.tools.util
  "Shared utilities for tools."
  (:require [clojure.string :as str]
            ["node:child_process" :as cp]
            ["node:path" :as node-path]))

(defn git-root
  "Return the git repository root for `dir`, or nil if not in a git repo."
  [dir]
  (try
    (-> (cp/execSync "git rev-parse --show-toplevel"
                     #js {:cwd dir :encoding "utf8"})
        str/trim)
    (catch :default _ nil)))

(defn display-path
  "Return a relative path from the git root, falling back to `path` as-is."
  [resolved path cwd]
  (if-let [root (git-root (or cwd (.cwd js/process)))]
    (.relative node-path root resolved)
    path))

(defn- lcs-lines
  "Longest common subsequence of two line vectors. Returns a set of
   [old-idx new-idx] pairs that belong to the LCS."
  [old-lines new-lines]
  (let [m (count old-lines)
        n (count new-lines)
        ;; Build DP table
        dp (let [arr (make-array (inc m))]
             (dotimes [i (inc m)]
               (aset arr i (make-array (inc n))))
             (dotimes [i (inc m)]
               (aset (aget arr i) 0 0))
             (dotimes [j (inc n)]
               (aset (aget arr 0) j 0))
             (dotimes [i m]
               (dotimes [j n]
                 (aset (aget arr (inc i)) (inc j)
                       (if (= (nth old-lines i) (nth new-lines j))
                         (inc (aget (aget arr i) j))
                         (max (aget (aget arr (inc i)) j)
                              (aget (aget arr i) (inc j)))))))
             arr)]
    ;; Backtrack to collect matched index pairs
    (loop [i m j n acc #{}]
      (cond
        (or (zero? i) (zero? j)) acc
        (= (nth old-lines (dec i)) (nth new-lines (dec j)))
        (recur (dec i) (dec j) (conj acc [(dec i) (dec j)]))
        (> (aget (aget dp (dec i)) j)
           (aget (aget dp i) (dec j)))
        (recur (dec i) j acc)
        :else
        (recur i (dec j) acc)))))

(defn unified-diff
  "Generate a minimal unified-style diff between old-text and new-text.
   Returns a string with - / + prefixed lines grouped into hunks,
   with `context` lines of surrounding context (default 3)."
  ([old-text new-text] (unified-diff old-text new-text 3))
  ([old-text new-text context]
   (let [old-lines (vec (str/split-lines old-text))
         new-lines (vec (str/split-lines new-text))
         matched (lcs-lines old-lines new-lines)
         old-matched (into #{} (map first) matched)
         new-matched (into #{} (map second) matched)
         ;; Build raw diff entries: each is {:type :ctx/:del/:add :text s}
         ;; Walk both sequences in order of matched pairs
         entries (let [pairs (sort-by first matched)]
                   (loop [oi 0 ni 0
                          [p & ps] pairs
                          acc []]
                     (if-not p
                       ;; Remaining lines
                       (into acc
                             (concat
                              (map (fn [i] {:type :del :text (nth old-lines i)}) (range oi (count old-lines)))
                              (map (fn [i] {:type :add :text (nth new-lines i)}) (range ni (count new-lines)))))
                       (let [[pi pj] p
                             ;; deletions before this match
                             dels (map (fn [i] {:type :del :text (nth old-lines i)}) (range oi pi))
                             ;; additions before this match
                             adds (map (fn [i] {:type :add :text (nth new-lines i)}) (range ni pj))
                             ctx {:type :ctx :text (nth old-lines pi)}]
                         (recur (inc pi) (inc pj) ps
                                (into acc (concat dels adds [ctx])))))))
         ;; Find which entry indices have changes nearby (within context)
         change-indices (into #{} (keep-indexed (fn [i e] (when (not= :ctx (:type e)) i))) entries)
         visible (into #{}
                       (mapcat (fn [ci]
                                 (range (max 0 (- ci context))
                                        (min (count entries) (+ ci context 1)))))
                       change-indices)
         ;; Build output lines, inserting "..." separators for gaps
         result (loop [i 0 last-shown -1 lines []]
                  (if (>= i (count entries))
                    lines
                    (if (visible i)
                      (let [e (nth entries i)
                            gap? (> i (inc last-shown))
                            prefix (case (:type e)
                                     :del "- "
                                     :add "+ "
                                     :ctx "  ")
                            line (str prefix (:text e))]
                        (recur (inc i) i
                               (if gap?
                                 (conj lines "..." line)
                                 (conj lines line))))
                      (recur (inc i) last-shown lines))))]
     (str/join "\n" result))))

