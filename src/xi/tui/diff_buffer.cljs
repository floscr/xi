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
            [xi.tui.pager :as pager]
            [xi.diff :as diff]))

;; ── Diff Parsing ──────────────────────────────────────────────────────────────

;; ── Rendering ─────────────────────────────────────────────────────────────────

(def ^:private hunk-header-fg "\033[38;2;129;161;193m")
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
   Returns {:lines [string] :change-starts [int] :file-starts [int]
            :line-locs [{:file str :line int}|nil]} — line-locs is parallel to
   :lines and maps each code line to its file + line number (new side when
   present, old side for deletions); header/blank lines carry nil (file
   headers carry {:file f} so an edit jump still lands in the right file)."
  [parsed-files width]
  (let [file-header-bg (hl-theme/diff-header-bg)
        add-bg (hl-theme/diff-add-bg)
        del-bg (hl-theme/diff-del-bg)
        lines (atom [])
        change-starts (atom [])
        file-starts (atom [])
        line-locs (atom [])
        prev-was-change (atom false)

        emit!
        (fn [line & [opts]]
          (let [idx (count @lines)]
            (when (:file opts)
              (swap! file-starts conj idx))
            (when (and (:change opts) (not @prev-was-change))
              (swap! change-starts conj idx))
            (reset! prev-was-change (boolean (:change opts)))
            (swap! line-locs conj (:loc opts))
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
               {:file true :loc {:file filename}})

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
                           (cond-> {:loc {:file filename
                                          :line (or (:new-line dl) (:old-line dl))}}
                             (#{:add :delete} (:type dl)) (assoc :change true)))))))))))

    ;; Trailing blank line
    (emit! "")

    {:lines (vec @lines)
     :change-starts (vec @change-starts)
     :file-starts (vec @file-starts)
     :line-locs (vec @line-locs)}))

(defn- loc-at
  "File/line location for the body line at idx: the line's own entry, else the
   nearest following one (file/hunk headers, blanks), else the nearest
   preceding one (trailing blanks)."
  [locs idx]
  (when (seq locs)
    (let [idx (-> idx (max 0) (min (dec (count locs))))]
      (or (nth locs idx)
          (some identity (subvec locs idx))
          (some identity (rseq (subvec locs 0 idx)))))))

;; ── Component ─────────────────────────────────────────────────────────────────

(defn make-diff-buffer
  "Create a diff viewer buffer component.

   opts:
     :diff-text       — raw unified diff text
     :title           — display title (e.g. \"Session Changes\")
     :on-close        — (fn []) called when q/Escape is pressed
     :on-command-mode — (fn []) called when : is pressed
     :on-explain      — (fn [text]) called with the selected region on e
     :on-prompt       — (fn [text]) called with the selected region on Enter
     :on-edit         — (fn [{:keys [file line]}]) called on v with the
                        file + line number under the cursor; optional (key
                        is inert when absent)"
  [{:keys [diff-text title on-close on-command-mode on-explain on-prompt
           on-edit]}]
  (let [parsed (diff/parse-diff-text diff-text)
        line-locs (atom [])
        file-count (count parsed)
        add-count (reduce + (for [f parsed, h (:hunks f), l (:lines h)
                                  :when (= :add (:type l))] 1))
        del-count (reduce + (for [f parsed, h (:hunks f), l (:lines h)
                                  :when (= :delete (:type l))] 1))]
    (pager/make-pager
     {:title (or title "Diff")
      :header-fn
      (fn []
        [(ansi/fg :dim
                  (str "  " file-count " file"
                       (when (not= 1 file-count) "s")
                       "  " (ansi/fg :green (str "+" add-count))
                       "  " (ansi/fg :red (str "-" del-count))))])
      :lines-fn (fn [width]
                  (let [rendered (render-diff-lines parsed width)]
                    (reset! line-locs (:line-locs rendered))
                    rendered))
      ;; v: open the file under the cursor in $EDITOR (host-provided).
      :extra-keys (when on-edit
                    (fn [data {:keys [body-cursor]}]
                      (when (and (= data "v") body-cursor)
                        (when-let [loc (loc-at @line-locs body-cursor)]
                          (on-edit loc)
                          true))))
      :help (pager/help-bar [["j/k" "move"] ["v" "edit"] ["V/y" "select/yank"]
                             ["e" "explain"] ["\u23ce" "prompt"]
                             ["]c/[c" "changes"] ["]f/[f" "files"]
                             ["gg/G" "top/bottom"] ["q" "close"] [":" "command"]])
      :on-close on-close
      :on-command-mode on-command-mode
      :on-explain on-explain
      :on-prompt on-prompt})))
