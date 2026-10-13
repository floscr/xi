(ns xi.web.embed
  "A tour (xi.web.tour) wears the theme of the site that embeds it. The site
   and its previews share one origin, so one stored theme: \"ui-theme\"
   (light/dark/auto, the framework runtime's key) and the custom theme's CSS
   properties (xi.web.cache theme-vars). A change in any document reaches the
   others through the `storage` event; site/public/js/site.js follows it on
   the page.

   The client's own light/dark switch writes \"ui-theme\" as usual, so it
   switches the site and the other previews too. Its custom-theme effects
   are not applied in a tour (xi.web.core :theme/apply-vars): the site's
   theming section (site/public/js/theme-demo.js) owns that one, posting
   {xiTheme: {params: {…} | null}}, which this client turns into the
   properties (xi.web.theme) and stores for everyone."
  (:require [xi.web.cache :as cache]
            [xi.web.theme :as theme]))

(defonce ^:private active (atom false))

(defn active?
  "Following the embedding site's theme (tour mode)."
  []
  @active)

(defn set-vars!
  "Set / remove every theme-managed property on <html>."
  [vars]
  (let [style (.-style js/document.documentElement)]
    (doseq [n theme/var-names]
      (if-let [v (get vars n)]
        (.setProperty style n v)
        (.removeProperty style n)))))

(defn- on-message [^js e]
  (when-let [^js msg (and (identical? (.-source e) js/parent)
                          (let [^js data (.-data e)] (some-> data .-xiTheme)))]
    (let [params (some-> (.-params msg) (js->clj :keywordize-keys true) theme/normalize-params not-empty)
          vars   (theme/css-vars params)]
      (cache/save-theme-vars! vars)
      (set-vars! vars))))

(defn- on-storage [dispatch! ^js e]
  (condp = (.-key e)
    "ui-theme"           (dispatch! {:type :theme/set-mode :mode (or (.-newValue e) "auto") :init? true})
    cache/theme-vars-key (set-vars! (cache/load-theme-vars))
    nil))

(defn listen!
  "Follow the site's theme from now on. `dispatch!` may not deliver yet: the
   client boots after this, its cached-theme effects already skipped."
  [dispatch!]
  (reset! active true)
  (.addEventListener js/window "message" on-message)
  (.addEventListener js/window "storage" #(on-storage dispatch! %)))
