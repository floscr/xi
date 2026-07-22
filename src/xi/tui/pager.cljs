(ns xi.tui.pager
  "Generic scrollable buffer viewer — 'pager mode'.

   Provides a title/summary header, vim-like scroll navigation, optional
   jump-to-change / jump-to-file navigation, and a help toolbar (exposed as
   :help so the bottom panel can render it). Any focused buffer that just
   needs to be paged through (difftastic output, the system-prompt buffer,
   plain text) uses this directly; the interactive unified-diff viewer
   (xi.tui.diff-buffer) is a specialization that supplies diff-rendered lines
   plus change/file jump positions.

   A line-wise cursor (vim normal-mode style) tracks the current line; j/k move
   it and the viewport keeps a scroll-off margin so the cursor never sits at the
   very top/bottom edge until the buffer itself is at its top/bottom. v starts a
   line-wise selection and y yanks the current line (or selection) to the system
   clipboard. The buffer stays read-only.

   Keybindings:
     j/k, ↑/↓         Move cursor up/down
     v                 Start / cancel line-wise selection
     y                 Yank current line (or selection) to clipboard
     Ctrl-d/Ctrl-u     Half-page cursor jump
     Page Up/Down      Full-page cursor jump
     gg / G            Top / bottom
     ]c / [c           Next / previous change  (when sections present)
     ]f / [f           Next / previous file    (when sections present)
     :                 Enter command mode (focus editor)
     q / Escape        Close the buffer (Escape cancels selection first)"
  (:require [clojure.string :as str]
            [xi.tui.ansi :as ansi]
            [xi.tui.core :as tui]
            [xi.tui.terminal :as term])
  (:require-macros [xi.config-macros :refer [deftui-opt]]))

(deftui-opt pager-cursor-scroll-off 4
  "Number of lines the pager keeps between the line-wise cursor and the top or
   bottom edge of the viewport while moving with j/k (and the half/full-page
   jumps). The viewport scrolls to preserve this margin until the buffer itself
   is at its top or bottom, where the cursor is allowed to reach the edge.
   Clamped to half the viewport height on short terminals.
   Overridable via :pager-cursor-scroll-off in xi.config/tui.")

;; ── Key detection ─────────────────────────────────────────────────────────────

(def ^:private ESC (str (char 27)))

(defn- is-escape? [data]
  (or (= data ESC) (= data (str ESC "[27u"))))

(defn- is-arrow-up? [data] (= data (str ESC "[A")))
(defn- is-arrow-down? [data] (= data (str ESC "[B")))
(defn- ctrl? [data ch]
  (let [legacy-code (- (.charCodeAt ch 0) 64)
        codepoint (.charCodeAt (.toLowerCase ch) 0)]
    (or (= data (str (char legacy-code)))
        (= data (str ESC "[" codepoint ";5u")))))

(defn- is-ctrl-d? [data] (ctrl? data "D"))
(defn- is-ctrl-u? [data] (ctrl? data "U"))
(defn- is-page-up? [data] (= data (str ESC "[5~")))
(defn- is-page-down? [data] (= data (str ESC "[6~")))
(defn- is-enter? [data] (or (= data "\r") (= data "\n")))

;; ── Help bars ─────────────────────────────────────────────────────────────────

(defn help-bar
  "Assemble a single-line help toolbar from [key label] pairs, e.g.
   [[\"j/k\" \"scroll\"] [\"q\" \"close\"]]."
  [pairs]
  (let [dim (partial ansi/fg :dim)
        key (partial ansi/fg :accent)]
    (->> pairs
         (map (fn [[k label]] (str (key k) (dim (str ":" label)))))
         (str/join (dim "  ")))))

(def scroll-help
  "Help toolbar for a plain (section-less) pager."
  (help-bar [["j/k" "move"] ["v" "select"] ["y" "yank"]
             ["e" "explain"] ["\u23ce" "prompt"]
             ["gg/G" "top/bottom"] ["q" "close"] [":" "command"]]))

;; ── Component ─────────────────────────────────────────────────────────────────

;; Doom-style full-width line highlights (solid bg, syntax fg preserved).
(def ^:private cursor-line-bg "\033[48;2;59;66;82m")  ;; #3b4252 — current line
(def ^:private selection-bg   "\033[48;2;76;86;106m") ;; #4c566a — visual selection

(defn make-pager
  "Create a scrollable buffer viewer component.

   opts:
     :title           — title line shown bold at the top
     :header-fn        — (fn []) → [line ...] extra header lines (e.g. a diff
                         summary); optional
     :lines-fn         — (fn [width]) → {:lines [str]
                                         :change-starts [int]   ;; optional
                                         :file-starts   [int]}  ;; optional
                         produces the body lines (already width-aware) plus any
                         jump positions relative to the body
     :help             — help toolbar string (see help-bar); exposed as :help
     :on-close         — (fn []) called when q/Escape is pressed
     :on-command-mode  — (fn []) called when : is pressed
     :on-explain       — (fn [text]) called with the selected region on e;
                         optional (key is inert when absent)
     :on-prompt        — (fn [text]) called with the selected region on Enter;
                         optional (key is inert when absent)"
  [{:keys [title header-fn lines-fn help on-close on-command-mode
           on-explain on-prompt]}]
  (let [state (atom {:cached-lines nil
                     :cached-width nil
                     :change-starts []
                     :file-starts []
                     :header-len 0
                     :cursor nil      ;; absolute line index (into cached-lines)
                     :anchor nil      ;; visual-selection anchor line, nil = normal
                     :display-cache nil
                     :pending-key nil})

        line-count (fn [] (count (:cached-lines @state)))
        cursor-lo  (fn [] (:header-len @state 0))

        clamp-cursor
        (fn [n]
          (let [total (line-count) lo (cursor-lo)]
            (-> n (max lo) (min (max lo (dec total))))))

        ;; Scroll the viewport so the cursor keeps a scroll-off margin from the
        ;; top/bottom edge, unless the buffer itself is at its top/bottom.
        ;; Coordinates share the global content space (content == this pager).
        ensure-cursor-visible!
        (fn []
          (when-let [c (:cursor @state)]
            (let [vh (tui/viewport-height)
                  top (tui/viewport-top-line)
                  bottom (+ top (dec vh))
                  so (min pager-cursor-scroll-off (quot (dec vh) 2))
                  new-top (cond
                            (< (- c so) top) (- c so)
                            (> (+ c so) bottom) (- (+ c so) (dec vh))
                            :else top)]
              (when (not= new-top top)
                (tui/scroll-line-to-top! (max 0 new-top))))))

        set-cursor!
        (fn [n]
          (when (pos? (line-count))
            (swap! state assoc :cursor (clamp-cursor n))
            (ensure-cursor-visible!)
            (tui/request-render!)))

        move-cursor!
        (fn [delta]
          (set-cursor! (+ (or (:cursor @state) (cursor-lo)) delta)))

        jump-next!
        (fn [positions]
          (let [c (or (:cursor @state) (cursor-lo))
                target (first (filter #(> % c) positions))]
            (when target (set-cursor! target))))

        jump-prev!
        (fn [positions]
          (let [c (or (:cursor @state) (cursor-lo))
                target (last (filter #(< % c) positions))]
            (when target (set-cursor! target))))

        toggle-visual!
        (fn []
          (swap! state update :anchor
                 (fn [a] (when-not a (or (:cursor @state) (cursor-lo)))))
          (tui/request-render!))

        ;; Plain text of the current selection (current line when no anchor).
        selection-text
        (fn []
          (let [{:keys [cursor anchor cached-lines]} @state]
            (when cursor
              (let [a (or anchor cursor)
                    lo (min cursor a)
                    hi (max cursor a)]
                (->> (subvec cached-lines lo (inc hi))
                     (map ansi/strip-ansi)
                     (str/join "\n"))))))

        yank!
        (fn []
          (when-let [text (selection-text)]
            (tui/copy-to-clipboard! text)
            (swap! state assoc :anchor nil)
            (tui/request-render!)))

        ;; Hand the selected region to a host callback, then clear the anchor.
        run-region!
        (fn [f]
          (when f
            (when-let [text (selection-text)]
              (swap! state assoc :anchor nil)
              (f text))))]

    {:type :pager
     :capture-all-input true
     :help help

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
           (set-cursor! (cursor-lo))

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

           ;; j / ↓: cursor down
           (or (= data "j") (is-arrow-down? data))
           (move-cursor! 1)

           ;; k / ↑: cursor up
           (or (= data "k") (is-arrow-up? data))
           (move-cursor! -1)

           ;; G: go to bottom
           (= data "G")
           (set-cursor! (dec (line-count)))

           ;; v: toggle line-wise selection
           (= data "v")
           (toggle-visual!)

           ;; y: yank current line (or selection)
           (= data "y")
           (yank!)

           ;; e: explain the selected region (host submits a prompt)
           (= data "e")
           (run-region! on-explain)

           ;; Enter: send the selected region to the chat prompt
           (is-enter? data)
           (run-region! on-prompt)

           ;; Ctrl-d / Ctrl-u: half-page cursor jump
           (is-ctrl-d? data) (move-cursor! (quot (tui/viewport-height) 2))
           (is-ctrl-u? data) (move-cursor! (- (quot (tui/viewport-height) 2)))

           ;; Page Up / Page Down: full-page cursor jump
           (is-page-up? data) (move-cursor! (- (max 1 (- (tui/viewport-height) 2))))
           (is-page-down? data) (move-cursor! (max 1 (- (tui/viewport-height) 2)))

           ;; Escape: cancel selection first, else close
           (is-escape? data)
           (if (:anchor @state)
             (do (swap! state assoc :anchor nil) (tui/request-render!))
             (when on-close (on-close)))

           ;; q: close
           (= data "q")
           (when on-close (on-close))

           ;; :: command mode
           (= data ":")
           (when on-command-mode (on-command-mode))

           :else nil)))

     :invalidate
     (fn [] (swap! state assoc :cached-width nil :cached-lines nil :display-cache nil))

     :render
     (fn [width]
       (when (or (nil? (:cached-lines @state))
                 (not= width (:cached-width @state)))
         (let [{:keys [lines change-starts file-starts]} (lines-fn width)
               header (-> [(ansi/fg :bold (str " " (or title "Buffer")))]
                          (into (when header-fn (header-fn)))
                          (conj ""))
               header-len (count header)
               all-lines (into header lines)
               total (count all-lines)]
           (swap! state assoc
                  :cached-lines all-lines
                  :cached-width width
                  :header-len header-len
                  :display-cache nil
                  :change-starts (mapv #(+ % header-len) (or change-starts []))
                  :file-starts (mapv #(+ % header-len) (or file-starts [])))
           ;; Initialize / re-clamp the cursor to the (possibly re-wrapped) body.
           (swap! state update :cursor
                  (fn [c] (when (pos? total)
                            (-> (or c header-len) (max header-len) (min (dec total))))))))
       ;; Overlay the line-wise cursor / selection as a reverse-video bar,
       ;; cached by [width cursor anchor total] so unchanged frames reuse the
       ;; same vector (container diffs by identity) and only cursor moves repaint.
       (let [{:keys [cached-lines cursor anchor display-cache]} @state
             total (count cached-lines)
             key [width cursor anchor total]]
         (if (and display-cache (= key (:key display-cache)))
           (:lines display-cache)
           (let [bg (if anchor selection-bg cursor-line-bg)
                 lines (if (and cursor (pos? total))
                         (let [a (or anchor cursor)
                               lo (min cursor a)
                               hi (max cursor a)]
                           (into []
                                 (map-indexed
                                  (fn [i line]
                                    (if (<= lo i hi)
                                      (ansi/hl-line line width bg)
                                      line)))
                                 cached-lines))
                         cached-lines)]
             (swap! state assoc :display-cache {:key key :lines lines})
             lines))))}))

(defn make-text-buffer
  "Pager for plain (ANSI) text — no change/file sections.

   opts: :text :title :on-close :on-command-mode :on-explain :on-prompt"
  [{:keys [text title on-close on-command-mode on-explain on-prompt]}]
  (make-pager
   {:title (or title "Buffer")
    :lines-fn (fn [width]
                {:lines (if (str/blank? text)
                          ["  (empty)"]
                          (into [] (mapcat #(ansi/wrap-text % width))
                                (str/split-lines text)))
                 :change-starts []
                 :file-starts []})
    :help scroll-help
    :on-close on-close
    :on-command-mode on-command-mode
    :on-explain on-explain
    :on-prompt on-prompt}))
