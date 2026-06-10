(ns xi.web.core
  "Web client entry — rebuilt in phase 7 on the new core
   (shared reducer/effect model, web renderer + input layer).")

(defn- render! []
  (when-let [el (.getElementById js/document "app")]
    (set! (.-textContent el) "xi web — rebuild in progress")))

(defn init! []
  (render!))

(defn reload! []
  (render!))
