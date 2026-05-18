(ns xi.tui.diff-buffer
  "Interactive diff viewer buffer with syntax highlighting and navigation.
   Parses unified diff output and renders it as a scrollable view with
   line numbers, change highlighting, and vim-like navigation.

   Keybindings:
     j/k, ↑/↓         Scroll up/down
     Ctrl-d/Ctrl-u     Half-page scroll
     Page Up/Down      Full-page scroll
     gg / G            Top / bottom
     ]c / [c           Next / previous change
     ]f / [f           Next / previous file
     :                 Enter command mode (focus editor)
     q / Escape        Close diff view"
  (:require [clojure.string :as str]
            [xi.highlight.core :as hl]
            [xi.highlight.grammars :as hl-grammars]
            [xi.highlight.theme :as hl-theme]
            [xi.tui.ansi :as ansi]
            [xi.tui.core :as tui]
            [xi.tui.terminal :as term]))

;; ── Diff Parsing ──────────────────────────────────────────────────────────────

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

;; ── Rendering ─────────────────────────────────────────────────────────────────

(def ^:private file-header-bg "\033[48;2;50;55;70m")
(def ^:private hunk-header-fg "\033[38;2;129;161;193m")
(def ^:private add-bg "\033[48;2;35;60;45m")
(def ^:private del-bg "\033[48;2;65;40;42m")
(def ^:private line-nr-fg "\033[38;2;90;100;120m")
(def ^:private separator-fg "\033[38;2;70;80;100m")

(defn- file-ext
  "Extract file extension from a path."
  [filename]
  (when filename
    (when-let [dot (str/last-index-of filename ".")]
      (str/lower-case (subs filename (inc dot))))))

(defn- highlight-code
  "Syntax-highlight a line of code. Returns plain text if no grammar."
  [grammar text]
  (if grammar
    (hl-theme/colorize (hl/merge-adjacent (hl/tokenize grammar text)))
    text))

(defn- right-align
  "Right-align a string to width, padding with spaces."
  [s width]
  (let [pad (- width (count s))]
    (if (pos? pad)
      (str (apply str (repeat pad " ")) s)
      s)))

(defn- render-diff-lines
  "Pre-render parsed diff files into a flat vector of ANSI-formatted lines.
   Returns {:lines [string] :change-starts [int] :file-starts [int]}"
  [parsed-files width]
  (let [lines (atom [])
        change-starts (atom [])
        file-starts (atom [])
        prev-was-change (atom false)

        emit!
        (fn [line & [opts]]
          (let [idx (count @lines)]
            (when (:file opts)
              (swap! file-starts conj idx))
            (when (and (:change opts) (not @prev-was-change))
              (swap! change-starts conj idx))
            (reset! prev-was-change (boolean (:change opts)))
            (swap! lines conj line)))]

    (doseq [{:keys [filename status hunks]} parsed-files]
      (let [grammar (when-let [ext (file-ext filename)]
                      (hl-grammars/get-grammar ext))
            status-str (case status
                         :added "added"
                         :deleted "deleted"
                         :binary "binary"
                         :renamed "renamed"
                         "modified")]

        ;; Blank line + file header
        (emit! "")
        (emit! (ansi/apply-bg-to-line
                (str " " (ansi/fg :bold filename)
                     (ansi/fg :dim (str " ── " status-str " ")))
                width file-header-bg)
               {:file true})

        (if (= :binary status)
          (emit! (ansi/fg :dim "  Binary file"))

          ;; Render hunks
          (doseq [hunk hunks]
            ;; Hunk header
            (emit! (str " " (ansi/fg hunk-header-fg (:header hunk))))

            ;; Line number column widths for this hunk
            (let [all-old (keep :old-line (:lines hunk))
                  all-new (keep :new-line (:lines hunk))
                  max-old (if (seq all-old) (apply max all-old) 0)
                  max-new (if (seq all-new) (apply max all-new) 0)
                  old-w (max 3 (count (str max-old)))
                  new-w (max 3 (count (str max-new)))]

              (doseq [dl (:lines hunk)]
                (when (not= :meta (:type dl))
                  (let [;; Line numbers
                        old-str (if-let [n (:old-line dl)]
                                  (right-align (str n) old-w)
                                  (apply str (repeat old-w " ")))
                        new-str (if-let [n (:new-line dl)]
                                  (right-align (str n) new-w)
                                  (apply str (repeat new-w " ")))
                        ;; Gutter
                        gutter (str (ansi/fg line-nr-fg (str old-str " " new-str))
                                    (ansi/fg separator-fg " │"))
                        ;; Prefix
                        prefix (case (:type dl) :add "+" :delete "-" " ")
                        ;; Highlighted code
                        code (highlight-code grammar (:text dl))
                        ;; Build full line
                        raw-line (str gutter prefix code)
                        ;; Apply diff background for add/delete
                        bg (case (:type dl) :add add-bg :delete del-bg nil)
                        final-line (if bg
                                     (ansi/apply-bg-to-line raw-line width bg)
                                     raw-line)]
                    (emit! final-line
                           (when (#{:add :delete} (:type dl))
                             {:change true}))))))))))

    ;; Trailing blank line
    (emit! "")

    {:lines (vec @lines)
     :change-starts (vec @change-starts)
     :file-starts (vec @file-starts)}))

;; ── Key Detection ─────────────────────────────────────────────────────────────

(def ^:private ESC (str (char 27)))

(defn- is-escape? [data]
  (or (= data ESC) (= data (str ESC "[27u"))))

(defn- is-arrow-up? [data] (= data (str ESC "[A")))
(defn- is-arrow-down? [data] (= data (str ESC "[B")))
(defn- is-ctrl-d? [data] (= data (str (char 4))))
(defn- is-ctrl-u? [data] (= data (str (char 21))))
(defn- is-page-up? [data] (= data (str ESC "[5~")))
(defn- is-page-down? [data] (= data (str ESC "[6~")))

;; ── Component ─────────────────────────────────────────────────────────────────

(defn make-diff-buffer
  "Create a diff viewer buffer component.

   opts:
     :diff-text       — raw unified diff text
     :title           — display title (e.g. \"Session Changes\")
     :on-close        — (fn []) called when q/Escape is pressed
     :on-command-mode — (fn []) called when : is pressed"
  [opts]
  (let [{:keys [diff-text title on-close on-command-mode]} opts
        parsed (parse-diff-text diff-text)
        file-count (count parsed)
        add-count (reduce + (for [f parsed, h (:hunks f), l (:lines h)
                                  :when (= :add (:type l))] 1))
        del-count (reduce + (for [f parsed, h (:hunks f), l (:lines h)
                                  :when (= :delete (:type l))] 1))

        state (atom {:cached-lines nil
                     :cached-width nil
                     :change-starts []
                     :file-starts []
                     :pending-key nil})

        ;; Viewport height estimate (terminal height minus bottom panel and status)
        viewport-height (fn [] (max 1 (- (term/rows) 3)))

        ;; Navigation: find current top visible line
        current-top-line
        (fn []
          (let [total (count (:cached-lines @state))
                vh (viewport-height)
                offset (tui/get-scroll-offset)
                end (- total (min offset (max 0 (- total vh))))
                start (max 0 (- end vh))]
            start))

        ;; Jump to a specific line (places it ~1/3 from top)
        jump-to-line!
        (fn [n]
          (let [total (count (:cached-lines @state))
                vh (viewport-height)
                offset (max 0 (- total n (quot vh 3)))]
            (tui/scroll-to-offset! offset)))

        jump-next!
        (fn [positions]
          (let [top (current-top-line)
                target (first (filter #(> % (+ top 2)) positions))]
            (when target (jump-to-line! target))))

        jump-prev!
        (fn [positions]
          (let [top (current-top-line)
                target (last (filter #(< % top) positions))]
            (when target (jump-to-line! target))))]

    {:type :diff-buffer
     :capture-all-input true

     :handle-scroll
     (fn [delta]
       (if (neg? delta)
         (tui/scroll-up! (- delta))
         (tui/scroll-down! delta)))

     :handle-input
     (fn [data]
       (let [pending (:pending-key @state)]
         (swap! state assoc :pending-key nil)
         (cond
           ;; gg: go to top
           (and (= pending "g") (= data "g"))
           (tui/scroll-to-offset! 999999)

           ;; ]c / [c: next/prev change
           (and (= pending "]") (= data "c"))
           (jump-next! (:change-starts @state))
           (and (= pending "[") (= data "c"))
           (jump-prev! (:change-starts @state))

           ;; ]f / [f: next/prev file
           (and (= pending "]") (= data "f"))
           (jump-next! (:file-starts @state))
           (and (= pending "[") (= data "f"))
           (jump-prev! (:file-starts @state))

           ;; Pending prefix keys
           (#{"g" "]" "["} data)
           (swap! state assoc :pending-key data)

           ;; j / ↓: scroll down
           (or (= data "j") (is-arrow-down? data))
           (tui/scroll-down! 1)

           ;; k / ↑: scroll up
           (or (= data "k") (is-arrow-up? data))
           (tui/scroll-up! 1)

           ;; G: go to bottom
           (= data "G")
           (tui/scroll-to-offset! 0)

           ;; Ctrl-d / Ctrl-u: half-page scroll
           (is-ctrl-d? data) (tui/scroll-down! (quot (viewport-height) 2))
           (is-ctrl-u? data) (tui/scroll-up! (quot (viewport-height) 2))

           ;; Page Up / Page Down
           (is-page-up? data) (tui/scroll-up! (max 1 (- (viewport-height) 2)))
           (is-page-down? data) (tui/scroll-down! (max 1 (- (viewport-height) 2)))

           ;; q / Escape: close
           (or (= data "q") (is-escape? data))
           (when on-close (on-close))

           ;; :: command mode
           (= data ":")
           (when on-command-mode (on-command-mode))

           :else nil)))

     :invalidate
     (fn [] (swap! state assoc :cached-width nil :cached-lines nil))

     :render
     (fn [width]
       ;; Re-render on width change
       (when (or (nil? (:cached-lines @state))
                 (not= width (:cached-width @state)))
         (let [{:keys [lines change-starts file-starts]}
               (render-diff-lines parsed width)
               ;; Prepend a title/summary header
               header [(ansi/fg :bold (str " " (or title "Diff")))
                       (ansi/fg :dim
                                (str "  " file-count " file"
                                     (when (not= 1 file-count) "s")
                                     "  " (ansi/fg :green (str "+" add-count))
                                     "  " (ansi/fg :red (str "-" del-count))
                                     "  │  "
                                     "j/k:scroll  ]c/[c:changes  ]f/[f:files  gg/G:top/bottom  q:close  ::command"))
                       ""]
               header-len (count header)
               ;; Adjust positions to account for header
               all-lines (into header lines)]
           (swap! state assoc
                  :cached-lines all-lines
                  :cached-width width
                  :change-starts (mapv #(+ % header-len) change-starts)
                  :file-starts (mapv #(+ % header-len) file-starts))))
       (:cached-lines @state))}))
