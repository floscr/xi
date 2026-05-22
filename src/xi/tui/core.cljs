(ns xi.tui.core
  "TUI engine — viewport-based component system with alternate screen buffer.
   Content is rendered into a scrollable viewport. A bottom panel (editor/menu)
   is pinned at the bottom of the screen. Scroll state is managed internally.
   Mouse text selection is handled in-app with OSC 52 clipboard copy."
  (:require [clojure.string :as str]
            [xi.tui.ansi :as ansi]
            [xi.tui.grid :as grid]
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
         :previous-frame []     ;; last rendered frame lines (for selection text extraction)
         :previous-grid nil      ;; last rendered cell grid (for cell-level diffing)
         :render-requested false
         :render-timer nil
         :stopped false
         :content-dirty true     ;; content needs re-render (false = use cached lines)
         :cached-content-lines nil ;; cached output of content render
         :cached-content-width nil ;; width used for cached content
         ;; Mouse selection state
         ;; nil when no selection, map when selecting/selected:
         ;; {:start-row :start-col :end-row :end-col :selecting}
         ;; row/col are 0-based screen coordinates
         :selection nil}))

(def ^:private MIN_RENDER_INTERVAL_MS 16)

;; ── Scroll API ────────────────────────────────────────────────────────────────

(declare request-render! request-panel-render!)

(defn scroll-up!
  "Scroll content up by n lines."
  [n]
  (swap! tui-state update :scroll-offset + n)
  (request-panel-render!))

(defn scroll-down!
  "Scroll content down by n lines (towards bottom)."
  [n]
  (swap! tui-state update :scroll-offset #(max 0 (- % n)))
  (request-panel-render!))

(defn scroll-to-bottom!
  "Snap viewport to the bottom (latest content)."
  []
  (swap! tui-state assoc :scroll-offset 0)
  (request-panel-render!))

(defn scrolled-up?
  "Returns true if the viewport is scrolled up from the bottom."
  []
  (pos? (:scroll-offset @tui-state)))

;; ── Selection Helpers ─────────────────────────────────────────────────────────

(defn- normalize-selection
  "Ensure start is before end (user may drag upward)."
  [{:keys [start-row start-col end-row end-col] :as sel}]
  (if (or (< end-row start-row)
          (and (= end-row start-row) (< end-col start-col)))
    (assoc sel
           :start-row end-row :start-col end-col
           :end-row start-row :end-col start-col)
    sel))

(defn- clear-selection! []
  (when (:selection @tui-state)
    (swap! tui-state assoc :selection nil)
    (request-panel-render!)))

(defn- apply-selection-to-frame
  "Apply reverse-video highlighting to lines within the selection range."
  [frame selection]
  (if-not selection
    frame
    (let [{:keys [start-row start-col end-row end-col]} (normalize-selection selection)]
      (vec (map-indexed
            (fn [i line]
              (cond
                ;; Before or after selection
                (or (< i start-row) (> i end-row))
                line

                ;; Single-line selection
                (and (= i start-row) (= i end-row))
                (ansi/highlight-range line start-col end-col)

                ;; First line of multi-line selection
                (= i start-row)
                (ansi/highlight-range line start-col (ansi/visible-width line))

                ;; Last line of multi-line selection
                (= i end-row)
                (ansi/highlight-range line 0 end-col)

                ;; Middle line — highlight entire line
                :else
                (ansi/highlight-range line 0 (ansi/visible-width line))))
            frame)))))

(defn- extract-selection-text
  "Extract plain text from the selection area of the rendered frame."
  [frame selection]
  (let [{:keys [start-row start-col end-row end-col]} (normalize-selection selection)
        lines (for [r (range start-row (inc end-row))
                    :let [line (ansi/strip-ansi (nth frame r ""))
                          from (if (= r start-row) (min start-col (count line)) 0)
                          to (if (= r end-row) (min end-col (count line)) (count line))]]
                (subs line (min from (count line)) (min to (count line))))]
    (str/join "\n" lines)))

(defn- copy-to-clipboard!
  "Copy text to system clipboard via OSC 52."
  [text]
  (when (seq text)
    (let [b64 (.toString (js/Buffer.from text "utf-8") "base64")]
      (term/write! (str "\033]52;c;" b64 "\007")))))

;; ── Viewport Rendering ───────────────────────────────────────────────────────

(defn- do-render!
  "Execute the render pass — viewport-based with alternate screen buffer."
  []
  (let [{:keys [content bottom-panel scroll-offset prev-content-height
                previous-frame stopped suspended selection]} @tui-state]
    (when (and content (not stopped) (not suspended))
      (let [width (term/columns)
            height (term/rows)

            ;; Render bottom panel (editor / completion menu)
            bottom-lines (if bottom-panel
                           ((:render bottom-panel) width)
                           [])
            bottom-height (count bottom-lines)

            ;; Viewport height = terminal height - bottom panel - 1 (status/separator line)
            viewport-height (max 1 (- height bottom-height 1))

            ;; Render content (skip if clean and width unchanged)
            {:keys [content-dirty cached-content-lines cached-content-width]} @tui-state
            all-content (if (and (not content-dirty)
                                cached-content-lines
                                (= cached-content-width width))
                          cached-content-lines
                          (let [lines (vec ((:render content) width))]
                            (swap! tui-state assoc
                                   :content-dirty false
                                   :cached-content-lines lines
                                   :cached-content-width width)
                            lines))
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
                           (ansi/fg :border (str "── ↓ " effective-offset " more lines below ──"))
                           width)
                          "")

            ;; Combine: viewport + status + bottom panel
            new-frame (-> viewport-lines
                          (conj status-line)
                          (into bottom-lines))

            ;; Safety net: truncate any line exceeding terminal width
            ;; Components should wrap/truncate themselves, but if any line
            ;; overflows, the terminal wraps it visually and corrupts layout
            new-frame (mapv (fn [line]
                              (if (> (ansi/visible-width line) width)
                                (ansi/truncate-to-width line width)
                                line))
                            new-frame)

            ;; Ensure frame is exactly terminal height
            new-frame (let [n (count new-frame)]
                        (cond
                          (> n height) (subvec new-frame 0 height)
                          (< n height) (into new-frame (repeat (- height n) ""))
                          :else new-frame))

            ;; Apply selection highlighting (visual only — doesn't affect previous-frame diff)
            display-frame (if selection
                            (apply-selection-to-frame new-frame selection)
                            new-frame)

            ;; Cell-level diffing: parse display lines into a grid, diff against previous
            new-grid (grid/frame->grid display-frame width height)
            old-grid (:previous-grid @tui-state)
            full-repaint? (nil? old-grid)]

        ;; Emit changes
        (term/sync-start!)
        (when full-repaint?
          (term/clear-screen!))
        (grid/emit-diff! new-grid (or old-grid (grid/make-grid width height)))
        (term/sync-end!)

        (swap! tui-state assoc
               :previous-frame new-frame  ;; keep raw lines for selection text extraction
               :previous-grid new-grid
               :scroll-offset effective-offset
               :prev-content-height total-content
               :render-requested false)))))

(defn- schedule-render!
  "Schedule a debounced render pass."
  []
  (when-not (:render-requested @tui-state)
    (swap! tui-state assoc :render-requested true)
    (when-let [t (:render-timer @tui-state)]
      (js/clearTimeout t))
    (let [timer (js/setTimeout do-render! MIN_RENDER_INTERVAL_MS)]
      (swap! tui-state assoc :render-timer timer))))

(defn request-render!
  "Request a render pass, marking content as dirty.
   Use for content changes (chat messages, tool output, etc.)."
  []
  (swap! tui-state assoc :content-dirty true)
  (schedule-render!))

(defn request-panel-render!
  "Request a render pass without marking content dirty.
   Use for bottom-panel-only changes (editor typing, completion menu)."
  []
  (schedule-render!))

(defn render-now!
  "Force an immediate render (bypass debounce)."
  []
  (when-let [t (:render-timer @tui-state)]
    (js/clearTimeout t))
  (swap! tui-state assoc :render-timer nil :content-dirty true)
  (do-render!))

;; ── Focus ─────────────────────────────────────────────────────────────────────

(defn set-focus!
  "Set the focused component (receives keyboard input)."
  [component]
  (swap! tui-state assoc :focused component))

;; ── Bottom Panel ──────────────────────────────────────────────────────────────

(defn scroll-to-offset!
  "Set the scroll offset directly. Used by modal components for jumping."
  [offset]
  (swap! tui-state assoc :scroll-offset (max 0 offset))
  (request-panel-render!))

(defn get-scroll-offset
  "Get the current scroll offset."
  []
  (:scroll-offset @tui-state))

(defn set-bottom-panel!
  "Set the component pinned at the bottom of the screen (editor, menu, etc.)."
  [component]
  (swap! tui-state assoc :bottom-panel component)
  (request-panel-render!))

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

(defn- handle-mouse-event
  "Handle mouse events: scroll wheel + text selection."
  [{:keys [button col row pressed]}]
  (let [;; Convert 1-based terminal coords to 0-based screen coords
        screen-row (dec row)
        screen-col (dec col)]
    (cond
      ;; Scroll wheel up
      (= 64 button)
      (scroll-up! 3)

      ;; Scroll wheel down
      (= 65 button)
      (scroll-down! 3)

      ;; Left button press — start selection
      (and (= 0 button) pressed)
      (do (swap! tui-state assoc :selection
                 {:start-row screen-row :start-col screen-col
                  :end-row screen-row :end-col screen-col
                  :selecting true})
          (request-panel-render!))

      ;; Left button drag (motion with button 0 = button code 32)
      (and (= 32 button) pressed)
      (when (:selection @tui-state)
        (swap! tui-state update :selection assoc
               :end-row screen-row :end-col screen-col)
        (request-panel-render!))

      ;; Left button release — finish selection
      (and (= 0 button) (not pressed))
      (when-let [sel (:selection @tui-state)]
        (let [norm (normalize-selection sel)]
          (if (and (= (:start-row norm) (:end-row norm))
                   (= (:start-col norm) (:end-col norm)))
            ;; Click without drag — clear selection
            (clear-selection!)
            ;; Real selection — copy to clipboard
            (let [frame (:previous-frame @tui-state)
                  ;; Use a clean frame without highlights for text extraction
                  text (extract-selection-text frame (assoc sel :selecting false))]
              (copy-to-clipboard! text)
              ;; Keep highlight visible briefly, then clear
              (swap! tui-state update :selection assoc :selecting false)
              (request-panel-render!)
              (js/setTimeout clear-selection! 200)))))

      :else nil)))

(defn- handle-input [data]
  (let [{:keys [focused]} @tui-state]
    (if (:capture-all-input focused)
      ;; Modal component (diff viewer, etc.) captures all input.
      ;; No auto-snap to bottom, no selection clear. Scroll wheel → :handle-scroll.
      (if-let [mouse (parse-mouse-event data)]
        (cond
          ;; Scroll wheel up → component scroll handler
          (= 64 (:button mouse))
          (when-let [hs (:handle-scroll focused)] (hs -3))
          ;; Scroll wheel down → component scroll handler
          (= 65 (:button mouse))
          (when-let [hs (:handle-scroll focused)] (hs 3))
          ;; Other mouse events — normal handling (text selection)
          :else
          (handle-mouse-event mouse))
        ;; Keyboard → directly to component
        (when (:handle-input focused)
          ((:handle-input focused) data)))
      ;; Normal mode — existing behavior
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
          (handle-mouse-event mouse)
          ;; Regular input — clear selection, snap to bottom, pass to focused component
          (do
            (clear-selection!)
            (when (pos? (:scroll-offset @tui-state))
              (scroll-to-bottom!))
            (when (and focused (:handle-input focused))
              ((:handle-input focused) data))))))))

(defn- handle-resize []
  ;; Invalidate all components
  (when-let [content (:content @tui-state)]
    ((:invalidate content)))
  (when-let [bp (:bottom-panel @tui-state)]
    (when-let [inv (:invalidate bp)]
      (inv)))
  ;; Clear selection
  (swap! tui-state assoc :selection nil)
  ;; Force full re-render (clear previous frame/grid)
  (swap! tui-state assoc :previous-frame [] :previous-grid nil)
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
           :previous-grid nil
           :render-requested false
           :render-timer nil
           :stopped false
           :content-dirty true
           :cached-content-lines nil
           :cached-content-width nil
           :selection nil)
    (term/start! terminal handle-input handle-resize)
    content))

(defn run-external!
  "Suspend the TUI, run an external command with inherited stdio, resume on exit.
   Returns a promise. cmd is a vector of strings.
   opts: {:cwd string, :on-suspend fn, :on-resume fn}"
  [cmd opts]
  (let [{:keys [terminal]} @tui-state
        cmd-arr (clj->js cmd)]
    (when terminal
      (swap! tui-state assoc :suspended true)
      (when-let [f (:on-suspend opts)] (f))
      (term/suspend! terminal)
      (let [proc (js/Bun.spawn
                  cmd-arr
                  #js {:stdin "inherit"
                       :stdout "inherit"
                       :stderr "inherit"
                       :cwd (or (:cwd opts) (.cwd js/process))})]
        (-> (.-exited proc)
            (.then (fn [_code]
                     (term/resume! terminal)
                     (when-let [f (:on-resume opts)] (f))
                     ;; Force full re-render
                     (swap! tui-state assoc :suspended false :previous-frame [] :previous-grid nil)
                     (render-now!))))))))

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
