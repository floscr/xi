(ns xi.web.core
  "Web client entry point — render loop, viewport handling, WS init."
  (:require [xi.web.state :as state]
            [xi.web.views :as views]
            [xi.web.ws :as ws]
            [xi.web.router :as router]
            [replicant.dom :as r]))

;; ---------------------------------------------------------------------------
;; Auto-scroll
;; ---------------------------------------------------------------------------

(defonce ^:private auto-scroll? (atom true))
(defonce ^:private tracked-timeline (atom nil))

(defn- at-bottom?
  "Check if the timeline is scrolled to (or near) the bottom."
  [^js el]
  (<= (- (.-scrollHeight el) (.-scrollTop el) (.-clientHeight el)) 40))

(defn- attach-scroll-listener!
  "Attach a scroll listener to .timeline to track whether the user scrolled away.
   Re-attaches when the element changes (e.g. home → chat transition)."
  []
  (when-let [timeline (.querySelector js/document ".timeline")]
    (when-not (identical? timeline @tracked-timeline)
      (reset! tracked-timeline timeline)
      (reset! auto-scroll? true)
      (.addEventListener timeline "scroll"
        (fn [] (reset! auto-scroll? (at-bottom? timeline)))))))

(defn- scroll-to-bottom!
  "Scroll .timeline to the bottom if auto-scroll is active."
  []
  (when @auto-scroll?
    (when-let [timeline (.querySelector js/document ".timeline")]
      (set! (.-scrollTop timeline) (.-scrollHeight timeline)))))

;; ---------------------------------------------------------------------------
;; Render
;; ---------------------------------------------------------------------------

(defn- el [id] (js/document.getElementById id))

(defn- focus-compose-input!
  "Focus the compose input when entering a chat view."
  []
  (when-let [input (.querySelector js/document ".compose-input-wrapper textarea")]
    (when-not (= input (.-activeElement js/document))
      (.focus input))))

(defn- render! [app-state]
  (r/render (el "app") (views/root-view app-state))
  ;; Attach scroll listener if timeline appeared (home → chat transition)
  (attach-scroll-listener!)
  ;; Scroll to bottom after DOM update
  (js/requestAnimationFrame
   (fn []
     (scroll-to-bottom!)
     (when (= :chat (get-in app-state [:route :page]))
       (focus-compose-input!)))))

;; ---------------------------------------------------------------------------
;; iOS keyboard handling
;; ---------------------------------------------------------------------------

(defn- setup-viewport! []
  (let [update-height!
        (fn []
          (let [h (if js/window.visualViewport
                    (.-height js/window.visualViewport)
                    (.-innerHeight js/window))]
            (.setProperty (.-style (.-documentElement js/document))
                          "--app-height" (str h "px"))
            (js/window.scrollTo 0 0)))]
    (update-height!)
    (when js/window.visualViewport
      (.addEventListener js/window.visualViewport "resize" update-height!)
      (.addEventListener js/window.visualViewport "scroll" update-height!))
    (.addEventListener js/window "orientationchange"
                       (fn [] (js/setTimeout update-height! 200)))))

;; ---------------------------------------------------------------------------
;; Init
;; ---------------------------------------------------------------------------

(defn- setup-visibility-listener!
  "Reconnect WS when returning from background (iOS locks, tab switches).
   Mobile browsers kill WS connections aggressively when backgrounded."
  []
  (.addEventListener js/document "visibilitychange"
    (fn []
      (when (= "visible" (.-visibilityState js/document))
        (when-not (:connected? @state/app-state)
          (js/console.log "[ws] page visible, forcing reconnect")
          (ws/connect!))))))

(defn ^:export init! []
  (js/console.log "[xi-web] starting")

  (r/set-dispatch! (fn [_ _]))

  ;; Init router — sets initial route from URL
  (router/init!)

  ;; Hydrate from localStorage cache first — shows content immediately
  (ws/hydrate-from-cache!)

  (add-watch state/app-state ::render
             (fn [_ _ _ new-state]
               (render! new-state)))

  (render! @state/app-state)
  (setup-viewport!)
  (setup-visibility-listener!)

  ;; Then connect WS — will refresh/override with live data
  (ws/connect!))

(defn ^:export reload! []
  (js/console.log "[xi-web] reloaded")
  (render! @state/app-state))
