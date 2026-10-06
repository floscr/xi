(ns xisite.docs
  "The guide: docs/guide/*.md rendered into /docs/<slug>/. Navigation comes
   from the `## Pages` list in docs/guide/README.md — a page not listed there
   is not published, as that file promises."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [xisite.markdown :as md]))

(def guide-dir "../docs/guide")

(defn- readme-sections
  "Parse the Pages list: `**Section**` lines open a section, `- [Title](file.md)
   — hook` lines add a page. Italic *planned* entries have no link and are
   skipped."
  [readme]
  (let [pages-part (second (str/split readme #"(?m)^## Pages\s*$" 2))
        pages-part (first (str/split (or pages-part "") #"(?m)^## " 2))]
    (->> (str/split-lines pages-part)
         (reduce (fn [sections line]
                   (cond
                     (re-matches #"\s*\*\*(.+)\*\*\s*" line)
                     (conj sections {:title (second (re-matches #"\s*\*\*(.+)\*\*\s*" line))
                                     :pages []})

                     (re-find #"^\s*-\s+\[([^\]]+)\]\(([^)]+)\.md\)" line)
                     (let [[_ title file] (re-find #"^\s*-\s+\[([^\]]+)\]\(([^)]+)\.md\)" line)
                           hook (second (re-find #"\)\s+[—-]+\s+(.*)$" line))]
                       (if (seq sections)
                         (update-in sections [(dec (count sections)) :pages]
                                    conj {:slug file :nav-title title :hook hook})
                         sections))

                     :else sections))
                 []))))

(defn- load-page [{:keys [slug] :as entry}]
  (let [path (str guide-dir "/" slug ".md")]
    (when (fs/exists? path)
      (let [{:keys [title summary body]} (md/split-title (slurp path))
            html (md/render-body body)]
        (assoc entry
               :title title
               :summary summary
               :html html
               :headings (md/headings html)
               :path (str "/docs/" slug "/")
               :source-path (str "docs/guide/" slug ".md"))))))

(defn sections
  "[{:title … :pages [page …]} …] in reading order, with each page rendered."
  []
  (->> (readme-sections (slurp (str guide-dir "/README.md")))
       (map (fn [s] (update s :pages #(vec (keep load-page %)))))
       (filter #(seq (:pages %)))
       vec))

(defn pages
  "Every page in reading order, each with :prev / :next."
  [sections]
  (let [ps (vec (mapcat :pages sections))]
    (vec (map-indexed (fn [i p]
                        (assoc p
                               :prev (get ps (dec i))
                               :next (get ps (inc i))))
                      ps))))
