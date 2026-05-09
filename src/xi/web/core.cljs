(ns xi.web.core
  "Web client entry point — render loop, viewport handling, WS init."
  (:require [xi.web.state :as state]
            [xi.web.views :as views]
            [xi.web.ws :as ws]
            [replicant.dom :as r]))

;; ---------------------------------------------------------------------------
;; Render
;; ---------------------------------------------------------------------------

(defn- el [id] (js/document.getElementById id))

(defn- render! [app-state]
  (r/render (el "app") (views/root-view app-state)))

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

(defn ^:export init! []
  (js/console.log "[xi-web] starting")

  (r/set-dispatch! (fn [_ _]))

  (add-watch state/app-state ::render
             (fn [_ _ _ new-state]
               (render! new-state)))

  (render! @state/app-state)
  (setup-viewport!)
  (ws/connect!))

(defn ^:export reload! []
  (js/console.log "[xi-web] reloaded")
  (render! @state/app-state))
