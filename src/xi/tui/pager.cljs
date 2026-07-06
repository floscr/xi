(ns xi.tui.pager
  "Generic scrollable buffer viewer — 'pager mode'.

   Provides a title/summary header, vim-like scroll navigation, optional
   jump-to-change / jump-to-file navigation, and a help toolbar (exposed as
   :help so the bottom panel can render it). Any focused buffer that just
   needs to be paged through (difftastic output, the system-prompt buffer,
   plain text) uses this directly; the interactive unified-diff viewer
   (xi.tui.diff-buffer) is a specialization that supplies diff-rendered lines
   plus change/file jump positions.

   Keybindings:
     j/k, ↑/↓         Scroll up/down
     Ctrl-d/Ctrl-u     Half-page scroll
     Page Up/Down      Full-page scroll
     gg / G            Top / bottom
     ]c / [c           Next / previous change  (when sections present)
     ]f / [f           Next / previous file    (when sections present)
     :                 Enter command mode (focus editor)
     q / Escape        Close the buffer"
  (:require [clojure.string :as str]
            [xi.tui.ansi :as ansi]
            [xi.tui.core :as tui]
            [xi.tui.terminal :as term]))

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
  (help-bar [["j/k" "scroll"] ["gg/G" "top/bottom"] ["q" "close"] [":" "command"]]))

;; ── Component ─────────────────────────────────────────────────────────────────

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
     :on-command-mode  — (fn []) called when : is pressed"
  [{:keys [title header-fn lines-fn help on-close on-command-mode]}]
  (let [state (atom {:cached-lines nil
                     :cached-width nil
                     :change-starts []
                     :file-starts []
                     :pending-key nil})

        viewport-height (fn [] (max 1 (- (term/rows) 3)))

        current-top-line
        (fn []
          (let [total (count (:cached-lines @state))
                vh (viewport-height)
                offset (tui/get-scroll-offset)
                end (- total (min offset (max 0 (- total vh))))
                start (max 0 (- end vh))]
            start))

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
       (when (or (nil? (:cached-lines @state))
                 (not= width (:cached-width @state)))
         (let [{:keys [lines change-starts file-starts]} (lines-fn width)
               header (-> [(ansi/fg :bold (str " " (or title "Buffer")))]
                          (into (when header-fn (header-fn)))
                          (conj ""))
               header-len (count header)
               all-lines (into header lines)]
           (swap! state assoc
                  :cached-lines all-lines
                  :cached-width width
                  :change-starts (mapv #(+ % header-len) (or change-starts []))
                  :file-starts (mapv #(+ % header-len) (or file-starts [])))))
       (:cached-lines @state))}))

(defn make-text-buffer
  "Pager for plain (ANSI) text — no change/file sections.

   opts: :text :title :on-close :on-command-mode"
  [{:keys [text title on-close on-command-mode]}]
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
    :on-command-mode on-command-mode}))
