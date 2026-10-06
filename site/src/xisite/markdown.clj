(ns xisite.markdown
  "Markdown → html for the guide pages: fenced code blocks with build-time
   highlighting, github-style heading ids, and link rewriting so the plain
   `.md` links the guide uses keep working on the site."
  (:require [clojure.string :as str]
            [hiccup2.core :as h]
            [markdown.core :as md]
            [xisite.core :as core]
            [xisite.highlight :as highlight]))

;; --- Code blocks ---

(defn code-block-hiccup
  "A fenced code block: highlighted <pre><code>. The block is always dark, so it opts into the framework's
   dark tokens (`data-theme=\"dark\"` re-scopes them to this subtree). An optional `output` source is
   shown as a second highlighted <pre> in the same wrapper, divided from the code by a border. An optional
   `title` (a ```lang title=\"…\" fence) is a header above the code."
  ([lang source] (code-block-hiccup lang source nil nil))
  ([lang source output] (code-block-hiccup lang source output nil))
  ([lang source output title]
   [:div.code-block {:data-theme "dark"}
    (when title [:div.code-title title])
    [:pre [:code {:data-language (or lang "text")}
           (seq (highlight/highlight lang source))]]
    (when output
      [:pre.code-output [:code {:data-language (or lang "text")}
                         (seq (highlight/highlight lang output))]])]))

;; --- Heading ids (github-slugger style) ---

(defn slugify [s]
  (-> s
      (str/replace #"<[^>]*>" "")
      str/lower-case
      (str/replace #"[^\p{L}\p{N}\s-]" "")
      str/trim
      (str/replace #"\s+" "-")))

(defn- add-heading-ids [html]
  (str/replace html #"<h([1-6])>(.*?)</h\1>"
               (fn [[_ level content]]
                 (format "<h%s id=\"%s\">%s</h%s>" level (slugify content) content level))))

(defn headings
  "[{:level 2 :id \"…\" :text \"…\"} …] from rendered html, for the page toc."
  [html]
  (for [[_ level id content] (re-seq #"<h([2-3]) id=\"([^\"]+)\">(.*?)</h\1>" html)]
    {:level (parse-long level)
     :id id
     :text (str/replace content #"<[^>]*>" "")}))

;; --- Links ---

(defn- rewrite-href
  "Guide-internal `page.md#x` → `/docs/page/#x`; reference `../x.md` →
   the repo's docs on the forge; anything else untouched."
  [href]
  (cond
    (re-find #"^(?:https?:|mailto:|#|/)" href) href

    (str/starts-with? href "../")
    (str core/reference-docs-url (subs href 3))

    :else
    (if-let [[_ page anchor] (re-matches #"([^#]+)\.md(#.*)?" href)]
      (str "/docs/" page "/" (or anchor ""))
      href)))

(defn- rewrite-links
  "markdown-clj emits single-quoted hrefs; accept both."
  [html]
  (str/replace html #"<a href=(['\"])([^'\"]+)\1"
               (fn [[_ _ href]]
                 (let [out (rewrite-href href)]
                   (str "<a href=\"" out "\""
                        (when (str/starts-with? out "http")
                          " rel=\"noopener\""))))))

;; --- Rendering ---

(def ^:private code-fence-re
  "A fence (optionally with a `title=\"…\"` header), optionally followed directly by an ```output fence (the result of the code above it)."
  #"(?ms)^```([\w-]*)(?:[ \t]+title=\"([^\"\n]*)\")?[ \t]*\n(.*?)^```[ \t]*$(?:\n+^```output[ \t]*\n(.*?)^```[ \t]*$)?")

(defn render-body
  "Markdown → html string."
  [body]
  (let [blocks (atom [])
        body (str/replace body code-fence-re
                          (fn [[_ lang title source output]]
                            (let [lang (when (seq lang) lang)
                                  source (str/replace source #"\n\z" "")
                                  output (some-> output (str/replace #"\n\z" ""))
                                  html (str (h/html (code-block-hiccup lang source output title)))]
                              (swap! blocks conj html)
                              (str "\n§CODEBLOCK" (dec (count @blocks)) "§\n"))))
        html (md/md-to-html-string body :heading-anchors false)
        html (add-heading-ids html)
        html (rewrite-links html)]
    (str/replace html #"(?:<p>)?§CODEBLOCK(\d+)§(?:</p>)?"
                 (fn [[_ idx]]
                   (str/re-quote-replacement (nth @blocks (parse-long idx)))))))

(defn split-title
  "A guide page starts with `# Title` and, usually, a one-paragraph summary (empty when a heading follows).
   → {:title :summary :body} (body still includes the summary)."
  [text]
  (let [lines (str/split-lines text)
        title (some->> lines (drop-while str/blank?) first (re-find #"^#\s+(.*)$") second)
        rest-lines (->> lines (drop-while str/blank?) rest (drop-while str/blank?))
        summary (if (some-> (first rest-lines) (str/starts-with? "#"))
                   ""
                   (->> rest-lines (take-while (complement str/blank?)) (str/join " ")))
        body (str/join "\n" (->> lines (drop-while str/blank?) rest))]
    {:title (or title "Untitled")
     :summary (str/replace summary #"[`*_]" "")
     :body body}))
