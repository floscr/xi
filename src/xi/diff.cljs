(ns xi.diff
  "Unified diff parsing, shared by the TUI diff viewer and the web client."
  (:require [clojure.string :as str]))

(defn- parse-hunk-header
  "Parse @@ -old,count +new,count @@ context"
  [line]
  (when-let [[_ os oc ns nc ctx]
             (re-matches #"@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@(.*)" line)]
    {:old-start (js/parseInt os 10)
     :old-count (if oc (js/parseInt oc 10) 1)
     :new-start (js/parseInt ns 10)
     :new-count (if nc (js/parseInt nc 10) 1)
     :context (str/trim (or ctx ""))}))

(defn- bump
  "Next line number — nil stays nil, for hunks whose header carries no numbers
   (see tool-diff->unified), so their lines render without a gutter."
  [n]
  (when n (inc n)))

(defn parse-diff-text
  "Parse unified diff text into structured data.
   Returns [{:filename string :status keyword :hunks [...]}]. A bare `@@ @@`
   hunk header (no ranges) yields lines with nil line numbers."
  [text]
  (when (and text (seq (str/trim text)))
    (let [lines (str/split-lines text)
          result (atom [])
          file (atom nil)
          hunk (atom nil)
          old-ln (atom 0)
          new-ln (atom 0)

          flush-hunk!
          (fn []
            (when @hunk
              (swap! file update :hunks conj @hunk)
              (reset! hunk nil)))

          flush-file!
          (fn []
            (flush-hunk!)
            (when @file
              (swap! result conj @file)
              (reset! file nil)))]

      (doseq [line lines]
        (cond
          ;; New file section
          (str/starts-with? line "diff --git")
          (do (flush-file!)
              (let [[_ _a b] (re-find #"diff --git a/(.*) b/(.*)" line)]
                (reset! file {:filename (or b "unknown")
                              :status :modified
                              :hunks []})))

          ;; File metadata (index, ---, +++, new file, deleted, binary)
          (and @file (nil? @hunk) (not (str/starts-with? line "@@")))
          (swap! file
                 (fn [f]
                   (cond-> f
                     (str/starts-with? line "new file") (assoc :status :added)
                     (str/starts-with? line "deleted file") (assoc :status :deleted)
                     (str/starts-with? line "rename ") (assoc :status :renamed)
                     (str/starts-with? line "Binary") (assoc :status :binary))))

          ;; Hunk header
          (str/starts-with? line "@@")
          (do (flush-hunk!)
              (let [hdr (parse-hunk-header line)]
                (reset! old-ln (:old-start hdr))
                (reset! new-ln (:new-start hdr))
                (reset! hunk (assoc hdr :header line :lines []))))

          ;; Diff content lines
          @hunk
          (let [entry
                (cond
                  (str/starts-with? line "+")
                  (let [n @new-ln]
                    (swap! new-ln bump)
                    {:type :add :text (subs line 1) :new-line n})

                  (str/starts-with? line "-")
                  (let [n @old-ln]
                    (swap! old-ln bump)
                    {:type :delete :text (subs line 1) :old-line n})

                  (str/starts-with? line " ")
                  (let [o @old-ln, n @new-ln]
                    (swap! old-ln bump)
                    (swap! new-ln bump)
                    {:type :context :text (subs line 1) :old-line o :new-line n})

                  ;; "\ No newline at end of file"
                  (str/starts-with? line "\\")
                  {:type :meta :text line}

                  :else nil)]
            (when entry
              (swap! hunk update :lines conj entry)))))

      (flush-file!)
      @result)))

(defn diff-rows
  "Flatten parsed diff files into an ordered vector of render rows, assigning
   a stable :sel-idx (selection index) to each code line (add/delete/context).
   File and hunk headers carry :sel-idx nil. Used by both the renderer and the
   selection/prompt logic so indices stay in sync.

   Row shapes:
     {:row :file :filename str :status kw :sel-idx nil}
     {:row :hunk :header str :sel-idx nil}
     {:row :line :filename str :line {..diff line..} :sel-idx int}"
  [parsed]
  (let [rows (atom [])
        n (atom 0)]
    (doseq [{:keys [filename status hunks]} parsed]
      (swap! rows conj {:row :file :filename filename :status status :sel-idx nil})
      (doseq [hunk hunks]
        (swap! rows conj {:row :hunk :header (:header hunk) :sel-idx nil})
        (doseq [l (:lines hunk)]
          (if (= :meta (:type l))
            (swap! rows conj {:row :line :filename filename :line l :sel-idx nil})
            (let [idx @n]
              (swap! n inc)
              (swap! rows conj {:row :line :filename filename :line l :sel-idx idx}))))))
    @rows))

;; ── Layouts ───────────────────────────────────────────────────────────────────
;; A consumer hands the viewer rows in either line shape: unified (:add /
;; :delete / :context / :meta with one :text, from diff-rows) or :split, an old
;; and a new side ({:n :text :changed? :spans}, either nil), from
;; xi.diff.difft. :spans ([[start end] …], string indices) mark the changed
;; ranges of a line. as-layout converts so either view mode shows either source.

(defn- line-row? [r]
  (= :line (:row r)))

(defn split-change?
  "A :split line that is a change (not context): a side changed or missing,
   or the two differ in what difftastic counts as layout only."
  [{:keys [lhs rhs]}]
  (boolean (or (nil? lhs) (nil? rhs) (:changed? lhs) (:changed? rhs)
               (not= (:text lhs) (:text rhs)))))

(defn- side [n text spans]
  (cond-> {:n n :text text :changed? true}
    (seq spans) (assoc :spans spans)))

(defn- pair-run
  "A run of deleted and added unified lines as :split rows, paired in order."
  [filename dels adds]
  (for [i (range (max (count dels) (count adds)))
        :let [d (get dels i) a (get adds i)]]
    {:row :line :filename filename
     :line {:type :split
            :lhs  (when d (side (:old-line d) (:text d) (:spans d)))
            :rhs  (when a (side (:new-line a) (:text a) (:spans a)))}}))

(defn- context->split [{{:keys [text old-line new-line]} :line :as r}]
  (assoc r :line {:type :split
                  :lhs  {:n old-line :text text}
                  :rhs  {:n new-line :text text}}))

(defn- unified->split [rows]
  (loop [rs (seq rows) fname nil dels [] adds [] out []]
    (let [flush #(into % (pair-run fname dels adds))]
      (if-let [r (first rs)]
        (case (when (line-row? r) (get-in r [:line :type]))
          :delete  (if (seq adds)
                     (recur (next rs) (:filename r) [(:line r)] [] (flush out))
                     (recur (next rs) (:filename r) (conj dels (:line r)) adds out))
          :add     (recur (next rs) (:filename r) dels (conj adds (:line r)) out)
          :context (recur (next rs) nil [] [] (conj (flush out) (context->split r)))
          (recur (next rs) nil [] [] (conj (flush out) r)))
        (flush out)))))

(defn- side-row [r type side]
  (when side
    {:row :line :filename (:filename r)
     :line (cond-> {:type type :text (:text side)}
             (= :delete type) (assoc :old-line (:n side))
             (= :add type)    (assoc :new-line (:n side))
             (seq (:spans side)) (assoc :spans (:spans side)))}))

(defn- split->unified
  "Stack :split rows: each run of changed rows as its old lines, then its new
   ones; context rows become one :context line."
  [rows]
  (loop [rs (seq rows) run [] out []]
    (let [flush #(-> %
                     (into (keep (fn [r] (side-row r :delete (get-in r [:line :lhs])))) run)
                     (into (keep (fn [r] (side-row r :add (get-in r [:line :rhs])))) run))]
      (if-let [r (first rs)]
        (let [l (:line r)]
          (cond
            (not (and (line-row? r) (= :split (:type l))))
            (recur (next rs) [] (conj (flush out) r))

            (split-change? l)
            (recur (next rs) (conj run r) out)

            :else
            (recur (next rs) []
                   (conj (flush out)
                         {:row :line :filename (:filename r)
                          :line {:type :context :text (get-in l [:lhs :text])
                                 :old-line (get-in l [:lhs :n])
                                 :new-line (get-in l [:rhs :n])}}))))
        (flush out)))))

(defn- number-rows
  "Assign :sel-idx 0..n to the selectable (non-meta) line rows, like diff-rows."
  [rows]
  (let [n (volatile! -1)]
    (mapv (fn [r]
            (assoc r :sel-idx (when (and (line-row? r) (not= :meta (get-in r [:line :type])))
                                (vswap! n inc))))
          rows)))

(defn as-layout
  "`rows` (either line shape) in `layout`: :split (side by side) or :stacked
   (unified, old above new), with :sel-idx renumbered."
  [rows layout]
  (number-rows (if (= :split layout) (unified->split rows) (split->unified rows))))

(defn default-layout
  "The layout a renderer's diff opens in until the user picks one."
  [engine]
  (if (= :difft engine) :split :stacked))

(defn buffer-rows
  "A :diff buffer's rows in `layout` (nil → default-layout): its :rows when a
   consumer parsed it server-side (xi.diff.difft), else its unified :text."
  [{:keys [rows text engine]} layout]
  (as-layout (or rows (diff-rows (parse-diff-text text)))
             (or layout (default-layout engine))))

(defn rows->text
  "Unified diff text of `rows` (either line shape), for a client that shows a
   rows buffer as text (the TUI)."
  [rows]
  (->> (as-layout rows :stacked)
       (map (fn [{:keys [row filename header line]}]
              (case row
                :file (str "diff --git a/" filename " b/" filename)
                :hunk header
                :line (str (case (:type line) :add "+" :delete "-" :meta "" " ") (:text line)))))
       (str/join "\n")))

(defn mark-spans
  "Split `tokens` ({:value str …}, a line's text in order) at the edges of
   `spans` ([[start end] …], sorted, disjoint) and flag the pieces inside
   one :changed? true."
  [tokens spans]
  (loop [ts (seq tokens) pos 0 sp (seq spans) out []]
    (if-let [{:keys [value] :as t} (first ts)]
      (let [end   (+ pos (count value))
            [s e] (first sp)]
        (cond
          (or (nil? s) (<= end s)) (recur (next ts) end sp (conj out t))
          (<= e pos)               (recur ts pos (next sp) out)
          :else
          (let [a      (max 0 (- s pos))
                b      (min (count value) (- e pos))
                out    (cond-> out
                         (pos? a) (conj (assoc t :value (subs value 0 a)))
                         :always  (conj (assoc t :value (subs value a b) :changed? true)))
                after  (subs value b)]
            (if (seq after)
              (recur (cons (assoc t :value after) (next ts)) (+ pos b) (next sp) out)
              (recur (next ts) end sp out)))))
      out)))

(defn line-keys
  "Every line-key a row answers to: a :split row to both of its sides, so
   something anchored in one layout finds its line in the other."
  [{:keys [filename line]}]
  (let [{:keys [type old-line new-line lhs rhs]} line]
    (case type
      :split  (cond-> []
                rhs (conj [filename :new (:n rhs)])
                lhs (conj [filename :old (:n lhs)]))
      :delete [[filename :old old-line]]
      [[filename :new new-line]])))

(defn line-key
  "Identity of a diff line row that survives a reload of the same diff (its
   :sel-idx shifts when lines above it change) and a layout switch: the file
   and the new line it shows, else the old one."
  [row]
  (first (line-keys row)))

(defn selection-range
  "Normalize a {:anchor :head} selection into [lo hi] inclusive, or nil."
  [{:keys [anchor head]}]
  (when (and anchor head)
    [(min anchor head) (max anchor head)]))

(defn selected-snippet
  "Build a readable diff snippet from the rows whose :sel-idx falls within the
   inclusive [lo hi] selection range. Groups consecutive lines by file and
   prefixes +/-/space signs so an LLM can read it as a diff fragment."
  [rows [lo hi]]
  (let [sel (split->unified (filter (fn [{:keys [row sel-idx]}]
                                      (and (= :line row) sel-idx (<= lo sel-idx hi)))
                                    rows))
        by-file (partition-by :filename sel)]
    (->> by-file
         (map (fn [group]
                (let [fname (:filename (first group))
                      body (->> group
                                (map (fn [{:keys [line]}]
                                       (let [sign (case (:type line)
                                                    :add "+" :delete "-" " ")]
                                         (str sign (:text line)))))
                                (str/join "\n"))]
                  (str fname "\n" body))))
         (str/join "\n\n"))))

(defn- tool-diff-line?
  "A line of an edit tool's diff text proper: a signed line, the `...` gap
   or the `(created new file)` note."
  [l]
  (or (str/starts-with? l "+ ") (str/starts-with? l "- ") (str/starts-with? l "  ")
      (= "..." l) (= "(created new file)" l)))

(defn tool-diff-body
  "An edit tool's diff text as a tool block shows it: without the line that
   heads it (the edit tool's path, clj_replace's message) when diff lines
   follow, since the block's header already names the file. The
   `[file-hash: …]` trailer is dropped by xi.util/strip-file-hash."
  [text]
  (let [[l & more] (str/split-lines (or text ""))]
    (if (and l (not (tool-diff-line? l)) (some tool-diff-line? more))
      (str/join "\n" more)
      (or text ""))))

(defn tool-diff->unified
  "Convert an edit/write tool's own diff text (xi.tools.util/unified-diff: `- `,
   `+ ` and `  ` prefixed lines, `...` between hunks, plus a path line, an
   optional `(created new file)` note and a `[file-hash: …]` trailer) into git
   unified-diff text for `path`, so the exact change a tool block shows opens in
   the diff viewer. The tool diff carries no line numbers, so hunks get a bare
   `@@ @@` header and render without a gutter. nil when there are no diff lines."
  [path text]
  (let [lines  (str/split-lines (or text ""))
        sign   (fn [l]
                 (cond (str/starts-with? l "+ ") (str "+" (subs l 2))
                       (str/starts-with? l "- ") (str "-" (subs l 2))
                       (str/starts-with? l "  ") (str " " (subs l 2))))
        body   (mapcat (fn [l]
                         (if (= "..." l)
                           ["@@ @@"]
                           (some-> (sign l) vector)))
                       lines)
        body   (if (= "@@ @@" (first body)) body (cons "@@ @@" body))]
    (when (some #(not= "@@ @@" %) body)
      (str/join "\n"
                (concat [(str "diff --git a/" path " b/" path)]
                        (when (some #{"(created new file)"} lines)
                          ["new file mode 100644"])
                        [(str "--- a/" path) (str "+++ b/" path)]
                        body)))))

