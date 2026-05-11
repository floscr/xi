(ns xi.tui.core
  "TUI engine — viewport-based component system with alternate screen buffer.
   Content is rendered into a scrollable viewport. A bottom panel (editor/menu)
   is pinned at the bottom of the screen. Scroll state is managed internally."
  (:require [clojure.string :as str]
            [xi.tui.ansi :as ansi]
            [xi.tui.terminal :as term]))

;; ── Component Protocol ────────────────────────────────────────────────────────
;;
;; Components are maps with:
;;   :render     (fn [width] -> [line1 line2 ...])
;;   :invalidate (fn [] -> nil)  — clear cached output
;;   :handle-input (fn [data] -> nil)  — optional, for focusable components
;;
;; Container is a component that collects lines from children.

(defn make-container
  "Create a container component. Children are rendered vertically."
  []
  (let [children (atom [])]
    {:type :container
     :children children
     :add-child (fn [child] (swap! children conj child))
     :remove-child (fn [child]
                     (swap! children (fn [cs] (vec (remove #(identical? % child) cs)))))
     :clear (fn [] (reset! children []))
     :render (fn [width]
               (into []
                     (mapcat (fn [c] ((:render c) width)))
                     @children))
     :invalidate (fn []
                   (doseq [c @children]
                     (when-let [inv (:invalidate c)]
                       (inv))))}))

;; ── TUI State ─────────────────────────────────────────────────────────────────

(defonce ^:private tui-state
  (atom {:terminal nil
         :content nil           ;; scrollable content container
         :bottom-panel nil      ;; pinned bottom panel (editor, menu, etc.)
         :focused nil
         :scroll-offset 0       ;; 0 = at bottom, positive = lines from bottom
         :prev-content-height 0 ;; for scroll stabilization
         :previous-frame []     ;; last rendered frame (exactly terminal height)
         :render-requested false
         :render-timer nil
         :stopped false}))

(def ^:private MIN_RENDER_INTERVAL_MS 16)

;; ── Scroll API ────────────────────────────────────────────────────────────────

(declare request-render!)

(defn scroll-up!
  "Scroll content up by n lines."
  [n]
  (swap! tui-state update :scroll-offset + n)
  (request-render!))

(defn scroll-down!
  "Scroll content down by n lines (towards bottom)."
  [n]
  (swap! tui-state update :scroll-offset #(max 0 (- % n)))
  (request-render!))

(defn scroll-to-bottom!
  "Snap viewport to the bottom (latest content)."
  []
  (swap! tui-state assoc :scroll-offset 0)
  (request-render!))

(defn scrolled-up?
  "Returns true if the viewport is scrolled up from the bottom."
  []
  (pos? (:scroll-offset @tui-state)))

;; ── Viewport Rendering ───────────────────────────────────────────────────────

(defn- do-render!
  "Execute the render pass — viewport-based with alternate screen buffer."
  []
  (let [{:keys [content bottom-panel scroll-offset prev-content-height
                previous-frame stopped]} @tui-state]
    (when (and content (not stopped))
      (let [width (term/columns)
            height (term/rows)

            ;; Render bottom panel (editor / completion menu)
            bottom-lines (if bottom-panel
                           ((:render bottom-panel) width)
                           [])
            bottom-height (count bottom-lines)

            ;; Viewport height = terminal height - bottom panel - 1 (status/separator line)
            viewport-height (max 1 (- height bottom-height 1))

            ;; Render all content
            all-content (vec ((:render content) width))
            total-content (count all-content)

            ;; Stabilize scroll when content grows while scrolled up
            content-delta (max 0 (- total-content prev-content-height))
            adjusted-offset (if (and (pos? scroll-offset) (pos? content-delta))
                              (+ scroll-offset content-delta)
                              scroll-offset)

            ;; Clamp scroll offset
            max-scroll (max 0 (- total-content viewport-height))
            effective-offset (min adjusted-offset max-scroll)

            ;; Calculate visible content range
            viewport-lines
            (if (<= total-content viewport-height)
              ;; All content fits — content at top, pad at bottom
              (into all-content (vec (repeat (- viewport-height total-content) "")))
              ;; Content exceeds viewport — show window based on scroll
              (let [end-idx (- total-content effective-offset)
                    start-idx (max 0 (- end-idx viewport-height))]
                (subvec all-content start-idx end-idx)))

            ;; Status/separator line — shows scroll indicator when scrolled up
            status-line (if (pos? effective-offset)
                          (ansi/pad-to-width
                           (ansi/fg :dim (str "── ↓ " effective-offset " more lines below ──"))
                           width)
                          "")

            ;; Combine: viewport + status + bottom panel
            new-frame (-> viewport-lines
                          (conj status-line)
                          (into bottom-lines))

            ;; Ensure frame is exactly terminal height
            new-frame (let [n (count new-frame)]
                        (cond
                          (> n height) (subvec new-frame 0 height)
                          (< n height) (into new-frame (repeat (- height n) ""))
                          :else new-frame))

            ;; Find first difference for differential update
            first-diff (loop [i 0]
                         (cond
                           (>= i height) nil
                           (>= i (count previous-frame)) i
                           (not= (nth new-frame i) (nth previous-frame i nil)) i
                           :else (recur (inc i))))]

        (when (some? first-diff)
          (term/sync-start!)
          ;; Only write lines that actually changed
          (doseq [i (range first-diff (count new-frame))]
            (let [new-line (nth new-frame i)
                  old-line (nth previous-frame i nil)]
              (when (not= new-line old-line)
                (term/cursor-to! (inc i) 1)
                (term/write! ansi/CLEAR_LINE)
                (term/write! new-line))))
          (term/sync-end!))

        (swap! tui-state assoc
               :previous-frame new-frame
               :scroll-offset effective-offset
               :prev-content-height total-content
               :render-requested false)))))

(defn request-render!
  "Request a render pass. Debounced to MIN_RENDER_INTERVAL_MS."
  []
  (when-not (:render-requested @tui-state)
    (swap! tui-state assoc :render-requested true)
    (when-let [t (:render-timer @tui-state)]
      (js/clearTimeout t))
    (let [timer (js/setTimeout do-render! MIN_RENDER_INTERVAL_MS)]
      (swap! tui-state assoc :render-timer timer))))

(defn render-now!
  "Force an immediate render (bypass debounce)."
  []
  (when-let [t (:render-timer @tui-state)]
    (js/clearTimeout t))
  (swap! tui-state assoc :render-timer nil)
  (do-render!))

;; ── Focus ─────────────────────────────────────────────────────────────────────

(defn set-focus!
  "Set the focused component (receives keyboard input)."
  [component]
  (swap! tui-state assoc :focused component))

;; ── Bottom Panel ──────────────────────────────────────────────────────────────

(defn set-bottom-panel!
  "Set the component pinned at the bottom of the screen (editor, menu, etc.)."
  [component]
  (swap! tui-state assoc :bottom-panel component)
  (request-render!))

;; ── Input Handling ────────────────────────────────────────────────────────────

(def ^:private ESC-STR (str (char 27)))

(defn- is-page-up? [data]
  (= data (str ESC-STR "[5~")))

(defn- is-page-down? [data]
  (= data (str ESC-STR "[6~")))

(defn- is-shift-up? [data]
  (= data (str ESC-STR "[1;2A")))

(defn- is-shift-down? [data]
  (= data (str ESC-STR "[1;2B")))

(defn- parse-mouse-event
  "Parse SGR mouse event. Returns {:button :col :row :pressed} or nil."
  [data]
  (when (str/starts-with? data (str ESC-STR "[<"))
    (when-let [[_ btn-s col-s row-s action]
               (re-matches #"\x1b\[<(\d+);(\d+);(\d+)([Mm])" data)]
      {:button (js/parseInt btn-s 10)
       :col (js/parseInt col-s 10)
       :row (js/parseInt row-s 10)
       :pressed (= action "M")})))

(defn- handle-input [data]
  (cond
    ;; Page Up — scroll up one page
    (is-page-up? data)
    (scroll-up! (max 1 (- (term/rows) 5)))

    ;; Page Down — scroll down one page
    (is-page-down? data)
    (scroll-down! (max 1 (- (term/rows) 5)))

    ;; Shift+Up — scroll up a few lines
    (is-shift-up? data)
    (scroll-up! 3)

    ;; Shift+Down — scroll down a few lines
    (is-shift-down? data)
    (scroll-down! 3)

    :else
    (if-let [mouse (parse-mouse-event data)]
      ;; Mouse events — handle scroll wheel
      (cond
        (= 64 (:button mouse)) (scroll-up! 3)
        (= 65 (:button mouse)) (scroll-down! 3)
        :else nil)  ;; ignore click events
      ;; Regular input — snap to bottom and pass to focused component
      (do
        (when (pos? (:scroll-offset @tui-state))
          (scroll-to-bottom!))
        (let [{:keys [focused]} @tui-state]
          (when (and focused (:handle-input focused))
            ((:handle-input focused) data)))))))

(defn- handle-resize []
  ;; Invalidate all components
  (when-let [content (:content @tui-state)]
    ((:invalidate content)))
  (when-let [bp (:bottom-panel @tui-state)]
    (when-let [inv (:invalidate bp)]
      (inv)))
  ;; Force full re-render (clear previous frame)
  (swap! tui-state assoc :previous-frame [])
  (request-render!))

;; ── Lifecycle ─────────────────────────────────────────────────────────────────

(defn create-tui!
  "Create and start the TUI. Returns the content container (scrollable area).
   Set the bottom panel (editor) via set-bottom-panel!."
  []
  (let [terminal (term/create-terminal)
        content (make-container)]
    (swap! tui-state assoc
           :terminal terminal
           :content content
           :bottom-panel nil
           :focused nil
           :scroll-offset 0
           :prev-content-height 0
           :previous-frame []
           :render-requested false
           :render-timer nil
           :stopped false)
    (term/start! terminal handle-input handle-resize)
    content))

(defn stop-tui!
  "Stop the TUI, restore terminal."
  []
  (when-let [t (:render-timer @tui-state)]
    (js/clearTimeout t))
  (swap! tui-state assoc :stopped true)
  (when-let [terminal (:terminal @tui-state)]
    (term/stop! terminal)))

(defn get-container
  "Get the content container."
  []
  (:content @tui-state))
