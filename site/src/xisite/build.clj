(ns xisite.build
  "Static build: render every route into a staging directory."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [xisite.docs :as docs]
            [xisite.pages :as pages]
            [xisite.theme :as theme]))

(def ^:private plain-routes
  "Pages that don't need the guide parsed."
  {"/" pages/home
   "/tour/" pages/tour-page
   "/changelog/" pages/changelog})

(defn routes
  "Map of path (trailing slash, root as \"/\") → html string."
  []
  (let [sections (docs/sections)
        pages (docs/pages sections)]
    (merge
     (update-vals plain-routes #(%))
     {"/docs/" (pages/docs-index sections)}
     (into {} (for [page pages]
                [(:path page) (pages/docs-page sections page)])))))

(defn render
  "The html for `path` (with or without its trailing slash), or nil. For the
   dev server: the home page and each tour iframe skip parsing the guide
   (~0.8 s), which every docs page needs."
  [path]
  (let [paths [path (str path "/")]]
    (if-let [page (some plain-routes paths)]
      (page)
      (some (routes) paths))))

;; The stage iframe's web client: the release :tour build (`bb tour:build` at
;; the repo root) and the web client's own stylesheet, framework theme and
;; runtime, served at /tour/ (the default theme, not the site's /css/ui.css),
;; plus xterm.js for the terminal tours, at the absolute path the client
;; loads it from (xi.web.terminal).
(def tour-files
  {"/tour/main.js"       "../target/tour/main.js"
   "/tour/style.css"     "../resources/public/css/style.css"
   "/tour/theme.css"     "../resources/public/theme.css"
   "/tour/ui-runtime.js" "../resources/public/ui-runtime.js"
   "/vendor/xterm/xterm.js"          "../resources/public/vendor/xterm/xterm.js"
   "/vendor/xterm/xterm.css"         "../resources/public/vendor/xterm/xterm.css"
   "/vendor/xterm/addon-fit.js"      "../resources/public/vendor/xterm/addon-fit.js"
   "/vendor/xterm/LICENSE"           "../resources/public/vendor/xterm/LICENSE"
   "/vendor/xterm/LICENSE-addon-fit" "../resources/public/vendor/xterm/LICENSE-addon-fit"})

(defn- content-hash [file]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                        (fs/read-all-bytes file))]
    (subs (apply str (map #(format "%02x" %) digest)) 0 10)))

(defn- stamp-assets!
  "Cache keys: point each page's local .css/.js at `?v=<content hash>`, so a
   changed file is a new URL. public/.htaccess caches those for good and makes
   everything else (pages, tour tapes) revalidate."
  [staging]
  (doseq [page (fs/glob staging "**.html")]
    (let [html    (slurp (str page))
          stamped (str/replace html #"(src|href)=\"(/[^\"?#]+\.(?:css|js))\""
                               (fn [[match attr uri]]
                                 (let [file (str staging uri)]
                                   (if (fs/exists? file)
                                     (str attr "=\"" uri "?v=" (content-hash file) "\"")
                                     match))))]
      (when-not (= html stamped)
        (spit (str page) stamped)))))

(defn- write-page! [staging path html]
  (let [dir (if (= path "/") staging (str staging path))]
    (fs/create-dirs dir)
    (spit (str dir "/index.html") html)))

(defn build! [staging]
  (fs/create-dirs staging)
  (let [pages (routes)]
    (doseq [[path html] pages]
      (write-page! staging path html))
    (fs/copy-tree "public" staging {:replace-existing true})
    ;; clj-ui-framework: generated theme + component CSS, and its browser runtime
    (fs/create-dirs (str staging "/css"))
    (fs/create-dirs (str staging "/js"))
    (spit (str staging "/css/ui.css") (theme/css))
    (spit (str staging "/js/ui-runtime.js") (theme/js))
    (doseq [[uri src] tour-files]
      (if (fs/exists? src)
        (let [dest (str staging uri)]
          (fs/create-dirs (fs/parent dest))
          (fs/copy src dest {:replace-existing true}))
        (println (str "warning: " src " is missing (run `bb tour:build`); the home page tour won't load"))))
    (when (fs/exists? (str docs/guide-dir "/img"))
      (fs/copy-tree (str docs/guide-dir "/img") (str staging "/docs/img") {:replace-existing true}))
    (stamp-assets! staging)
    (println (str "Built " (count pages) " pages into " staging "/"))))
