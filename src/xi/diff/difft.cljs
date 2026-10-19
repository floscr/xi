(ns xi.diff.difft
  "difftastic's JSON display (`difft --display json`) → xi.diff rows: the
   difftastic consumer of the diff viewer. Input is the record stream the
   GIT_EXTERNAL_DIFF hook of xi.ext.diff.difft prints, one record per file:
   its path, difftastic's JSON and both sides' text (base64). Output is
   :split rows with `context` unchanged lines around each change; the viewer
   stacks them with xi.diff/as-layout. Pure; base64 decoding is the caller's."
  (:require [clojure.string :as str]))

(def record-sep "\u001e")

(def field-sep "\u001f")

(def default-context 3)

(defn split-records
  "The hook's output → [{:path :json :old :new}], old/new still base64. Text
   before the first record (git show's commit preamble) is dropped."
  [out]
  (into []
        (keep (fn [rec]
                (let [[path json old new] (str/split (str/replace rec #"\n+$" "") #"\u001f" -1)]
                  (when (seq path)
                    {:path path :json json :old (or old "") :new (or new "")}))))
        (rest (str/split (or out "") #"\u001e"))))

;; ── Spans ─────────────────────────────────────────────────────────────────────

(defn- utf8-width [cp]
  (cond (< cp 0x80) 1 (< cp 0x800) 2 (< cp 0x10000) 3 :else 4))

(defn- byte->index
  "String index of UTF-8 byte offset `b` in `text` (difftastic counts bytes)."
  [text b]
  (if-not (re-find #"[^\x00-\x7f]" text)
    (min b (count text))
    (loop [i 0 bytes 0]
      (if (or (>= bytes b) (>= i (count text)))
        i
        (let [cp (.codePointAt text i)]
          (recur (+ i (if (> cp 0xffff) 2 1)) (+ bytes (utf8-width cp))))))))

(defn- merge-spans
  "Sort and join [start end] spans of `text` that overlap, touch or are only
   whitespace apart, so a changed expression reads as one mark."
  [text spans]
  (reduce (fn [out [s e]]
            (let [[ps pe] (peek out)]
              (if (and pe (or (<= s pe) (str/blank? (subs text pe s))))
                (conj (pop out) [ps (max pe e)])
                (conj out [s e]))))
          []
          (sort spans)))

(defn- side-spans
  "{line-index [[start end] …]} of one side (:lhs / :rhs) of difftastic's
   chunks, as string indices into `lines`."
  [chunks k lines]
  (reduce (fn [m {{:keys [line_number changes]} k}]
            (if line_number
              (let [text (get lines line_number "")]
                (update m line_number (fnil into [])
                        (map (fn [{:keys [start end]}]
                               [(byte->index text start) (byte->index text end)]))
                        changes))
              m))
          {}
          (apply concat chunks)))

;; ── Rows ──────────────────────────────────────────────────────────────────────

(defn- lines-of
  "`text`'s lines; the empty one after a final newline doesn't count."
  [text]
  (let [ls (str/split (or text "") #"\n" -1)]
    (if (= "" (peek ls)) (pop ls) ls)))

(defn- binary? [& texts]
  (some #(str/includes? % "\u0000") texts))

(defn- whole-line?
  "Do `spans` cover all of `text` but whitespace? Then the line's tint says
   it already (difftastic marks every word of a changed prose line)."
  [text spans]
  (->> (concat [0] (apply concat spans) [(count text)])
       (partition 2)
       (every? (fn [[a b]] (str/blank? (subs text a b))))))

(defn- side
  [lines spans idx]
  (when (and idx (< idx (count lines)))
    (let [text (nth lines idx)
          sp   (some->> (get spans idx) (merge-spans text))]
      (cond-> {:n (inc idx) :text text}
        (some? sp)                                  (assoc :changed? true)
        (and (seq sp) (not (whole-line? text sp))) (assoc :spans sp)))))

(defn- hunk-runs
  "Index runs of `pairs` to show: each changed pair with `context` pairs
   around it, overlapping windows joined."
  [changed? n context]
  (let [shown (into (sorted-set)
                    (mapcat #(range (max 0 (- % context)) (min n (+ % context 1))))
                    (filter changed? (range n)))]
    (reduce (fn [runs i]
              (if (= (dec i) (peek (peek runs)))
                (conj (pop runs) (conj (peek runs) i))
                (conj runs [i])))
            []
            shown)))

(defn- hunk-header [pair-rows]
  (let [first-n (fn [k] (some #(get-in % [:line k :n]) pair-rows))]
    (str "@@" (some->> (first-n :lhs) (str " -")) (some->> (first-n :rhs) (str " +")) " @@")))

(defn- whole-side
  "Every line of a created / deleted file as changed rows of side `k`."
  [path lines k]
  (vec (map-indexed (fn [i text]
                      {:row :line :filename path
                       :line {:type :split k {:n (inc i) :text text :changed? true}}})
                    lines)))

(defn file-rows
  "One hook record ({:path :json :old :new}, old/new decoded) → its rows: the
   file row, then a hunk row before each run of `context`-padded changes."
  ([record] (file-rows record default-context))
  ([{:keys [path json old new]} context]
   (let [{:keys [status language aligned_lines chunks]}
         (js->clj (js/JSON.parse json) :keywordize-keys true)
         old-lines (lines-of old)
         new-lines (lines-of new)
         st        (cond (binary? old new)      :binary
                         (= status "created")   :added
                         (= status "deleted")   :deleted
                         :else                  :modified)
         file      (cond-> {:row :file :filename path :status st}
                     language (assoc :language language))]
     (into [file]
           (case st
             :binary  []
             :added   (into [{:row :hunk :header "@@ +1 @@"}]
                            (whole-side path new-lines :rhs))
             :deleted (into [{:row :hunk :header "@@ -1 @@"}]
                            (whole-side path old-lines :lhs))
             (let [lhs-spans (side-spans chunks :lhs old-lines)
                   rhs-spans (side-spans chunks :rhs new-lines)
                   pairs     (vec aligned_lines)
                   rows      (mapv (fn [[l r]]
                                     {:row :line :filename path
                                      :line {:type :split
                                             :lhs  (side old-lines lhs-spans l)
                                             :rhs  (side new-lines rhs-spans r)}})
                                   pairs)
                   changed?  (fn [i]
                               (let [[l r] (nth pairs i)]
                                 (or (contains? lhs-spans l) (contains? rhs-spans r))))
                   ;; the pair past a final newline has no line on either side
                   real?     (fn [r] (or (get-in r [:line :lhs]) (get-in r [:line :rhs])))]
               (if (and (empty? lhs-spans) (empty? rhs-spans))
                 [{:row :hunk :header "No syntactic changes"}]
                 (mapcat (fn [run]
                           (let [rs (filterv real? (map rows run))]
                             (when (seq rs)
                               (cons {:row :hunk :header (hunk-header rs)} rs))))
                         (hunk-runs changed? (count pairs) context)))))))))

(defn rows
  "Decoded hook records → the rows of every file, in order."
  [records]
  (into [] (mapcat file-rows) records))
