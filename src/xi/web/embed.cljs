(ns xi.web.embed
  "Theming a tour (xi.web.tour) from the page that embeds it: the site's
   theming section (site/public/js/theme-demo.js) posts
     {xiTheme: {params: {…} | null, mode: \"light\" | \"dark\" | null}}
   and the client shows those xi.web.theme params and that mode instead of
   its own, saving nothing. Once the parent has themed it, the override wins
   over the client's own theme effects (a replayed user-state frame, the boot
   mode) — xi.web.core's :theme/apply-vars and :theme/apply go through here."
  (:require [xi.web.theme :as theme]))

(defonce ^:private override (atom nil)) ; {:vars {…} | nil, :mode str | nil}

(defn mode
  "The parent's light/dark mode while it overrides one."
  []
  (:mode @override))

(defn set-vars!
  "Set / remove every theme-managed property on <html>: `vars`, or the
   parent's theme once it set one."
  [vars]
  (let [o     @override
        vars  (if o (:vars o) vars)
        style (.-style js/document.documentElement)]
    (doseq [n theme/var-names]
      (if-let [v (get vars n)]
        (.setProperty style n v)
        (.removeProperty style n)))))

(defn- on-message [^js e]
  (when-let [^js msg (and (identical? (.-source e) js/parent)
                          (let [^js data (.-data e)] (some-> data .-xiTheme)))]
    (let [params (some-> (.-params msg) (js->clj :keywordize-keys true) theme/normalize-params not-empty)
          mode   (#{"light" "dark"} (.-mode msg))]
      (reset! override {:vars (theme/css-vars params) :mode mode})
      (set-vars! nil)
      (when mode
        (.setAttribute js/document.documentElement "data-theme" mode)))))

(defn listen!
  "Accept theme messages from the embedding page (tour mode only)."
  []
  (.addEventListener js/window "message" on-message))
