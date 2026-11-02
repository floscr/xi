(ns xisite.blog
  "/blog/: posts as markdown in site/blog/, named YYYY-MM-DD-slug.md. A post
   starts with `# Title` and a summary paragraph (xisite.markdown/split-title);
   its images go in public/blog/<slug>/. A ```breadboard fence is a live 3D
   board (xisite.markdown/breadboard-hiccup)."
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

(defn- comment-field
  "The value of a `<!-- name: value -->` line in a post; comments don't
   render, so they carry the index's metadata."
  [text name]
  (some-> (re-find (re-pattern (str "<!--\\s*" name ":\\s*(.+?)\\s*-->")) text) second))

(defn- metadata [text]
  {:tag (comment-field text "tag")
   :cover (or (comment-field text "cover")
              (second (re-find #"!\[[^\]]*\]\(([^)\s]+)\)" text)))
   :minutes (max 1 (Math/round (/ (count (re-seq #"\S+" text)) 220.0)))})

(defn posts
  "Newest first: {:slug :date :path :title :summary :body :tag :cover
   :minutes}. A post's cover is its first image unless a `<!-- cover: url -->`
   line names one; `<!-- tag: … -->` labels it on the index."
  []
  (->> (fs/glob posts-dir "*.md")
       (keep (fn [f]
               (when-let [[_ date slug] (re-matches #"(\d{4}-\d{2}-\d{2})-(.+)\.md" (fs/file-name f))]
                 (let [text (slurp (str f))]
                   (merge {:slug slug :date date :path (str "/blog/" slug "/")}
                          (md/split-title text)
                          (metadata text))))))
       (sort-by :date #(compare %2 %1))))

(defn- short-date [date]
  (let [[y m d] (str/split date #"-")]
    (str (parse-long d) " "
         (["Jan" "Feb" "Mar" "Apr" "May" "Jun" "Jul" "Aug" "Sep" "Oct" "Nov" "Dec"] (dec (parse-long m)))
         " " y)))

(defn- featured [{:keys [path title summary date tag cover minutes]}]
  [:a.blog-feature {:href path}
   (when cover [:img {:src cover :alt ""}])
   [:div.blog-feature-body
    (when tag [:span.blog-tag tag])
    [:h2 title]
    [:p summary]
    [:div.blog-feature-meta (display-date date) " · " minutes " min read · " [:span "Read →"]]]])

(defn- earlier [posts]
  (when (seq posts)
    (list
     [:h3.blog-section "Earlier posts"]
     [:div.blog-earlier
      (for [{:keys [path title summary date minutes]} posts]
        [:a {:href path}
         [:time {:datetime date} (short-date date)]
         [:div [:h4 title] [:p summary]]
         [:span.blog-minutes minutes " min"]])])))

(defn index []
  (let [[newest & older] (posts)]
    (ui/render-page
     (ui/layout
      {:title "Blog · Xi" :description "Notes from building with Xi." :path "/blog/" :body-class "blog"}
      [:div.blog-shell
       [:p.eyebrow "Blog"]
       [:h1 "Notes from building with Xi"]
       [:p.blog-lede "Projects, techniques and the occasional detour: what happens when an agent harness sits next to you at the desk, and at the bench."]
       (when newest (featured newest))
       (earlier older)]))))

(defn- board-scripts
  "<breadboard-view>'s import map (three.js and squint from jsDelivr) and
   bundle, both copied from xi-extensions by `bb extensions:sync`."
  []
  (list [:script {:type "importmap"} (h/raw (slurp "public/js/breadboard.importmap.json"))]
        [:script {:type "module" :src "/js/breadboard.js"}]))

(defn post-page [{:keys [path title summary body date]}]
  (let [html (md/render-body body)]
    (ui/render-page
     (ui/layout
      {:title (str title " · Xi") :description summary :path path :body-class "blog"
       :scripts (when (str/includes? html "<breadboard-view") (board-scripts))}
      [:div.changelog-shell
       [:article.docs-content.blog-post
        [:p.blog-meta [:a {:href "/blog/"} "Blog"] " · " (display-date date)]
        [:h1 title]
        (h/raw html)]]))))

(defn routes []
  (into {"/blog/" (index)}
        (for [p (posts)] [(:path p) (post-page p)])))
