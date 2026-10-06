(ns xisite.build
  "Static build: render every route into a staging directory."
  (:require [babashka.fs :as fs]
            [xisite.docs :as docs]
            [xisite.pages :as pages]))

(defn routes
  "Map of path (trailing slash, root as \"/\") → html string."
  []
  (let [sections (docs/sections)
        pages (docs/pages sections)]
    (merge
     {"/" (pages/home)
      "/docs/" (pages/docs-index sections)}
     (into {} (for [page pages]
                [(:path page) (pages/docs-page sections page)])))))

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
    (when (fs/exists? (str docs/guide-dir "/img"))
      (fs/copy-tree (str docs/guide-dir "/img") (str staging "/docs/img") {:replace-existing true}))
    (println (str "Built " (count pages) " pages into " staging "/"))))
