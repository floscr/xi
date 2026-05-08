(ns xi.tui.core
  "TUI engine — retained mode component system with differential rendering.
   Follows Pi's approach: components render to string arrays (lines),
   TUI compares with previous frame and only redraws what changed.
   Content flows into the scrollback buffer naturally."
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

;; ── TUI ───────────────────────────────────────────────────────────────────────

(defonce ^:private tui-state
  (atom {:terminal nil
         :container nil
         :focused nil
         :previous-lines []
         :previous-width 0
         :cursor-row 0          ;; how many lines above current cursor position
         :render-requested false
         :render-timer nil
         :stopped false}))

(def ^:private MIN_RENDER_INTERVAL_MS 16)

(defn- lines-equal?
  "Compare two lines, handling nil."
  [a b]
  (= a b))

(defn- do-render!
  "Execute the actual render pass — differential update."
  []
  (let [{:keys [container previous-lines previous-width cursor-row stopped]} @tui-state]
    (when (and container (not stopped))
      (let [width (term/columns)
            new-lines ((:render container) width)
            width-changed (not= width previous-width)
            ;; Find first difference
            first-diff (if width-changed
                         0
                         (loop [i 0]
                           (cond
                             (and (>= i (count new-lines))
                                  (>= i (count previous-lines)))
                             nil ;; identical

                             (or (>= i (count previous-lines))
                                 (>= i (count new-lines))
                                 (not (lines-equal? (nth new-lines i nil)
                                                    (nth previous-lines i nil))))
                             i

                             :else (recur (inc i)))))]

        (when (some? first-diff)
          (term/sync-start!)

          ;; Move cursor to the first changed line
          ;; cursor-row tracks how many lines from the current cursor position
          ;; to the end of previously rendered content
          (let [prev-total (count previous-lines)
                lines-to-move-up (- prev-total first-diff)
                ;; We need to move from current position to first-diff line
                ;; Current position is at prev-total (end of previous render)
                move-up (max 0 (+ cursor-row (- prev-total first-diff)))]

            ;; Move cursor up to the first changed line
            (when (pos? move-up)
              (term/move-by! (- move-up)))

            ;; Move to start of line
            (term/write! "\r")

            ;; Render from first-diff to end
            (doseq [i (range first-diff (count new-lines))]
              (let [line (nth new-lines i)]
                (term/write! ansi/CLEAR_LINE)
                (term/write! line)
                (term/write! "\n")))

            ;; If new content is shorter than previous, clear remaining lines
            (when (> (count previous-lines) (count new-lines))
              (term/clear-from-cursor!))

            ;; Update state
            (swap! tui-state assoc
                   :previous-lines new-lines
                   :previous-width width
                   :cursor-row 0  ;; cursor is now at end of new content
                   :render-requested false))

          (term/sync-end!))

        ;; Even if nothing changed, clear the request flag
        (swap! tui-state assoc :render-requested false)))))

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

(defn set-focus!
  "Set the focused component (receives keyboard input)."
  [component]
  (swap! tui-state assoc :focused component))

(defn- handle-input [data]
  (let [{:keys [focused]} @tui-state]
    (when (and focused (:handle-input focused))
      ((:handle-input focused) data))))

(defn- handle-resize []
  ;; Invalidate all components and re-render
  (when-let [container (:container @tui-state)]
    ((:invalidate container)))
  ;; Width changed — force full re-render
  (swap! tui-state assoc :previous-width 0)
  (request-render!))

;; ── Lifecycle ─────────────────────────────────────────────────────────────────

(defn create-tui!
  "Create and start the TUI. Returns the root container."
  []
  (let [terminal (term/create-terminal)
        container (make-container)]
    (swap! tui-state assoc
           :terminal terminal
           :container container
           :focused nil
           :previous-lines []
           :previous-width 0
           :cursor-row 0
           :render-requested false
           :render-timer nil
           :stopped false)
    (term/start! terminal handle-input handle-resize)
    container))

(defn stop-tui!
  "Stop the TUI, restore terminal."
  []
  (when-let [t (:render-timer @tui-state)]
    (js/clearTimeout t))
  (swap! tui-state assoc :stopped true)
  (when-let [terminal (:terminal @tui-state)]
    (term/stop! terminal)))

(defn get-container
  "Get the root container."
  []
  (:container @tui-state))
