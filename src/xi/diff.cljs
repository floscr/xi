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

(defn parse-diff-text
  "Parse unified diff text into structured data.
   Returns [{:filename string :status keyword :hunks [...]}]"
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
                    (swap! new-ln inc)
                    {:type :add :text (subs line 1) :new-line n})

                  (str/starts-with? line "-")
                  (let [n @old-ln]
                    (swap! old-ln inc)
                    {:type :delete :text (subs line 1) :old-line n})

                  (str/starts-with? line " ")
                  (let [o @old-ln, n @new-ln]
                    (swap! old-ln inc)
                    (swap! new-ln inc)
                    {:type :context :text (subs line 1) :old-line o :new-line n})

                  ;; "\ No newline at end of file"
                  (str/starts-with? line "\\")
                  {:type :meta :text line}

                  :else nil)]
            (when entry
              (swap! hunk update :lines conj entry)))))

      (flush-file!)
      @result)))

