(ns xi.ext.web
  "Web fetch extension — retrieve and clean web content.
   Ported from Pi's web.ts: UA rotation, HTML→markdown, Jina Reader fallback,
   bot-blocking detection, feed parsing, low-quality detection."
  (:require [clojure.string :as str]))

;; ── Constants ─────────────────────────────────────────────────────────────────

(def ^:private MAX_OUTPUT_CHARS 100000)
(def ^:private MAX_OUTPUT_LINES 300)
(def ^:private FETCH_TIMEOUT_MS 20000)

(def ^:private USER_AGENTS
  ["curl/8.0"
   "Mozilla/5.0 (compatible; TextBot/1.0)"
   "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"])

;; ── HTML to Markdown ──────────────────────────────────────────────────────────

(defn- decode-html-entities [text]
  (-> text
      (str/replace "&lt;" "<")
      (str/replace "&gt;" ">")
      (str/replace "&amp;" "&")
      (str/replace "&quot;" "\"")
      (str/replace #"&#0?39;" "'")
      (str/replace "&#x27;" "'")
      (str/replace "&#x2F;" "/")
      (str/replace "&nbsp;" " ")))

(defn- html-to-markdown [html]
  (-> html
      ;; Remove script, style, nav, footer
      (str/replace #"(?i)<script[^>]*>[\s\S]*?</script>" "")
      (str/replace #"(?i)<style[^>]*>[\s\S]*?</style>" "")
      (str/replace #"(?i)<nav[^>]*>[\s\S]*?</nav>" "")
      (str/replace #"(?i)<footer[^>]*>[\s\S]*?</footer>" "")
      ;; Code blocks
      (str/replace #"(?i)<pre[^>]*><code[^>]*>" "\n```\n")
      (str/replace #"(?i)</code></pre>" "\n```\n")
      (str/replace #"(?i)<code[^>]*>" "`")
      (str/replace #"(?i)</code>" "`")
      ;; Bold
      (str/replace #"(?i)<strong[^>]*>" "**")
      (str/replace #"(?i)</strong>" "**")
      (str/replace #"(?i)<b[^>]*>" "**")
      (str/replace #"(?i)</b>" "**")
      ;; Italic
      (str/replace #"(?i)<em[^>]*>" "*")
      (str/replace #"(?i)</em>" "*")
      (str/replace #"(?i)<i[^>]*>" "*")
      (str/replace #"(?i)</i>" "*")
      ;; Links
      (str/replace #"(?i)<a[^>]*href=\"([^\"]+)\"[^>]*>([\s\S]*?)</a>"
                   (fn [[_ href text]]
                     (str "[" (str/replace text #"<[^>]+>" "") "](" href ")")))
      ;; Paragraphs / breaks
      (str/replace #"(?i)<p[^>]*>" "\n\n")
      (str/replace #"(?i)</p>" "")
      (str/replace #"(?i)<br\s*/?>" "\n")
      ;; Lists
      (str/replace #"(?i)<li[^>]*>" "- ")
      (str/replace #"(?i)</li>" "\n")
      (str/replace #"(?i)</?[uo]l[^>]*>" "\n")
      ;; Headings
      (str/replace #"(?i)<h(\d)[^>]*>"
                   (fn [[_ n]] (str "\n" (apply str (repeat (js/parseInt n 10) "#")) " ")))
      (str/replace #"(?i)</h\d>" "\n")
      ;; Blockquote
      (str/replace #"(?i)<blockquote[^>]*>" "\n> ")
      (str/replace #"(?i)</blockquote>" "\n")
      ;; HR
      (str/replace #"(?i)<hr[^>]*/?>" "\n---\n")
      ;; Strip remaining tags
      (str/replace #"<[^>]+>" "")
      ;; Collapse whitespace
      (str/replace #"\n{3,}" "\n\n")
      str/trim
      decode-html-entities))

;; ── Utilities ─────────────────────────────────────────────────────────────────

(defn- normalize-url [url]
  (if (re-find #"^https?://" url)
    url
    (str "https://" url)))

(defn- normalize-mime [content-type]
  (-> (or content-type "")
      (str/split #";")
      first
      str/trim
      str/lower-case))

(defn- looks-like-html? [content]
  (let [trimmed (str/lower-case (str/trim content))]
    (or (str/starts-with? trimmed "<!doctype")
        (str/starts-with? trimmed "<html")
        (str/starts-with? trimmed "<head")
        (str/starts-with? trimmed "<body"))))

(defn- low-quality-output? [content]
  (let [lower (str/lower-case content)
        js-gated ["enable javascript" "javascript required"
                  "turn on javascript" "please enable javascript"
                  "browser not supported"]]
    (or
     ;; Short JS-gated page
     (and (< (count content) 1024)
          (some #(str/includes? lower %) js-gated))
     ;; Mostly short lines (navigation menu junk)
     (let [lines (filterv #(not (str/blank? %)) (str/split-lines content))]
       (and (> (count lines) 10)
            (> (/ (count (filterv #(< (count (str/trim %)) 40) lines))
                  (count lines))
               0.7))))))

(defn- format-json [content]
  (try
    (js/JSON.stringify (js/JSON.parse content) nil 2)
    (catch :default _ content)))

(defn- truncate-output [content]
  (let [lines (str/split-lines content)
        [content line-truncated?] (if (> (count lines) MAX_OUTPUT_LINES)
                                    [(str/join "\n" (take MAX_OUTPUT_LINES lines)) true]
                                    [content false])
        [content char-truncated?] (if (> (count content) MAX_OUTPUT_CHARS)
                                    [(subs content 0 MAX_OUTPUT_CHARS) true]
                                    [content false])
        truncated? (or line-truncated? char-truncated?)]
    {:content (-> content
                  (str/replace #"\n{3,}" "\n\n")
                  str/trim)
     :truncated truncated?}))

;; ── Page Loading ──────────────────────────────────────────────────────────────

(defn- load-page
  "Fetch URL with UA rotation and bot-blocking retry. Returns promise of map."
  [url timeout-ms]
  (let [attempt (atom 0)]
    (letfn [(try-fetch []
              (let [ua (nth USER_AGENTS @attempt)
                    controller (js/AbortController.)
                    timer (js/setTimeout #(.abort controller) timeout-ms)]
                (-> (js/fetch url
                              #js {:signal (.-signal controller)
                                   :headers #js {"User-Agent" ua
                                                 "Accept" "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                                                 "Accept-Language" "en-US,en;q=0.5"}
                                   :redirect "follow"})
                    (.then (fn [resp]
                             (js/clearTimeout timer)
                             (let [ct (or (.get (.-headers resp) "content-type") "")
                                   final-url (.-url resp)
                                   status (.-status resp)]
                               (-> (.text resp)
                                   (.then (fn [body]
                                            (if (and (or (= status 403) (= status 503))
                                                     (< @attempt (dec (count USER_AGENTS))))
                                              ;; Check for bot blocking
                                              (let [lower (str/lower-case body)]
                                                (if (some #(str/includes? lower %)
                                                          ["cloudflare" "captcha" "blocked" "access denied"])
                                                  (do (swap! attempt inc)
                                                      (try-fetch))
                                                  {:content body :content-type ct
                                                   :final-url final-url :ok (.-ok resp) :status status}))
                                              {:content body :content-type ct
                                               :final-url final-url :ok (.-ok resp) :status status})))))))
                    (.catch (fn [_err]
                              (js/clearTimeout timer)
                              (if (< @attempt (dec (count USER_AGENTS)))
                                (do (swap! attempt inc)
                                    (try-fetch))
                                {:content "" :content-type "" :final-url url :ok false}))))))]
      (try-fetch))))

;; ── Jina Reader Fallback ──────────────────────────────────────────────────────

(defn- try-jina-reader
  "Try Jina Reader for better HTML→markdown. Returns promise of string or nil."
  [url]
  (-> (js/fetch (str "https://r.jina.ai/" url)
                #js {:headers #js {"Accept" "text/markdown"}})
      (.then (fn [resp]
               (if (.-ok resp)
                 (-> (.text resp)
                     (.then (fn [content]
                              (when (and (> (count (str/trim content)) 100)
                                         (not (low-quality-output? content)))
                                content))))
                 nil)))
      (.catch (fn [_] nil))))

;; ── Feed Parsing ──────────────────────────────────────────────────────────────

(defn- parse-feed [raw-content]
  (let [items (atom [])
        re (js/RegExp. "<(?:item|entry)[\\s>]([\\s\\S]*?)</(?:item|entry)>" "gi")]
    (loop []
      (when-let [m (.exec re raw-content)]
        (when (< (count @items) 20)
          (let [block (aget m 1)
                title (or (some-> (re-find #"(?i)<title[^>]*>([\s\S]*?)</title>" block)
                                  second
                                  (str/replace #"<!\[CDATA\[|\]\]>" "")
                                  str/trim)
                          "Untitled")
                link (or (second (re-find #"(?i)<link[^>]*href=\"([^\"]+)\"" block))
                         (some-> (re-find #"(?i)<link[^>]*>([\s\S]*?)</link>" block)
                                 second
                                 str/trim))]
            (swap! items conj
                   (str "## " (decode-html-entities title)
                        (when link (str "\n[Link](" link ")"))
                        "\n"))))
        (recur)))
    (if (seq @items)
      (str/join "\n---\n\n" @items)
      raw-content)))

;; ── Fetch Pipeline ────────────────────────────────────────────────────────────

(defn- render-url
  "Fetch and process a URL. Returns promise of result map."
  [url timeout-ms raw?]
  (let [url (normalize-url url)
        notes (atom [])]
    (-> (load-page url timeout-ms)
        (.then
         (fn [{:keys [content content-type final-url ok status]}]
           (if-not ok
             ;; Failed
             {:url url :final-url (or final-url url) :content-type (or content-type "unknown")
              :method "failed" :content "" :truncated false
              :notes [(if status (str "Failed to fetch URL (HTTP " status ")") "Failed to fetch URL")]}
             ;; Success — route by content type
             (let [mime (normalize-mime content-type)
                   is-html? (or (str/includes? mime "html") (str/includes? mime "xhtml"))
                   is-json? (str/includes? mime "json")
                   is-text? (or (str/includes? mime "text/plain") (str/includes? mime "text/markdown"))
                   is-feed? (or (str/includes? mime "rss") (str/includes? mime "atom") (str/includes? mime "feed"))
                   is-xml-feed? (and (str/includes? mime "xml")
                                     (or (str/includes? content "<rss") (str/includes? content "<feed")))]
               (cond
                 ;; JSON
                 is-json?
                 (let [{:keys [content truncated]} (truncate-output (format-json content))]
                   {:url url :final-url final-url :content-type mime :method "json"
                    :content content :truncated truncated :notes @notes})

                 ;; RSS/Atom feed
                 (or is-feed? is-xml-feed?)
                 (let [{:keys [content truncated]} (truncate-output (parse-feed content))]
                   {:url url :final-url final-url :content-type mime :method "feed"
                    :content content :truncated truncated :notes @notes})

                 ;; Plain text / markdown
                 (and is-text? (not (looks-like-html? content)))
                 (let [{:keys [content truncated]} (truncate-output content)]
                   {:url url :final-url final-url :content-type mime :method "text"
                    :content content :truncated truncated :notes @notes})

                 ;; HTML — try Jina, then fallback to built-in conversion
                 (and is-html? (not raw?))
                 (-> (try-jina-reader final-url)
                     (.then (fn [jina-content]
                              (if jina-content
                                (do (swap! notes conj "Rendered via Jina Reader")
                                    (let [{:keys [content truncated]} (truncate-output jina-content)]
                                      {:url url :final-url final-url :content-type "text/markdown"
                                       :method "jina" :content content :truncated truncated :notes @notes}))
                                ;; Fallback: built-in HTML to markdown
                                (let [markdown (html-to-markdown content)]
                                  (if (and (> (count markdown) 100) (not (low-quality-output? markdown)))
                                    (let [{:keys [content truncated]} (truncate-output markdown)]
                                      {:url url :final-url final-url :content-type mime :method "html-to-markdown"
                                       :content content :truncated truncated :notes @notes})
                                    (do (swap! notes conj "Page may require JavaScript or is mostly navigation")
                                        (let [{:keys [content truncated]} (truncate-output (or markdown content))]
                                          {:url url :final-url final-url :content-type mime :method "raw-html"
                                           :content content :truncated truncated :notes @notes}))))))))

                 ;; Raw HTML requested
                 (and raw? is-html?)
                 (let [{:keys [content truncated]} (truncate-output content)]
                   {:url url :final-url final-url :content-type mime :method "raw"
                    :content content :truncated truncated :notes @notes})

                 ;; Fallback: raw content
                 :else
                 (let [{:keys [content truncated]} (truncate-output content)]
                   {:url url :final-url final-url :content-type mime :method "raw"
                    :content content :truncated truncated :notes @notes})))))))))

;; ── Extension ─────────────────────────────────────────────────────────────────

(def ^:private fetch-def
  {:name "fetch"
   :description "Retrieve content from a URL and return it in a clean, readable format.

- Extract information from web pages, GitHub issues/PRs, Stack Overflow, Wikipedia, Reddit, NPM, arXiv, technical blogs, RSS/Atom feeds, JSON endpoints
- Use `raw: true` for untouched HTML or debugging

Returns processed, readable content. HTML transformed to markdown. JSON returned formatted. With `raw: true`, returns untransformed HTML."
   :input_schema {:type "object"
                  :properties {:url {:type "string" :description "URL to fetch"}
                               :timeout {:type "number" :description "Timeout in seconds (default: 20)"}
                               :raw {:type "boolean" :description "Return raw HTML without transforms"}}
                  :required ["url"]}})

(defn- fetch-tool [{:keys [url timeout raw]} _ctx]
  (let [timeout-ms (* (min (max (or timeout 20) 5) 60) 1000)]
    (-> (render-url url timeout-ms (boolean raw))
        (.then (fn [result]
                 (let [output (str "URL: " (:final-url result) "\n"
                                   "Content-Type: " (:content-type result) "\n"
                                   "Method: " (:method result) "\n"
                                   (when (seq (:notes result))
                                     (str "Notes: " (str/join "; " (:notes result)) "\n"))
                                   (when (:truncated result)
                                     (str "⚠ Output truncated to " MAX_OUTPUT_LINES " lines / " MAX_OUTPUT_CHARS " chars\n"))
                                   "\n---\n\n"
                                   (:content result))]
                   {:content [{:type "text" :text output}]})))
        (.catch (fn [e]
                  {:content [{:type "text" :text (str "Error fetching " url ": " (.-message e))}]
                   :is-error true})))))

(def extension
  {:id               :web
   :tool-definitions [fetch-def]
   :tool-registry    {"fetch" fetch-tool}})
