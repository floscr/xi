(ns xisite.blog
  "/blog/: posts as markdown in site/blog/, named YYYY-MM-DD-slug.md. A post
   starts with `# Title` and a summary paragraph (xisite.markdown/split-title);
   its images go in public/blog/<slug>/."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [xisite.markdown :as md]
            [xisite.ui :as ui]))

(def posts-dir "blog")

(defn- display-date [date]
  (let [[y m d] (str/split date #"-")]
    (str (parse-long d) " "
         (["January" "February" "March" "April" "May" "June" "July" "August"
           "September" "October" "November" "December"] (dec (parse-long m)))
         " " y)))

(defn posts
  "Newest first: {:slug :date :path :title :summary :body}."
  []
  (->> (fs/glob posts-dir "*.md")
       (keep (fn [f]
               (when-let [[_ date slug] (re-matches #"(\d{4}-\d{2}-\d{2})-(.+)\.md" (fs/file-name f))]
                 (merge {:slug slug :date date :path (str "/blog/" slug "/")}
                        (md/split-title (slurp (str f)))))))
       (sort-by :date #(compare %2 %1))))

(defn index []
  (ui/render-page
   (ui/layout
    {:title "Blog · Xi" :description "Notes on building with Xi." :path "/blog/" :body-class "blog"}
    [:div.changelog-shell
     [:article.docs-content
      [:h1 "Blog"]
      [:ul.blog-list
       (for [{:keys [path title summary date]} (posts)]
         [:li
          [:a {:href path} [:strong title]]
          [:small (display-date date)]
          [:p summary]])]]])))

(defn post-page [{:keys [path title summary body date]}]
  (ui/render-page
   (ui/layout
    {:title (str title " · Xi") :description summary :path path :body-class "blog"}
    [:div.changelog-shell
     [:article.docs-content.blog-post
      [:p.blog-meta [:a {:href "/blog/"} "Blog"] " · " (display-date date)]
      [:h1 title]
      (h/raw (md/render-body body))]])))

(defn routes []
  (into {"/blog/" (index)}
        (for [p (posts)] [(:path p) (post-page p)])))
