(ns xisite.theme
  "The site's design tokens and component styles come from clj-ui-framework.
   The theme is generated from the framework's tokens with the hues nudged
   to the site's slate and blue, so the colours stay close to what the site
   had before. Light, dark and the system setting are the framework's own:
   `[data-theme=light|dark]` on <html>, or `prefers-color-scheme` when unset."
  (:require [ui.css.gen :as gen]))

(def theme-opts
  "Deep-merged over the framework's default tokens (purple on grey)."
  {:scales {:color {:gray   {:hue 255 :chroma-scale 0.55}
                    :accent {:hue 263 :chroma-scale 0.9}}}
   ;; light theme: one step deeper than the framework's accent-500, which is
   ;; what the site's blue always was. Dark keeps the framework's accent-400.
   :tokens {:accent "var(--accent-600)"}})

(defn css
  "Framework tokens + every component's CSS, as one string."
  []
  (gen/build-css theme-opts))

(defn js
  "The framework's pre-built browser runtime (theme switching lives in it)."
  []
  (gen/build-js))

(def ^:private theme-storage-key
  "localStorage key the runtime's `__uiTheme` persists the choice under."
  "ui-theme")

(def head-script
  "Runs before first paint so a stored light/dark choice doesn't flash the
   other theme. Auto (nothing stored) leaves `data-theme` off, and the CSS
   media query follows the system."
  (str "(function(){try{var m=localStorage.getItem('" theme-storage-key "');"
       "if(m==='light'||m==='dark')document.documentElement.setAttribute('data-theme',m);}catch(e){}})();"))
