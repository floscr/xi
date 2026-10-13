(ns xisite.theme
  "The site's design tokens and component styles come from clj-ui-framework,
   with its default tokens: the same theme the web client ships
   (resources/public/theme.css), so the site matches its live previews.
   Light, dark and the system setting are the framework's own:
   `[data-theme=light|dark]` on <html>, or `prefers-color-scheme` when unset.

   The site and its previews (/tour/, xi.web.embed) share the stored theme:
   the light/dark choice and a custom theme's CSS properties, set from the
   home page's theming section."
  (:require [ui.css.gen :as gen]))

(defn css
  "Framework tokens + every component's CSS, as one string."
  []
  (gen/build-css {}))

(defn js
  "The framework's pre-built browser runtime (theme switching lives in it)."
  []
  (gen/build-js))

(def ^:private theme-storage-key
  "localStorage key the runtime's `__uiTheme` persists the choice under."
  "ui-theme")

(def ^:private theme-vars-key
  "localStorage key of a custom theme's CSS properties (xi.web.cache)."
  "xi/theme-vars")

(def head-script
  "Runs before first paint so a stored light/dark choice or custom theme
   doesn't flash the default. Auto (nothing stored) leaves `data-theme` off,
   and the CSS media query follows the system."
  (str "(function(){try{var d=document.documentElement,m=localStorage.getItem('" theme-storage-key "');"
       "if(m==='light'||m==='dark')d.setAttribute('data-theme',m);"
       "var v=JSON.parse(localStorage.getItem('" theme-vars-key "')||'null');"
       "if(v)for(var k in v)d.style.setProperty(k,v[k]);}catch(e){}})();"))
