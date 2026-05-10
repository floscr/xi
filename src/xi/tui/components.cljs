(ns xi.tui.components
  "TUI components — Text, Spacer, Box, Loader."
  (:require [clojure.string :as str]
            [xi.tui.ansi :as ansi]
            [xi.tui.core :as tui]))

;; ── Text ──────────────────────────────────────────────────────────────────────

(defn make-text
  "Create a text component with optional padding and background.
   opts: {:padding-x 1 :padding-y 0 :bg-code nil}"
  ([text] (make-text text {}))
  ([text opts]
   (let [state (atom {:text text
                      :cached-text nil
                      :cached-width nil
                      :cached-lines nil})
         padding-x (or (:padding-x opts) 0)
         padding-y (or (:padding-y opts) 0)
         bg-code (:bg-code opts)]
     {:type :text
      :set-text (fn [t]
                  (swap! state assoc :text t :cached-text nil :cached-width nil :cached-lines nil)
                  (tui/request-render!))
      :get-text (fn [] (:text @state))
      :invalidate (fn [] (swap! state assoc :cached-text nil :cached-width nil :cached-lines nil))
      :render (fn [width]
                (let [{:keys [text cached-text cached-width cached-lines]} @state]
                  (if (and cached-lines (= cached-text text) (= cached-width width))
                    cached-lines
                    (let [content-width (max 1 (- width (* 2 padding-x)))
                          left-pad (apply str (repeat padding-x " "))
                          wrapped (if (or (nil? text) (= "" (str/trim (str text))))
                                    []
                                    (ansi/wrap-text text content-width))
                          content-lines (mapv (fn [line]
                                               (let [padded (str left-pad line)]
                                                 (if bg-code
                                                   (ansi/apply-bg-to-line padded width bg-code)
                                                   (ansi/pad-to-width padded width))))
                                             wrapped)
                          empty-line (if bg-code
                                      (ansi/apply-bg-to-line "" width bg-code)
                                      (apply str (repeat width " ")))
                          pad-lines (vec (repeat padding-y empty-line))
                          result (into [] (concat pad-lines content-lines pad-lines))]
                      (swap! state assoc :cached-text text :cached-width width :cached-lines result)
                      result))))})))

;; ── Spacer ────────────────────────────────────────────────────────────────────

(defn make-spacer
  "Create a spacer component that renders n empty lines."
  ([] (make-spacer 1))
  ([n]
   (let [lines (atom n)]
     {:type :spacer
      :set-lines (fn [new-n] (reset! lines new-n) (tui/request-render!))
      :invalidate (fn [])
      :render (fn [_width] (vec (repeat @lines "")))})))

;; ── Box ───────────────────────────────────────────────────────────────────────

(defn make-box
  "Create a box that wraps children with padding and optional background."
  ([] (make-box {}))
  ([opts]
   (let [children (atom [])
         padding-x (or (:padding-x opts) 1)
         padding-y (or (:padding-y opts) 1)
         bg-code (:bg-code opts)]
     {:type :box
      :children children
      :add-child (fn [c] (swap! children conj c) (tui/request-render!))
      :remove-child (fn [c] (swap! children (fn [cs] (vec (remove #(identical? % c) cs)))) (tui/request-render!))
      :clear (fn [] (reset! children []) (tui/request-render!))
      :invalidate (fn []
                    (doseq [c @children]
                      (when-let [inv (:invalidate c)]
                        (inv))))
      :render (fn [width]
                (if (empty? @children)
                  []
                  (let [content-width (max 1 (- width (* 2 padding-x)))
                        left-pad (apply str (repeat padding-x " "))
                        child-lines (into []
                                         (mapcat (fn [c] (mapv #(str left-pad %) ((:render c) content-width))))
                                         @children)
                        apply-line (fn [line]
                                     (if bg-code
                                       (ansi/apply-bg-to-line line width bg-code)
                                       (ansi/pad-to-width line width)))
                        empty-line (apply-line "")
                        pad-lines (vec (repeat padding-y empty-line))
                        content (mapv apply-line child-lines)]
                    (into [] (concat pad-lines content pad-lines)))))})))

;; ── Spinner ───────────────────────────────────────────────────────────────────

(def ^:private SPINNER_FRAMES ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"])

(defn make-spinner
  "Create a minimal inline spinner component."
  []
  (let [state (atom {:frame 0 :timer nil :cached-width nil :cached-lines nil})]
    {:type :spinner
     :start (fn []
              (when-let [t (:timer @state)] (js/clearInterval t))
              (let [timer (js/setInterval
                           (fn []
                             (swap! state (fn [s]
                                           (-> s
                                               (update :frame #(mod (inc %) (count SPINNER_FRAMES)))
                                               (assoc :cached-width nil :cached-lines nil))))
                             (tui/request-render!))
                           80)]
                (swap! state assoc :timer timer :cached-width nil :cached-lines nil)
                (tui/request-render!)))
     :stop (fn []
             (when-let [t (:timer @state)]
               (js/clearInterval t)
               (swap! state assoc :timer nil)))
     :invalidate (fn [] (swap! state assoc :cached-width nil :cached-lines nil))
     :render (fn [_width]
               (let [{:keys [frame cached-width cached-lines]} @state]
                 (if (and cached-lines (= cached-width _width))
                   cached-lines
                   (let [spinner (nth SPINNER_FRAMES frame)
                         result [(ansi/fg :dim spinner)]]
                     (swap! state assoc :cached-width _width :cached-lines result)
                     result))))}))

;; ── Loader ────────────────────────────────────────────────────────────────────

(defn make-loader
  "Create an animated loader/spinner component."
  [message]
  (let [state (atom {:message message
                     :frame 0
                     :timer nil
                     :thinking-text nil
                     :cached-width nil
                     :cached-lines nil})]
    {:type :loader
     :set-message (fn [msg]
                    (swap! state assoc :message msg :cached-width nil :cached-lines nil)
                    (tui/request-render!))
     :set-thinking (fn [text]
                     (swap! state assoc :thinking-text text :cached-width nil :cached-lines nil)
                     (tui/request-render!))
     :clear-thinking (fn []
                       (swap! state assoc :thinking-text nil :cached-width nil :cached-lines nil))
     :start (fn []
              (when-let [t (:timer @state)]
                (js/clearInterval t))
              (let [timer (js/setInterval
                           (fn []
                             (swap! state (fn [s]
                                           (-> s
                                               (update :frame #(mod (inc %) (count SPINNER_FRAMES)))
                                               (assoc :cached-width nil :cached-lines nil))))
                             (tui/request-render!))
                           80)]
                (swap! state assoc :timer timer :cached-width nil :cached-lines nil)
                (tui/request-render!)))
     :stop (fn []
             (when-let [t (:timer @state)]
               (js/clearInterval t)
               (swap! state assoc :timer nil)))
     :invalidate (fn [] (swap! state assoc :cached-width nil :cached-lines nil))
     :render (fn [width]
               (let [{:keys [message frame thinking-text cached-width cached-lines]} @state]
                 (if (and cached-lines (= cached-width width))
                   cached-lines
                   (let [spinner (nth SPINNER_FRAMES frame)
                         header (str "" (ansi/fg :accent spinner) " " (ansi/fg :dim message))
                         result (if (and thinking-text (seq thinking-text))
                                  (let [max-lines 6
                                        lines (str/split-lines thinking-text)
                                        total (count lines)
                                        visible (if (> total max-lines)
                                                  (subvec (vec lines) (- total max-lines))
                                                  lines)
                                        content-width (max 1 (- width 4))
                                        thinking-lines (mapv (fn [line]
                                                               (let [trimmed (if (> (count line) content-width)
                                                                              (str (subs line 0 content-width) "…")
                                                                              line)]
                                                                 (str "  " (ansi/fg :dim trimmed))))
                                                             visible)]
                                    (into ["" header] thinking-lines))
                                  ["" header])]
                     (swap! state assoc :cached-width width :cached-lines result)
                     result))))}))
