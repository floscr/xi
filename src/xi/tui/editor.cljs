(ns xi.tui.editor
  "Raw-mode multi-line editor component.
   Renders a bordered input area at the bottom of the TUI.
   Handles character input, cursor movement, history, and paste."
  (:require [clojure.string :as str]
            [xi.tui.ansi :as ansi]
            [xi.tui.core :as tui]))

;; ── Key Detection ─────────────────────────────────────────────────────────────

(def ^:private ESC (str (char 27)))
(def ^:private DEL (str (char 127)))
(def ^:private BS (str (char 8)))

(defn- ctrl? [data ch]
  (let [legacy-code (- (.charCodeAt ch 0) 64)
        codepoint (.charCodeAt (.toLowerCase ch) 0)]
    (or
     ;; Legacy: Ctrl+B → \x02, etc.
     (= data (str (char legacy-code)))
     ;; Kitty CSI u: Ctrl+B → \x1b[98;5u (modifier 5 = Ctrl)
     (= data (str (char 27) "[" codepoint ";5u")))))

(defn- is-enter? [data]
  (or (= data "\r") (= data "\n")))

(defn- is-backspace? [data]
  (or (= data DEL) (= data BS)))

(defn- is-escape? [data]
  (or (= data ESC)
      (= data (str ESC "[27u"))))

(defn- is-arrow-up? [data]
  (= data (str ESC "[A")))

(defn- is-arrow-down? [data]
  (= data (str ESC "[B")))

(defn- is-arrow-left? [data]
  (= data (str ESC "[D")))

(defn- is-arrow-right? [data]
  (= data (str ESC "[C")))

(defn- is-home? [data]
  (or (= data (str ESC "[H")) (ctrl? data "A")))

(defn- is-end? [data]
  (or (= data (str ESC "[F")) (ctrl? data "E")))

(defn- is-delete? [data]
  (= data (str ESC "[3~")))

(defn- is-paste-start? [data]
  (str/starts-with? data (str ESC "[200~")))

(defn- is-paste-end? [data]
  (str/ends-with? data (str ESC "[201~")))

(defn- is-printable? [data]
  (let [code (.charCodeAt data 0)]
    (and (= (count data) 1)
         (>= code 32)
         (not= code 127))))

(defn- is-shift-enter? [data]
  (or (= data (str ESC "[13;2u"))      ;; kitty keyboard protocol
      (= data (str ESC "OM"))))

(defn- is-alt-enter? [data]
  (or (= data (str ESC "\r"))
      (= data (str ESC "\n"))
      (= data (str ESC "[13;3u"))))

(defn- is-alt-b? [data]
  (or (= data (str ESC "b"))
      (= data (str ESC "[98;3u"))))

(defn- is-alt-f? [data]
  (or (= data (str ESC "f"))
      (= data (str ESC "[102;3u"))))

(defn- is-alt-d? [data]
  (or (= data (str ESC "d"))
      (= data (str ESC "[100;3u"))))

(defn- is-alt-backspace? [data]
  (or (= data (str ESC DEL))
      (= data (str ESC "[127;3u"))))

;; ── Word Boundary Helpers ─────────────────────────────────────────────────────

(defn- word-char? [ch]
  (boolean (re-matches #"[a-zA-Z0-9_]" (str ch))))

(defn- find-word-start-backward
  "From position pos in line, find the start of the previous word (emacs M-b)."
  [line pos]
  (if (<= pos 0)
    0
    (let [;; Phase 1: skip non-word chars backwards
          i (loop [i (dec pos)]
              (cond
                (neg? i) -1
                (word-char? (.charAt line i)) i
                :else (recur (dec i))))]
      (if (neg? i)
        0
        ;; Phase 2: skip word chars backwards
        (loop [j i]
          (if (and (pos? j) (word-char? (.charAt line (dec j))))
            (recur (dec j))
            j))))))

(defn- find-word-end-forward
  "From position pos in line, find the end of the next word (emacs M-f)."
  [line pos]
  (let [len (count line)]
    (if (>= pos len)
      len
      (let [;; Phase 1: skip non-word chars forward
            i (loop [i pos]
                (cond
                  (>= i len) len
                  (word-char? (.charAt line i)) i
                  :else (recur (inc i))))]
        (if (>= i len)
          len
          ;; Phase 2: skip word chars forward
          (loop [j i]
            (if (and (< j len) (word-char? (.charAt line j)))
              (recur (inc j))
              j)))))))

;; ── Editor Component ──────────────────────────────────────────────────────────

(defn make-editor
  "Create a multi-line editor component.
   opts:
     :on-submit (fn [text]) — called when user presses Enter with content
     :on-interrupt (fn []) — called on Ctrl+C
     :prompt \"xi> \" — prompt text"
  [opts]
  (let [state (atom {:lines [""]
                      :cursor-line 0
                      :cursor-col 0
                      :history []
                      :history-index -1
                      :saved-text nil
                      :paste-buffer nil
                      :cached-width nil
                      :cached-lines nil})
        prompt (or (:prompt opts) "xi> ")
        on-submit (:on-submit opts)
        on-interrupt (:on-interrupt opts)
        on-escape (:on-escape opts)
        on-palette (:on-palette opts)

        get-text (fn []
                   (str/join "\n" (:lines @state)))

        set-text (fn [text]
                   (let [lines (if (empty? text) [""] (str/split-lines text))]
                     (swap! state assoc
                            :lines (vec lines)
                            :cursor-line (dec (count lines))
                            :cursor-col (count (last lines))
                            :cached-width nil :cached-lines nil))
                   (tui/request-render!))

        add-history (fn [text]
                      (when (seq (str/trim text))
                        (swap! state update :history
                               (fn [h] (vec (take 100 (cons text h)))))
                        (swap! state assoc :history-index -1)))

        insert-char (fn [ch]
                      (swap! state (fn [{:keys [lines cursor-line cursor-col] :as s}]
                                     (let [line (nth lines cursor-line)
                                           new-line (str (subs line 0 cursor-col) ch (subs line cursor-col))]
                                       (-> s
                                           (assoc-in [:lines cursor-line] new-line)
                                           (update :cursor-col + (count ch))
                                           (assoc :cached-width nil :cached-lines nil
                                                  :history-index -1)))))
                      (tui/request-render!))

        insert-newline (fn []
                         (swap! state (fn [{:keys [lines cursor-line cursor-col] :as s}]
                                        (let [line (nth lines cursor-line)
                                              before (subs line 0 cursor-col)
                                              after (subs line cursor-col)
                                              new-lines (vec (concat
                                                              (subvec lines 0 cursor-line)
                                                              [before after]
                                                              (subvec lines (inc cursor-line))))]
                                          (-> s
                                              (assoc :lines new-lines)
                                              (assoc :cursor-line (inc cursor-line))
                                              (assoc :cursor-col 0)
                                              (assoc :cached-width nil :cached-lines nil)))))
                         (tui/request-render!))

        delete-back (fn []
                      (swap! state (fn [{:keys [lines cursor-line cursor-col] :as s}]
                                     (cond
                                       ;; Middle of line — delete char before cursor
                                       (pos? cursor-col)
                                       (let [line (nth lines cursor-line)
                                             new-line (str (subs line 0 (dec cursor-col)) (subs line cursor-col))]
                                         (-> s
                                             (assoc-in [:lines cursor-line] new-line)
                                             (update :cursor-col dec)
                                             (assoc :cached-width nil :cached-lines nil)))

                                       ;; Start of line — merge with previous line
                                       (pos? cursor-line)
                                       (let [prev-line (nth lines (dec cursor-line))
                                             curr-line (nth lines cursor-line)
                                             merged (str prev-line curr-line)
                                             new-lines (vec (concat
                                                             (subvec lines 0 (dec cursor-line))
                                                             [merged]
                                                             (subvec lines (inc cursor-line))))]
                                         (-> s
                                             (assoc :lines new-lines)
                                             (assoc :cursor-line (dec cursor-line))
                                             (assoc :cursor-col (count prev-line))
                                             (assoc :cached-width nil :cached-lines nil)))

                                       :else s)))
                      (tui/request-render!))

        delete-forward (fn []
                         (swap! state (fn [{:keys [lines cursor-line cursor-col] :as s}]
                                        (let [line (nth lines cursor-line)]
                                          (cond
                                            ;; Not at end of line
                                            (< cursor-col (count line))
                                            (let [new-line (str (subs line 0 cursor-col) (subs line (inc cursor-col)))]
                                              (-> s
                                                  (assoc-in [:lines cursor-line] new-line)
                                                  (assoc :cached-width nil :cached-lines nil)))

                                            ;; At end of line, merge with next
                                            (< cursor-line (dec (count lines)))
                                            (let [next-line (nth lines (inc cursor-line))
                                                  merged (str line next-line)
                                                  new-lines (vec (concat
                                                                  (subvec lines 0 cursor-line)
                                                                  [merged]
                                                                  (subvec lines (+ cursor-line 2))))]
                                              (-> s
                                                  (assoc :lines new-lines)
                                                  (assoc :cached-width nil :cached-lines nil)))

                                            :else s))))
                         (tui/request-render!))

        move-cursor (fn [dline dcol]
                      (swap! state (fn [{:keys [lines cursor-line cursor-col] :as s}]
                                     (let [new-line (max 0 (min (dec (count lines)) (+ cursor-line dline)))
                                           line-len (count (nth lines new-line))
                                           new-col (if (not= 0 dline)
                                                     ;; Vertical movement — clamp to line length
                                                     (min cursor-col line-len)
                                                     ;; Horizontal movement
                                                     (max 0 (min line-len (+ cursor-col dcol))))]
                                       (-> s
                                           (assoc :cursor-line new-line :cursor-col new-col)
                                           (assoc :cached-width nil :cached-lines nil)))))
                      (tui/request-render!))

        browse-history (fn [direction]
                         (let [{:keys [history history-index]} @state
                               max-idx (dec (count history))]
                           (when (seq history)
                             (let [new-idx (case direction
                                            :up (min max-idx (inc history-index))
                                            :down (max -1 (dec history-index)))]
                               (when (not= new-idx history-index)
                                 ;; Save current text before first browse
                                 (when (= -1 history-index)
                                   (swap! state assoc :saved-text (get-text)))
                                 (if (= -1 new-idx)
                                   ;; Back to current input
                                   (do (set-text (or (:saved-text @state) ""))
                                       (swap! state assoc :history-index -1))
                                   (do (set-text (nth history new-idx))
                                       (swap! state assoc :history-index new-idx))))))))

        delete-word-back (fn []
                           (swap! state (fn [{:keys [lines cursor-line cursor-col] :as s}]
                                          (let [line (nth lines cursor-line)]
                                            (if (pos? cursor-col)
                                              (let [target (find-word-start-backward line cursor-col)
                                                    new-line (str (subs line 0 target) (subs line cursor-col))]
                                                (-> s
                                                    (assoc-in [:lines cursor-line] new-line)
                                                    (assoc :cursor-col target)
                                                    (assoc :cached-width nil :cached-lines nil)))
                                              ;; At start of line — merge with previous (like backspace)
                                              (if (pos? cursor-line)
                                                (let [prev-line (nth lines (dec cursor-line))
                                                      curr-line (nth lines cursor-line)
                                                      merged (str prev-line curr-line)
                                                      new-lines (vec (concat
                                                                      (subvec lines 0 (dec cursor-line))
                                                                      [merged]
                                                                      (subvec lines (inc cursor-line))))]
                                                  (-> s
                                                      (assoc :lines new-lines)
                                                      (assoc :cursor-line (dec cursor-line))
                                                      (assoc :cursor-col (count prev-line))
                                                      (assoc :cached-width nil :cached-lines nil)))
                                                s)))))
                           (tui/request-render!))

        delete-word-forward (fn []
                              (swap! state (fn [{:keys [lines cursor-line cursor-col] :as s}]
                                             (let [line (nth lines cursor-line)
                                                   len (count line)]
                                               (if (< cursor-col len)
                                                 (let [target (find-word-end-forward line cursor-col)
                                                       new-line (str (subs line 0 cursor-col) (subs line target))]
                                                   (-> s
                                                       (assoc-in [:lines cursor-line] new-line)
                                                       (assoc :cached-width nil :cached-lines nil)))
                                                 ;; At end of line — merge with next
                                                 (if (< cursor-line (dec (count lines)))
                                                   (let [next-line (nth lines (inc cursor-line))
                                                         merged (str line next-line)
                                                         new-lines (vec (concat
                                                                         (subvec lines 0 cursor-line)
                                                                         [merged]
                                                                         (subvec lines (+ cursor-line 2))))]
                                                     (-> s
                                                         (assoc :lines new-lines)
                                                         (assoc :cached-width nil :cached-lines nil)))
                                                   s)))))
                              (tui/request-render!))

        move-word-back (fn []
                         (swap! state (fn [{:keys [lines cursor-line cursor-col] :as s}]
                                        (if (pos? cursor-col)
                                          (let [line (nth lines cursor-line)
                                                target (find-word-start-backward line cursor-col)]
                                            (-> s
                                                (assoc :cursor-col target)
                                                (assoc :cached-width nil :cached-lines nil)))
                                          ;; At start of line — jump to end of previous line
                                          (if (pos? cursor-line)
                                            (let [prev-line (nth lines (dec cursor-line))]
                                              (-> s
                                                  (assoc :cursor-line (dec cursor-line))
                                                  (assoc :cursor-col (count prev-line))
                                                  (assoc :cached-width nil :cached-lines nil)))
                                            s))))
                         (tui/request-render!))

        move-word-forward (fn []
                            (swap! state (fn [{:keys [lines cursor-line cursor-col] :as s}]
                                           (let [line (nth lines cursor-line)
                                                 len (count line)]
                                             (if (< cursor-col len)
                                               (let [target (find-word-end-forward line cursor-col)]
                                                 (-> s
                                                     (assoc :cursor-col target)
                                                     (assoc :cached-width nil :cached-lines nil)))
                                               ;; At end of line — jump to start of next line
                                               (if (< cursor-line (dec (count lines)))
                                                 (-> s
                                                     (assoc :cursor-line (inc cursor-line))
                                                     (assoc :cursor-col 0)
                                                     (assoc :cached-width nil :cached-lines nil))
                                                 s)))))
                            (tui/request-render!))

        transpose-chars (fn []
                          (swap! state (fn [{:keys [lines cursor-line cursor-col] :as s}]
                                         (let [line (nth lines cursor-line)
                                               len (count line)]
                                           (cond
                                             ;; At end of line with 2+ chars — swap last two
                                             (and (= cursor-col len) (>= len 2))
                                             (let [new-line (str (subs line 0 (- len 2))
                                                                (str (.charAt line (dec len))
                                                                     (.charAt line (- len 2))))]
                                               (-> s
                                                   (assoc-in [:lines cursor-line] new-line)
                                                   (assoc :cached-width nil :cached-lines nil)))

                                             ;; In middle with char before — swap with next, advance
                                             (and (pos? cursor-col) (< cursor-col len))
                                             (let [new-line (str (subs line 0 (dec cursor-col))
                                                                (str (.charAt line cursor-col)
                                                                     (.charAt line (dec cursor-col)))
                                                                (subs line (inc cursor-col)))]
                                               (-> s
                                                   (assoc-in [:lines cursor-line] new-line)
                                                   (update :cursor-col inc)
                                                   (assoc :cached-width nil :cached-lines nil)))

                                             :else s))))
                          (tui/request-render!))

        handle-submit (fn []
                        (let [text (get-text)]
                          (when (seq (str/trim text))
                            (add-history text)
                            (set-text "")
                            (when on-submit
                              (on-submit text)))))

        handle-input (fn [data]
                       (cond
                         ;; Ctrl+C
                         (ctrl? data "C")
                         (if (seq (str/trim (get-text)))
                           (set-text "")
                           (when on-interrupt (on-interrupt)))

                         ;; Ctrl+D on empty — exit
                         (and (ctrl? data "D") (empty? (str/trim (get-text))))
                         (when on-interrupt (on-interrupt))

                         ;; Ctrl+D with content — delete char forward
                         (ctrl? data "D")
                         (delete-forward)

                         ;; Escape — notify parent
                         (is-escape? data)
                         (when on-escape (on-escape))

                         ;; Shift+Enter / Alt+Enter — insert newline
                         (or (is-shift-enter? data) (is-alt-enter? data))
                         (insert-newline)

                         ;; Enter — submit
                         (is-enter? data)
                         (handle-submit)

                         ;; Backspace
                         (is-backspace? data)
                         (delete-back)

                         ;; Delete
                         (is-delete? data)
                         (delete-forward)

                         ;; Arrow keys
                         (is-arrow-up? data)
                         (if (zero? (:cursor-line @state))
                           (browse-history :up)
                           (move-cursor -1 0))

                         (is-arrow-down? data)
                         (if (= (:cursor-line @state) (dec (count (:lines @state))))
                           (browse-history :down)
                           (move-cursor 1 0))

                         (is-arrow-left? data)
                         (move-cursor 0 -1)

                         (is-arrow-right? data)
                         (move-cursor 0 1)

                         ;; Home / Ctrl+A
                         (is-home? data)
                         (swap! state (fn [s]
                                        (-> s
                                            (assoc :cursor-col 0)
                                            (assoc :cached-width nil :cached-lines nil))))

                         ;; End / Ctrl+E
                         (is-end? data)
                         (swap! state (fn [{:keys [lines cursor-line] :as s}]
                                        (-> s
                                            (assoc :cursor-col (count (nth lines cursor-line)))
                                            (assoc :cached-width nil :cached-lines nil))))

                         ;; Ctrl+U — kill line
                         (ctrl? data "U")
                         (swap! state (fn [{:keys [lines cursor-line] :as s}]
                                        (let [line (nth lines cursor-line)
                                              after (subs line (:cursor-col s))]
                                          (-> s
                                              (assoc-in [:lines cursor-line] after)
                                              (assoc :cursor-col 0)
                                              (assoc :cached-width nil :cached-lines nil)))))

                         ;; Ctrl+K — kill to end of line
                         (ctrl? data "K")
                         (swap! state (fn [{:keys [lines cursor-line cursor-col] :as s}]
                                        (let [line (nth lines cursor-line)
                                              before (subs line 0 cursor-col)]
                                          (-> s
                                              (assoc-in [:lines cursor-line] before)
                                              (assoc :cached-width nil :cached-lines nil)))))

                         ;; Ctrl+B — backward char
                         (ctrl? data "B")
                         (move-cursor 0 -1)

                         ;; Ctrl+F — forward char
                         (ctrl? data "F")
                         (move-cursor 0 1)

                         ;; Ctrl+P — command palette
                         (ctrl? data "P")
                         (when on-palette (on-palette))

                         ;; Ctrl+N — next line / history down
                         (ctrl? data "N")
                         (if (= (:cursor-line @state) (dec (count (:lines @state))))
                           (browse-history :down)
                           (move-cursor 1 0))

                         ;; Ctrl+W — delete word backward
                         (ctrl? data "W")
                         (delete-word-back)

                         ;; Ctrl+T — transpose characters
                         (ctrl? data "T")
                         (transpose-chars)

                         ;; Alt+B — backward word
                         (is-alt-b? data)
                         (move-word-back)

                         ;; Alt+F — forward word
                         (is-alt-f? data)
                         (move-word-forward)

                         ;; Alt+D — delete word forward
                         (is-alt-d? data)
                         (delete-word-forward)

                         ;; Alt+Backspace — delete word backward
                         (is-alt-backspace? data)
                         (delete-word-back)

                         ;; Bracketed paste
                         (is-paste-start? data)
                         (let [paste-start-seq (str ESC "[200~")
                               paste-end-seq (str ESC "[201~")
                               content (-> data
                                           (str/replace paste-start-seq "")
                                           (str/replace paste-end-seq ""))]
                           (doseq [ch content]
                             (if (= ch \newline)
                               (insert-newline)
                               (insert-char (str ch)))))

                         ;; "/" on empty editor — open command palette
                         (and (= data "/") on-palette (empty? (str/trim (get-text))))
                         (on-palette)

                         ;; Printable character
                         (is-printable? data)
                         (insert-char data)

                         ;; Multi-byte printable (emoji, unicode)
                         (and (> (count data) 1)
                              (not (str/starts-with? data ESC)))
                         (insert-char data)

                         :else nil)
                       (tui/request-render!))]

    {:type :editor
     :get-text get-text
     :set-text set-text
     :add-history add-history
     :invalidate (fn [] (swap! state assoc :cached-width nil :cached-lines nil))
     :handle-input handle-input
     :render (fn [width]
               (let [{:keys [lines cursor-line cursor-col]} @state
                     prompt-w (ansi/visible-width prompt)
                     content-w (max 1 (- width prompt-w))
                     prompt-pad (apply str (repeat prompt-w " "))
                     border (ansi/fg :dim (apply str (repeat width "─")))
                     editor-lines
                     (into []
                           (mapcat
                            (fn [[i line]]
                              (let [has-cursor (= i cursor-line)
                                    ;; Insert cursor marker into line text
                                    display (if has-cursor
                                              (let [cc (min cursor-col (count line))
                                                    before (subs line 0 cc)
                                                    after (subs line cc)
                                                    cursor-ch (if (seq after) (subs after 0 1) " ")
                                                    rest-str (if (seq after) (subs after 1) "")]
                                                (str before
                                                     (str ansi/ESC "7m" cursor-ch ansi/ESC "27m")
                                                     rest-str))
                                              line)
                                    ;; Word-wrap display text to fit content-w
                                    wrapped (ansi/wrap-text display content-w)]
                                ;; Prefix each visual line
                                (map-indexed
                                 (fn [vi vline]
                                   (let [pfx (if (and (zero? i) (zero? vi))
                                               (ansi/fg :accent prompt)
                                               prompt-pad)]
                                     (str pfx vline)))
                                 wrapped)))
                            (map-indexed vector lines)))]
                 (into [border] editor-lines)))}))
