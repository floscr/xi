(ns xisite.build
  "Static build: render every route into a staging directory."
  (:require [babashka.fs :as fs]
            [xisite.docs :as docs]
            [xisite.pages :as pages]
            [xisite.theme :as theme]))

(defn routes
  "Map of path (trailing slash, root as \"/\") → html string."
  []
  (let [sections (docs/sections)
        pages (docs/pages sections)]
    (merge
     {"/" (pages/home)
      "/tour/" (pages/tour-page)
      "/changelog/" (pages/changelog)
      "/docs/" (pages/docs-index sections)}
     (into {} (for [page pages]
                [(:path page) (pages/docs-page sections page)])))))

;; The stage iframe's web client: the release :tour build (`bb tour:build` at
;; the repo root) and the web client's own stylesheet, served at /tour/.
(def tour-files
  {"/tour/main.js"   "../target/tour/main.js"
   "/tour/style.css" "../resources/public/css/style.css"})

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
        (fs/copy src (str staging uri) {:replace-existing true})
        (println (str "warning: " src " is missing (run `bb tour:build`); the home page tour won't load"))))
    (when (fs/exists? (str docs/guide-dir "/img"))
      (fs/copy-tree (str docs/guide-dir "/img") (str staging "/docs/img") {:replace-existing true}))
    (println (str "Built " (count pages) " pages into " staging "/"))))
