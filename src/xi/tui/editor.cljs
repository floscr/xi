(ns xi.tui.editor
  "Raw-mode multi-line editor component.
   Renders a bordered input area at the bottom of the TUI.
   Handles character input, cursor movement, history, and paste."
  (:require [clojure.string :as str]
            [xi.tui.ansi :as ansi]
            [xi.tui.core :as tui]
            [xi.tui.snippets :as snippets]
            [xi.tui.terminal :as term])
  (:require-macros [xi.config-macros :refer [deftui-opt]]))

(deftui-opt prompt-max-visible-lines 10
  "Max number of visual lines the chat prompt editor shows before it starts
   scrolling internally to keep the cursor in view. The editor never grows past
   this many body lines (bounded further by terminal height); longer input
   scrolls with the cursor instead of overflowing the panel.
   Overridable via :prompt-max-visible-lines in xi.config/tui.")

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

(defn- is-tab? [data]
  (= data "\t"))

(defn- is-shift-tab? [data]
  ;; Legacy backtab (CBT) or kitty CSI-u (tab keycode 9, shift modifier 2).
  (or (= data (str ESC "[Z"))
      (= data (str ESC "[9;2u"))))

(defn- is-ctrl-i? [data]
  ;; Distinct from Tab only under the kitty keyboard protocol: 'i' is
  ;; codepoint 105, Ctrl is modifier 5. On terminals without kitty, Ctrl+I is
  ;; indistinguishable from Tab and this never matches.
  (= data (str ESC "[105;5u")))

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

(defn- is-ctrl-shift-g? [data]
  (= data (str ESC "[103;6u")))

(defn- is-ctrl-shift-z? [data]
  (= data (str ESC "[122;6u")))

(defn- is-ctrl-shift-n? [data]
  (= data (str ESC "[110;6u")))

(defn- is-alt-v? [data]
  (or (= data (str ESC "v"))
      (= data (str ESC "[118;3u"))))

(defn- is-ctrl-slash? [data]
  ;; Ctrl+/ emits 0x1F (legacy) or CSI-u \x1b[47;5u (kitty; '/' is codepoint 47).
  ;; The generic ctrl? helper can't derive this since 47-64 is negative.
  (or (= data (str (char 31)))
      (= data (str ESC "[47;5u"))))

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
                      :cached-lines nil
                      :undo-stack []
                      :redo-stack []
                      :last-action nil})
        prompt (or (:prompt opts) "xi> ")
        prompt-suffix-fn (:prompt-suffix-fn opts)
        prompt-right-fn (:prompt-right-fn opts)
        on-submit (:on-submit opts)
        on-interrupt (:on-interrupt opts)
        on-escape (:on-escape opts)
        on-palette (:on-palette opts)
        on-commands (:on-commands opts)
        on-git (:on-git opts)
        on-notify-toggle (:on-notify-toggle opts)
        on-paste-image (:on-paste-image opts)
        on-tab-complete (:on-tab-complete opts)
        word-candidates-fn (:word-candidates-fn opts)
        on-word-menu (:on-word-menu opts)
        ext-keybindings (:ext-keybindings opts)

        get-text (fn []
                   (str/join "\n" (:lines @state)))

        set-text (fn [text]
                   (let [lines (if (empty? text) [""] (str/split-lines text))]
                     (swap! state assoc
                            :lines (vec lines)
                            :cursor-line (dec (count lines))
                            :cursor-col (count (last lines))
                            :cached-width nil :cached-lines nil
                            :undo-stack [] :redo-stack [] :last-action nil))
                   (tui/request-panel-render!))

        add-history (fn [text]
                      (when (seq (str/trim text))
                        (swap! state update :history
                               (fn [h] (vec (take 100 (cons text h)))))
                        (swap! state assoc :history-index -1)))

        push-undo! (fn [action-type]
                     (swap! state (fn [s]
                                    (if (= action-type (:last-action s))
                                      s
                                      (let [snapshot (select-keys s [:lines :cursor-line :cursor-col])
                                            stack (conj (:undo-stack s) snapshot)
                                            stack (if (> (count stack) 100)
                                                    (subvec stack (- (count stack) 100))
                                                    stack)]
                                        (assoc s
                                               :undo-stack stack
                                               :redo-stack []
                                               :last-action action-type))))))

        insert-char (fn [ch]
                      (push-undo! :typing)
                      (swap! state (fn [{:keys [lines cursor-line cursor-col] :as s}]
                                     (let [line (nth lines cursor-line)
                                           new-line (str (subs line 0 cursor-col) ch (subs line cursor-col))]
                                       (-> s
                                           (assoc-in [:lines cursor-line] new-line)
                                           (update :cursor-col + (count ch))
                                           (assoc :cached-width nil :cached-lines nil
                                                  :history-index -1)))))
                      (tui/request-panel-render!))

        insert-newline (fn []
                         (push-undo! :newline)
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
                         (tui/request-panel-render!))

        delete-back (fn []
                      (push-undo! :delete-back)
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
                      (tui/request-panel-render!))

        delete-forward (fn []
                         (push-undo! :delete-forward)
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
                         (tui/request-panel-render!))

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
                                           (assoc :cached-width nil :cached-lines nil :last-action nil)))))
                      (tui/request-panel-render!))

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
                           (push-undo! :delete-back)
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
                           (tui/request-panel-render!))

        delete-word-forward (fn []
                              (push-undo! :delete-forward)
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
                              (tui/request-panel-render!))

        move-word-back (fn []
                         (swap! state (fn [{:keys [lines cursor-line cursor-col] :as s}]
                                        (if (pos? cursor-col)
                                          (let [line (nth lines cursor-line)
                                                target (find-word-start-backward line cursor-col)]
                                            (-> s
                                                (assoc :cursor-col target)
                                                (assoc :cached-width nil :cached-lines nil :last-action nil)))
                                          ;; At start of line — jump to end of previous line
                                          (if (pos? cursor-line)
                                            (let [prev-line (nth lines (dec cursor-line))]
                                              (-> s
                                                  (assoc :cursor-line (dec cursor-line))
                                                  (assoc :cursor-col (count prev-line))
                                                  (assoc :cached-width nil :cached-lines nil :last-action nil)))
                                            s))))
                         (tui/request-panel-render!))

        move-word-forward (fn []
                            (swap! state (fn [{:keys [lines cursor-line cursor-col] :as s}]
                                           (let [line (nth lines cursor-line)
                                                 len (count line)]
                                             (if (< cursor-col len)
                                               (let [target (find-word-end-forward line cursor-col)]
                                                 (-> s
                                                     (assoc :cursor-col target)
                                                     (assoc :cached-width nil :cached-lines nil :last-action nil)))
                                               ;; At end of line — jump to start of next line
                                               (if (< cursor-line (dec (count lines)))
                                                 (-> s
                                                     (assoc :cursor-line (inc cursor-line))
                                                     (assoc :cursor-col 0)
                                                     (assoc :cached-width nil :cached-lines nil :last-action nil))
                                                 s)))))
                            (tui/request-panel-render!))

        transpose-chars (fn []
                          (push-undo! :transpose)
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
                          (tui/request-panel-render!))

        insert-text-bulk (fn [text]
                           (push-undo! :paste)
                           (swap! state (fn [{:keys [lines cursor-line cursor-col] :as s}]
                                          (let [current-line (nth lines cursor-line)
                                                before (subs current-line 0 cursor-col)
                                                after (subs current-line cursor-col)
                                                combined (str before text after)
                                                new-lines (vec (str/split combined #"\n" -1))
                                                text-parts (str/split (str before text) #"\n" -1)
                                                new-cursor-line (+ cursor-line (dec (count text-parts)))
                                                new-cursor-col (count (last text-parts))
                                                result-lines (vec (concat
                                                                    (subvec lines 0 cursor-line)
                                                                    new-lines
                                                                    (subvec lines (inc cursor-line))))]
                                            (-> s
                                                (assoc :lines result-lines
                                                       :cursor-line new-cursor-line
                                                       :cursor-col new-cursor-col
                                                       :cached-width nil :cached-lines nil
                                                       :history-index -1)))))
                           (tui/request-panel-render!))

        delete-chars-back
        (fn [n]
          (when (pos? n)
            (push-undo! :delete-back)
            (swap! state (fn [{:keys [lines cursor-line cursor-col] :as s}]
                           ;; Flatten all text, compute absolute cursor pos, delete n chars before it
                           (let [full-text (str/join "\n" lines)
                                 abs-pos (+ cursor-col
                                            (reduce + 0 (map #(inc (count (nth lines %)))
                                                             (range cursor-line))))
                                 del-start (max 0 (- abs-pos n))
                                 new-text (str (subs full-text 0 del-start)
                                               (subs full-text abs-pos))
                                 new-lines (vec (str/split new-text #"\n" -1))
                                 ;; Compute new cursor position from del-start
                                 before-cursor (subs new-text 0 del-start)
                                 before-parts (str/split before-cursor #"\n" -1)
                                 new-cursor-line (dec (count before-parts))
                                 new-cursor-col (count (last before-parts))]
                             (-> s
                                 (assoc :lines new-lines
                                        :cursor-line new-cursor-line
                                        :cursor-col new-cursor-col
                                        :cached-width nil :cached-lines nil)))))
            (tui/request-panel-render!)))

        do-undo (fn []
                  (swap! state (fn [{:keys [undo-stack] :as s}]
                                 (if (seq undo-stack)
                                   (let [snapshot (peek undo-stack)]
                                     (-> s
                                         (update :undo-stack pop)
                                         (update :redo-stack conj (select-keys s [:lines :cursor-line :cursor-col]))
                                         (merge snapshot)
                                         (assoc :last-action nil
                                                :cached-width nil :cached-lines nil)))
                                   s)))
                  (tui/request-panel-render!))

        do-redo (fn []
                  (swap! state (fn [{:keys [redo-stack] :as s}]
                                 (if (seq redo-stack)
                                   (let [snapshot (peek redo-stack)]
                                     (-> s
                                         (update :redo-stack pop)
                                         (update :undo-stack conj (select-keys s [:lines :cursor-line :cursor-col]))
                                         (merge snapshot)
                                         (assoc :last-action nil
                                                :cached-width nil :cached-lines nil)))
                                   s)))
                  (tui/request-panel-render!))

        handle-submit (fn []
                        (let [text (get-text)]
                          (when (seq (str/trim text))
                            (add-history text)
                            (set-text "")
                            (when on-submit
                              (on-submit text)))))

        ;; ── Word completion (inline Tab cycling) ─────────────────────────
        ;; The word before the cursor and where it starts on the line.
        word-before-cursor
        (fn []
          (let [{:keys [lines cursor-line cursor-col]} @state
                line (nth lines cursor-line)
                before (subs line 0 cursor-col)
                word-start (loop [i (dec (count before))]
                             (cond
                               (neg? i) 0
                               (= " " (.charAt before i)) (inc i)
                               :else (recur (dec i))))]
            {:word-start word-start
             :trigger (subs before word-start (count before))}))

        ;; Replace the region [word-start, cursor] on `line-idx` with `word`,
        ;; leaving the cursor at its end. Works both for the initial insert
        ;; (region = typed token) and for cycling (region = previous candidate).
        apply-candidate!
        (fn [word-start line-idx word]
          (swap! state (fn [{:keys [lines cursor-col] :as s}]
                         (let [line (nth lines line-idx)
                               new-line (str (subs line 0 word-start)
                                             word
                                             (subs line cursor-col))]
                           (-> s
                               (assoc-in [:lines line-idx] new-line)
                               (assoc :cursor-col (+ word-start (count word)))
                               (assoc :cached-width nil :cached-lines nil)))))
          (tui/request-panel-render!))

        ;; True when a completion session is live and the cursor still sits at
        ;; the end of the last-inserted candidate (nothing else has moved it).
        word-completion-active?
        (fn []
          (let [{:keys [cursor-line cursor-col completion]} @state]
            (and completion
                 (= (:line completion) cursor-line)
                 (= (:end-col completion) cursor-col))))

        ;; Advance the active session by `delta` candidates (wrapping) and
        ;; swap the inserted word.
        cycle-word!
        (fn [delta]
          (let [{:keys [cursor-line completion]} @state
                cands (:candidates completion)
                idx (mod (+ (:index completion) delta) (count cands))
                word (nth cands idx)]
            (push-undo! :word-complete)
            (apply-candidate! (:word-start completion) cursor-line word)
            (swap! state update :completion assoc
                   :index idx
                   :end-col (+ (:word-start completion) (count word)))))

        ;; Start a fresh session for `trigger`. Returns true when candidates
        ;; were found (and the first inserted), false to let the caller fall
        ;; through to path completion.
        start-word-completion!
        (fn [trigger word-start]
          (let [cands (when (and word-candidates-fn (seq trigger))
                        (vec (word-candidates-fn trigger)))]
            (if (seq cands)
              (let [{:keys [cursor-line]} @state
                    word (first cands)]
                (push-undo! :word-complete)
                (apply-candidate! word-start cursor-line word)
                (swap! state assoc :completion
                       {:line cursor-line
                        :word-start word-start
                        :candidates cands
                        :index 0
                        :end-col (+ word-start (count word))})
                true)
              false)))

        handle-input (fn [data]
                       ;; Any key other than Tab/Shift+Tab ends an active
                       ;; word-completion cycle.
                       (when-not (or (is-tab? data) (is-shift-tab? data))
                         (swap! state dissoc :completion))
                       (cond
                         ;; Extension keybindings (checked first so they can intercept Ctrl+C etc.)
                         (some (fn [{:keys [key-fn]}] (key-fn data)) ext-keybindings)
                         (let [{:keys [handler]} (first (filter #((:key-fn %) data) ext-keybindings))]
                           (handler))

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
                                            (assoc :cached-width nil :cached-lines nil :last-action nil))))

                         ;; End / Ctrl+E
                         (is-end? data)
                         (swap! state (fn [{:keys [lines cursor-line] :as s}]
                                        (-> s
                                            (assoc :cursor-col (count (nth lines cursor-line)))
                                            (assoc :cached-width nil :cached-lines nil :last-action nil))))

                         ;; Ctrl+U — kill line
                         (ctrl? data "U")
                         (do (push-undo! :kill)
                             (swap! state (fn [{:keys [lines cursor-line] :as s}]
                                            (let [line (nth lines cursor-line)
                                                  after (subs line (:cursor-col s))]
                                              (-> s
                                                  (assoc-in [:lines cursor-line] after)
                                                  (assoc :cursor-col 0)
                                                  (assoc :cached-width nil :cached-lines nil))))))

                         ;; Ctrl+K — kill to end of line
                         (ctrl? data "K")
                         (do (push-undo! :kill)
                             (swap! state (fn [{:keys [lines cursor-line cursor-col] :as s}]
                                            (let [line (nth lines cursor-line)
                                                  before (subs line 0 cursor-col)]
                                              (-> s
                                                  (assoc-in [:lines cursor-line] before)
                                                  (assoc :cached-width nil :cached-lines nil))))))

                         ;; Ctrl+B — backward char
                         (ctrl? data "B")
                         (move-cursor 0 -1)

                         ;; Ctrl+F — forward char
                         (ctrl? data "F")
                         (move-cursor 0 1)

                         ;; Ctrl+P — command palette (Chats/Actions/Commands)
                         (ctrl? data "P")
                         (when on-palette (on-palette))

                         ;; Ctrl+/ — slash commands menu (works with text in editor)
                         (is-ctrl-slash? data)
                         (when on-commands (on-commands))

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

                         ;; Ctrl+Z — undo
                         (ctrl? data "Z")
                         (do-undo)

                         ;; Ctrl+Shift+Z / Ctrl+Y — redo
                         (or (is-ctrl-shift-z? data) (ctrl? data "Y"))
                         (do-redo)

                         ;; Ctrl+Shift+G — open git status
                         (is-ctrl-shift-g? data)
                         (when on-git (on-git))

                         ;; Ctrl+Shift+N — toggle notification
                         (is-ctrl-shift-n? data)
                         (when on-notify-toggle (on-notify-toggle))

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

                         ;; Alt+V — paste clipboard image
                         (is-alt-v? data)
                         (when on-paste-image (on-paste-image))

                         ;; Bracketed paste
                         (is-paste-start? data)
                         (let [paste-start-seq (str ESC "[200~")
                               paste-end-seq (str ESC "[201~")
                               content (-> data
                                           (str/replace paste-start-seq "")
                                           (str/replace paste-end-seq ""))]
                           (insert-text-bulk content))

                         ;; Shift+Tab — cycle the active word completion backward
                         (and (is-shift-tab? data) (word-completion-active?))
                         (cycle-word! -1)

                         ;; Ctrl+I — open the word completion menu for the word
                         ;; before the cursor (kitty protocol only; falls through
                         ;; to Tab on terminals that can't disambiguate it).
                         (and (is-ctrl-i? data) on-word-menu)
                         (on-word-menu (word-before-cursor))

                         ;; Tab — cycle word completion, else snippet expansion,
                         ;; else path completion. Order: cycle an active session,
                         ;; then snippet, then start a word cycle, then path.
                         (is-tab? data)
                         (cond
                           ;; Continue an in-progress inline word cycle.
                           (word-completion-active?)
                           (cycle-word! 1)

                           :else
                           (let [{:keys [word-start trigger]} (word-before-cursor)
                                 expansion (and (seq trigger) (snippets/expand trigger))]
                             (cond
                               expansion
                               (do
                                 (push-undo! :snippet)
                                 (swap! state (fn [{:keys [lines cursor-line cursor-col] :as s}]
                                                (let [line (nth lines cursor-line)
                                                      new-line (str (subs line 0 word-start)
                                                                    expansion
                                                                    (subs line cursor-col))]
                                                  (-> s
                                                      (assoc-in [:lines cursor-line] new-line)
                                                      (assoc :cursor-col (+ word-start (count expansion)))
                                                      (assoc :cached-width nil :cached-lines nil)))))
                                 (tui/request-panel-render!))

                               ;; Start an inline word cycle from the buffer's
                               ;; vocabulary; falls back to path completion when
                               ;; there are no word candidates (e.g. a '/' token).
                               (start-word-completion! trigger word-start)
                               nil

                               on-tab-complete
                               (on-tab-complete {:token trigger :insert! insert-char}))))

                         ;; "/" on empty editor — open slash commands menu
                         (and (= data "/") on-commands (empty? (str/trim (get-text))))
                         (on-commands)

                         ;; Printable character
                         (is-printable? data)
                         (insert-char data)

                         ;; Multi-byte printable (emoji, unicode)
                         (and (> (count data) 1)
                              (not (str/starts-with? data ESC)))
                         (insert-char data)

                         :else nil)
                       (tui/request-panel-render!))]

    {:type :editor
     :get-text get-text
     :set-text set-text
     :submit handle-submit
     :insert-text insert-text-bulk
     :delete-chars-back delete-chars-back
     :add-history add-history
     :invalidate (fn [] (swap! state assoc :cached-width nil :cached-lines nil))
     :handle-input handle-input
     :render (fn [width]
               (let [{:keys [lines cursor-line cursor-col]} @state
                     suffix (if prompt-suffix-fn (or (prompt-suffix-fn) "") "")
                     full-prompt-w (+ (ansi/visible-width prompt) (ansi/visible-width suffix))
                     content-w (max 1 (- width full-prompt-w))
                     prompt-pad (apply str (repeat full-prompt-w " "))
                     right-text (when prompt-right-fn (prompt-right-fn))
                     right-w (if right-text (ansi/visible-width right-text) 0)
                     ;; border with optional right-aligned label
                     border (if (and right-text (pos? right-w) (>= width (+ right-w 4)))
                              (let [pad 1
                                    bar-w (- width right-w pad)]
                                (str (ansi/fg :border (apply str (repeat bar-w "─")))
                                     (apply str (repeat pad " "))
                                     right-text))
                              (ansi/fg :border (apply str (repeat width "─"))))
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
                                               (str (ansi/fg :accent prompt) suffix)
                                               prompt-pad)]
                                     (str pfx vline)))
                                 wrapped)))
                            (map-indexed vector lines)))
                     ;; Scroll the editor body to keep the cursor visible when
                     ;; the input is taller than the panel budget.
                     total (count editor-lines)
                     budget (min prompt-max-visible-lines
                                 (max 1 (- (term/rows) 6)))
                     body
                     (if (<= total budget)
                       editor-lines
                       (let [cursor-idx
                             (or (some (fn [[i l]]
                                         (when (str/includes? l (str ansi/ESC "7m")) i))
                                       (map-indexed vector editor-lines))
                                 (dec total))
                             start (-> (- cursor-idx (quot budget 2))
                                       (max 0)
                                       (min (- total budget)))
                             end (+ start budget)
                             window (subvec editor-lines start end)
                             above (when (pos? start)
                                     (str prompt-pad (ansi/fg :dim (str "\u2191 " start " more"))))
                             below (when (< end total)
                                     (str prompt-pad (ansi/fg :dim (str "\u2193 " (- total end) " more"))))]
                         (cond-> window
                           above (->> (into [above]))
                           below (conj below))))]
                 (into [border] body)))}))
