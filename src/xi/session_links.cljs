(ns xi.session-links
  "The URLs and file paths a chat mentions, for the web palette's links page
   (xi.web.views). Pure reads over room history entries (shapes in xi.agent):
   URLs from the user's messages, the agent's text and tool arguments; files
   from file-tool arguments, markdown links and path-like inline code."
  (:require [clojure.string :as str]
            [xi.url :as url]
            [xi.util :as util]))

(defn text-urls
  "Bare http(s) URLs in `s`, in order."
  [s]
  (if-not (string? s)
    []
    (loop [idx 0 acc []]
      (if-let [i (str/index-of s "http" idx)]
        (if-let [[u end] (url/url-at s i)]
          (recur end (conj acc u))
          (recur (inc i) acc))
        acc))))

(def ^:private md-link-re #"\[[^\]\n]*\]\(([^)\s]+)\)")

;; `src/x.cljs`, `docs/a.md:12`, `README.md:3` — a dotted name alone
;; (`clojure.string`) needs a slash or a line to count as a file.
(def ^:private code-path-re
  #"^(?:~|\.{1,2})?/?[\w@.+-]+(?:/[\w@.+-]+)*\.[A-Za-z0-9]{1,10}(?::\d+(?::\d+)?)?$")

(defn- code-path? [s]
  (and (re-matches code-path-re s)
       (or (str/includes? s "/") (re-find #":\d+(?::\d+)?$" s))))

(defn text-files
  "File paths in markdown text: relative or absolute markdown link targets and
   path-like inline code, `:line[:col]` dropped."
  [s]
  (if-not (string? s)
    []
    (->> (concat (map second (re-seq md-link-re s))
                 (filter code-path? (map second (re-seq #"`([^`\s]+)`" s))))
         (keep #(url/relative-file-link nil %))
         vec)))

(defn- has-ext? [path]
  (boolean (re-find #"[^/]\.[A-Za-z0-9]+$" path)))

(defn- tool-files
  "Path arguments of a tool call. Dir-taking tools (ls, grep, …) pass dirs
   too, so outside the edit tools only a name with an extension counts."
  [{:keys [arguments] :as entry}]
  (when (map? arguments)
    (for [k [:path :file_path :file]
          :let [p (get arguments k)]
          :when (and (string? p) (not (str/blank? p))
                     (or (util/edit-tool-call? entry) (has-ext? p)))]
      p)))

(defn- arg-strings [arguments]
  (filter string? (tree-seq coll? seq arguments)))

(defn- entry-links [{:keys [kind text arguments] :as entry}]
  (case kind
    (:user :text) {:urls (text-urls text) :files (text-files text)}
    :tool-call    {:urls  (mapcat text-urls (arg-strings arguments))
                   :files (tool-files entry)}
    nil))

(defn links
  "{:urls :files} the history mentions, newest first, distinct. Files under
   `cwd` are relative to it, others stay as written."
  [history cwd]
  (let [per-entry (keep entry-links (reverse history))
        rel       (fn [p] (or (when (and cwd (str/starts-with? p "/"))
                                (util/relative-under cwd p))
                              (str/replace p #"^\./" "")))]
    {:urls  (into [] (comp (mapcat :urls) (distinct)) per-entry)
     :files (into [] (comp (mapcat :files) (map rel) (distinct)) per-entry)}))
