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
  (let [sel (filter (fn [{:keys [row sel-idx]}]
                      (and (= :line row) sel-idx (<= lo sel-idx hi)))
                    rows)
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

