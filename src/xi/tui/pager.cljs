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
   very top/bottom edge until the buffer itself is at its top/bottom. V starts a
   line-wise selection and y yanks the current line (or selection) to the system
   clipboard. The buffer stays read-only.

   Keys are not hard-wired: every keypress is decoded (xi.tui.keys/decode) and
   resolved through the TUI keymap in the pager's layers — the buffer's own
   layer (:buffer/diff, :buffer/file, …), then :buffer/pager, :mode/navigate
   and :global — so config.edn `:keys` can rebind any of them. The built-in
   `:buffer/pager` keys (xi.keys/defaults):
     j/k, ↑/↓         Move cursor up/down             :pager/down :pager/up
     V                 Start / cancel line-wise selection      :pager/select
     y                 Yank current line (or selection)        :pager/yank
     e / Enter         Explain selection / put it in the editor
     Ctrl-d/Ctrl-u     Half-page cursor jump
     Page Up/Down      Full-page cursor jump
     gg / G            Top / bottom
     ]c / [c           Next / previous change  (when sections present)
     ]f / [f           Next / previous file    (when sections present)
     :                 Enter command mode (focus editor)       :pager/command
     q / Escape        Close the buffer (Escape cancels selection first)
   Actions the pager does not own (:agent/abort, :chat/new, :keys/show …) go
   to the host's `:run-action`."
  (:require [clojure.string :as str]
            [xi.config]
            [xi.keys :as keys]
            [xi.tui.ansi :as ansi]
            [xi.tui.core :as tui]
            [xi.tui.keys :as tui-keys]
            [xi.tui.terminal :as term])
  (:require-macros [xi.config-macros :refer [deftui-opt]]))

(deftui-opt pager-cursor-scroll-off 4
  "Number of lines the pager keeps between the line-wise cursor and the top or
   bottom edge of the viewport while moving with j/k (and the half/full-page
   jumps). The viewport scrolls to preserve this margin until the buffer itself
   is at its top or bottom, where the cursor is allowed to reach the edge.
   Clamped to half the viewport height on short terminals.
   Overridable via :pager-cursor-scroll-off in xi.config/tui.")

;; ── Help bars ─────────────────────────────────────────────────────────────────

(defn help-bar
  "Assemble a single-line help toolbar from [key label] pairs, e.g.
   [[\"j/k\" \"scroll\"] [\"q\" \"close\"]]. For keys the keymap owns prefer
   `keymap-help`, which reads the bound keys."
  [pairs]
  (let [dim (partial ansi/fg :dim)
        key (partial ansi/fg :accent)]
    (->> pairs
         (map (fn [[k label]] (str (key k) (dim (str ":" label)))))
         (str/join (dim "  ")))))

(def pager-help-entries
  "[[action-ids] label] entries of the generic pager's help bar, resolved
   against the keymap by `keymap-help` so a rebound key shows as bound."
  [[[:pager/down :pager/up] "move"]
   [[:pager/select] "select"]
   [[:pager/yank] "yank"]
   [[:pager/explain] "explain"]
   [[:pager/prompt] "prompt"]
   [[:pager/top :pager/bottom] "top/bottom"]
   [[:pager/close] "close"]
   [[:pager/command] "command"]])

(defn keymap-help
  "Help toolbar for a pager whose own layer is `layer` (may be nil), from
   `entries` ([[action-ids] label], default `pager-help-entries`) and the
   installed keymap."
  ([layer] (keymap-help layer pager-help-entries))
  ([layer entries]
   (tui-keys/help-bar (cond->> tui-keys/pager-layers layer (cons layer)) entries)))

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
     :layer            — this buffer's own keymap layer (:buffer/diff,
                         :buffer/file, …), resolved before :buffer/pager;
                         optional
     :actions          — {action-id (fn [ctx])} the buffer adds to the pager's
                         own (e.g. :diff/edit); ctx as for :extra-keys. A
                         returned false means \"not handled here\" and the key
                         falls through to :extra-keys-less built-ins; optional
     :help             — help toolbar string (see keymap-help); defaults to
                         the generic pager help for :layer
     :host             — {:layers-fn   (fn [] → extra layers, inner first,
                                         e.g. [:agent-busy]); optional
                          :run-action  (fn [action-id] → bool) runs an action
                                         the pager does not own; optional
                          :enabled?    (fn [action-id] → bool) guard for host
                                         actions; optional}
     :on-close         — (fn []) called by :pager/close
     :on-command-mode  — (fn []) called by :pager/command
     :on-explain       — (fn [text]) called with the selected region by
                         :pager/explain; optional (inert when absent)
     :on-prompt        — (fn [text]) called with the selected region by
                         :pager/prompt; optional (inert when absent)
     :extra-keys       — (fn [data ctx]) tried BEFORE the keymap, on the raw
                         input; return truthy when the key was handled (falsy
                         falls through). ctx keys: :cursor (absolute line
                         index), :body-cursor (relative to the lines-fn body;
                         nil while above it / before first render),
                         :set-cursor! (fn [n]), :invalidate! (0-arg: drop the
                         cached body so lines-fn re-runs — use when a key
                         mutates lines-fn output), :file-starts (absolute jump
                         positions), :jump-next-file! / :jump-prev-file!
                         (0-arg fns over the :file-starts jump positions).
                         Optional — lets specializations (e.g. the sub-agents
                         buffer) add selection/stop keys."
  [{:keys [title header-fn lines-fn layer actions help host on-close
           on-command-mode on-explain on-prompt extra-keys]}]
  (let [state (atom {:cached-lines nil
                     :cached-width nil
                     :change-starts []
                     :file-starts []
                     :header-len 0
                     :cursor nil      ;; absolute line index (into cached-lines)
                     :anchor nil      ;; visual-selection anchor line, nil = normal
                     :display-cache nil
                     :pending []})    ;; chords of a key sequence in progress

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

        ;; Scroll so the cursor sits at the vertical middle of the viewport
        ;; (vim `zz`), clamped at the content top. Used by jump navigation.
        center-cursor!
        (fn []
          (when-let [c (:cursor @state)]
            (tui/scroll-line-to-top! (max 0 (- c (quot (tui/viewport-height) 2))))))

        set-cursor!
        (fn [n]
          (if (pos? (line-count))
            (do (swap! state assoc :cursor (clamp-cursor n))
                (ensure-cursor-visible!)
                (tui/request-render!))
            ;; No cached lines (e.g. just invalidated): store the raw value —
            ;; the next render pass clamps it into the rebuilt body.
            (do (swap! state assoc :cursor n)
                (tui/request-render!))))

        move-cursor!
        (fn [delta]
          (set-cursor! (+ (or (:cursor @state) (cursor-lo)) delta)))

        jump-next!
        (fn [positions]
          (let [c (or (:cursor @state) (cursor-lo))
                target (first (filter #(> % c) positions))]
            (when target (set-cursor! target) (center-cursor!))))

        jump-prev!
        (fn [positions]
          (let [c (or (:cursor @state) (cursor-lo))
                target (last (filter #(< % c) positions))]
            (when target (set-cursor! target) (center-cursor!))))

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
              (f text))))

        ;; Drop the cached body so the next render re-runs lines-fn (used when a
        ;; host key mutates what lines-fn produces, e.g. collapsing a file).
        invalidate!
        (fn []
          (swap! state assoc :cached-width nil :cached-lines nil :display-cache nil)
          (tui/request-render!))

        ;; The ctx handed to :extra-keys and :actions fns.
        key-ctx
        (fn []
          (let [c (:cursor @state)
                hl (:header-len @state 0)]
            {:cursor c
             :body-cursor (when (and c (>= c hl)) (- c hl))
             :set-cursor! set-cursor!
             :invalidate! invalidate!
             :file-starts (:file-starts @state)
             :jump-next-file! #(jump-next! (:file-starts @state))
             :jump-prev-file! #(jump-prev! (:file-starts @state))}))

        ;; The pager's own actions (xi.keys/catalog :pager/*), each (fn [ctx]).
        builtin-actions
        {:pager/down        (fn [_] (move-cursor! 1))
         :pager/up          (fn [_] (move-cursor! -1))
         :pager/top         (fn [_] (set-cursor! (cursor-lo)))
         :pager/bottom      (fn [_] (set-cursor! (dec (line-count))))
         :pager/half-down   (fn [_] (move-cursor! (quot (tui/viewport-height) 2)))
         :pager/half-up     (fn [_] (move-cursor! (- (quot (tui/viewport-height) 2))))
         :pager/page-down   (fn [_] (move-cursor! (max 1 (- (tui/viewport-height) 2))))
         :pager/page-up     (fn [_] (move-cursor! (- (max 1 (- (tui/viewport-height) 2)))))
         :pager/select      (fn [_] (toggle-visual!))
         :pager/yank        (fn [_] (yank!))
         :pager/explain     (fn [_] (run-region! on-explain))
         :pager/prompt      (fn [_] (run-region! on-prompt))
         :pager/next-change (fn [_] (jump-next! (:change-starts @state)))
         :pager/prev-change (fn [_] (jump-prev! (:change-starts @state)))
         :diff/next-file    (fn [_] (jump-next! (:file-starts @state)))
         :diff/prev-file    (fn [_] (jump-prev! (:file-starts @state)))
         ;; Escape: cancel selection first, else close
         :pager/close       (fn [_]
                              (if (:anchor @state)
                                (do (swap! state assoc :anchor nil) (tui/request-render!))
                                (when on-close (on-close))))
         :pager/command     (fn [_] (when on-command-mode (on-command-mode)))}

        all-actions (merge builtin-actions actions)

        layers
        (fn []
          (-> []
              (into (when-let [f (:layers-fn host)] (f)))
              (cond-> layer (conj layer))
              (into tui-keys/pager-layers)))

        enabled?
        (fn [id]
          (or (contains? all-actions id)
              (if-let [f (:enabled? host)] (boolean (f id)) (some? (:run-action host)))))

        run-action!
        (fn [id]
          (if-let [f (get all-actions id)]
            (f (key-ctx))
            (when-let [run (:run-action host)]
              (run id))))]

    {:type :pager
     :capture-all-input true
     :help (or help (keymap-help layer))
     :set-cursor! set-cursor!

     :handle-scroll
     (fn [delta]
       (if (neg? delta)
         (tui/scroll-up! (- delta))
         (tui/scroll-down! delta)))

     :handle-input
     (fn [data]
       (let [pending (:pending @state)]
         (cond
           ;; Specialization keys run first (expand/stop in the sub-agents
           ;; buffer); falsy return falls through to the keymap.
           (and extra-keys (extra-keys data (key-ctx)))
           (swap! state assoc :pending [])

           :else
           (if-let [chord (tui-keys/decode data)]
             (let [res (tui-keys/lookup (layers) pending chord enabled?)]
               (case (:status res)
                 :action  (do (swap! state assoc :pending [])
                              (run-action! (:action res)))
                 :pending (swap! state assoc :pending (:pending res))
                 (swap! state assoc :pending [])))
             (swap! state assoc :pending [])))))

     :invalidate invalidate!

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

   opts: :text :title :layer :host :on-close :on-command-mode :on-explain
   :on-prompt"
  [{:keys [text title layer host on-close on-command-mode on-explain on-prompt]}]
  (make-pager
   {:title (or title "Buffer")
    :lines-fn (fn [width]
                {:lines (if (str/blank? text)
                          ["  (empty)"]
                          (into [] (mapcat #(ansi/wrap-text % width))
                                (str/split-lines text)))
                 :change-starts []
                 :file-starts []})
    :layer layer
    :host host
    :on-close on-close
    :on-command-mode on-command-mode
    :on-explain on-explain
    :on-prompt on-prompt}))
