(ns xisite.extensions
  "/extensions/: the extensions and MCP servers of the xi-extensions repo, a
   card each. They come from a copy of that repo's catalog.edn
   (site/extensions.edn) and its screenshots (public/extensions/), refreshed
   by `bb extensions:sync`, so a site build needs neither the repo nor the
   network."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [ui.badge :as badge]
            [ui.button :as button]
            [xisite.markdown :as md]
            [xisite.ui :as ui]))

(def repo-url "https://github.com/floscr/xi-extensions")
(def raw-url "https://raw.githubusercontent.com/floscr/xi-extensions/main/")
(def catalog-file "extensions.edn")

(defn catalog [] (edn/read-string (slurp catalog-file)))

(defn sync!
  "Copy the catalog and its screenshots from a xi-extensions checkout at
   `local` (a sibling of the xi repo by default), or fetch them from GitHub."
  ([] (sync! "../../xi-extensions"))
  ([local]
   (let [from-disk? (fs/exists? (fs/path local "catalog.edn"))
         read (fn [path] (if from-disk?
                           (fs/read-all-bytes (fs/path local path))
                           (:body (http/get (str raw-url path) {:as :bytes}))))
         text (String. ^bytes (read "catalog.edn") "UTF-8")
         entries (edn/read-string text)]
     (fs/create-dirs "public/extensions")
     (doseq [{:keys [id image]} entries :when image]
       (fs/write-bytes (str "public/extensions/" id "." (fs/extension image)) (read image)))
     (spit catalog-file text)
     (println (str "Synced " (count entries) " extensions from " (if from-disk? local repo-url))))))

(def ^:private kinds {:mcp "MCP server" :extension "User extension"})

(defn- card [{:keys [id kind title summary features tools path image requires mcp-entry]}]
  [:article.extension {:id id}
   (when image
     [:a.extension-shot {:href (str "/extensions/" id "." (fs/extension image))}
      [:img {:src (str "/extensions/" id "." (fs/extension image)) :alt (str title ", a screenshot") :loading "lazy"}]])
   [:div.extension-body
    [:p.extension-kind (badge/badge {:variant :secondary :size :sm} (get kinds kind (name kind)))]
    [:h2 title]
    [:p.extension-summary summary]
    (when (seq features)
      [:ul.feature-list (for [f features] [:li f])])
    (when (seq tools)
      [:p.extension-tools "Tools: " (interpose " " (for [t tools] [:code t]))])
    (when mcp-entry
      (list
       [:h3 "Install"]
       [:p "Needs " (str/join ", " requires) ". Clone the repo, add the server to "
        [:code "~/.config/xi/mcp.edn"] ", then run " [:code (str "/mcp refresh " id)] "."]
       (md/code-block-hiccup "clojure" mcp-entry)))
    [:p.extension-links
     (button/button {:variant :secondary :size :sm :href (str repo-url "/tree/main/" path)} "Source and docs")]]])

(defn page []
  (ui/render-page
   (ui/layout
    {:title "Extensions · Xi"
     :description "Extensions and MCP servers for Xi, ready to install."
     :path "/extensions/"
     :body-class "extensions-page"}
    [:div.extensions-shell
     [:header.extensions-intro
      [:p.eyebrow "Extensions"]
      [:h1 "Extensions and MCP servers"]
      [:p "Things built on Xi's extension API and on MCP, published in "
       [:a {:href repo-url :rel "noopener"} "xi-extensions"]
       ". Each one installs on its own. Writing your own? Start with "
       [:a {:href "/docs/extensions/"} "the extensions guide"] "."]]
     (for [e (catalog)] (card e))])))
