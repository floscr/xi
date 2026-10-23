(ns xi.url
  "URL detection utilities — recognizing bare http(s):// URLs embedded in
   text, and resolving relative links between files. Pure and cross-platform
   (Clojure + ClojureScript). Knows nothing about markdown; callers decide
   what to do with a detected URL."
  (:require [clojure.string :as str]))

(def ^:private schemes ["https://" "http://"])

(defn scheme-at
  "Returns the URL scheme prefix (\"https://\" or \"http://\") that `s` starts
   with at index `idx`, or nil."
  [^String s idx]
  (some (fn [p]
          (let [end (+ idx (count p))]
            (when (and (<= end (count s)) (= (subs s idx end) p))
              p)))
        schemes))

(defn- boundary-char?
  "Characters that terminate a bare URL while scanning."
  [ch]
  (or (= ch \space) (= ch \tab) (= ch \newline) (= ch \return)
      (= ch \<) (= ch \>) (= ch \") (= ch \`) (= ch \|) (= ch \\)))

;; Trailing punctuation trimmed from the end of a bare URL (GFM-style).
;; Closing parens are handled separately so balanced parens are kept.
(def ^:private trailing-punct
  #{\. \, \; \: \! \? \' \" \] \} \* \_ \~})

(defn- normalize-path
  "Collapse `.` and `..` segments; leading `..` of a relative path are kept."
  [path]
  (let [abs?  (str/starts-with? path "/")
        parts (reduce (fn [acc seg]
                        (cond
                          (or (= seg "") (= seg ".")) acc
                          (and (= seg "..") (seq acc) (not= ".." (peek acc))) (pop acc)
                          (and (= seg "..") abs?) acc
                          :else (conj acc seg)))
                      [] (str/split path #"/"))]
    (str (when abs? "/") (str/join "/" parts))))

(defn- decode [s]
  #?(:cljs (try (js/decodeURIComponent s) (catch :default _ s))
     :clj  (try (java.net.URLDecoder/decode (str/replace s "+" "%2B") "UTF-8")
                (catch Exception _ s))))

(defn relative-file-link
  "The file path an `href` found in the file at `base-path` points to, or nil
   when it isn't a link to another file: schemes (http:, mailto:, …),
   protocol-relative and pure #fragment links. Drops ?query, #fragment and a
   `:line[:col]` suffix; absolute paths are returned as-is, a nil `base-path`
   leaves relative ones relative."
  [base-path href]
  (when (and (string? href)
             (not (str/blank? href))
             (not (str/starts-with? href "#"))
             (not (str/starts-with? href "//"))
             (or (not (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*:" href))
                 ;; README.md:12 — a file and line, not a scheme
                 (re-find #"^[^:/]*\.[^:/]*:\d+(:\d+)?([?#]|$)" href)))
    (let [path (-> (first (str/split href #"[?#]" 2))
                   (str/replace #":\d+(:\d+)?$" "")
                   decode)]
      (when-not (str/blank? path)
        (if (str/starts-with? path "/")
          (normalize-path path)
          (let [dir (let [i (str/last-index-of (or base-path "") "/")]
                      (when i (subs base-path 0 (inc i))))]
            (normalize-path (str dir path))))))))

(defn link-fragment
  "The decoded #fragment of `href` (`a.md#setup` → \"setup\"), or nil."
  [href]
  (when (string? href)
    (let [i (str/index-of href "#")]
      (when i
        (let [frag (decode (subs href (inc i)))]
          (when-not (str/blank? frag) frag))))))

(defn- drop-slug-junk
  "Drop what a GitHub heading anchor leaves out: all but letters, digits,
   `_`, `-` and spaces."
  [s]
  #?(:cljs (.replace s (js/RegExp. "[^\\p{L}\\p{M}\\p{N}\\p{Pc} -]" "gu") "")
     :clj  (str/replace s #"[^\p{L}\p{M}\p{N}\p{Pc} -]" "")))

(defn heading-slugs
  "GitHub's anchor ids for headings with these texts, in document order:
   lower-cased, punctuation dropped, spaces as `-`, a repeated slug suffixed
   `-1`, `-2`, …."
  [texts]
  (first
   (reduce (fn [[slugs seen] text]
             (let [base (-> (str/lower-case (str/trim (or text "")))
                            drop-slug-junk
                            (str/replace " " "-"))
                   n    (get seen base 0)
                   slug (if (pos? n) (str base "-" n) base)]
               [(conj slugs slug) (assoc seen base (inc n) slug 1)]))
           [[] {}] texts)))

(defn url-at
  "If a bare http(s):// URL begins at index `idx` in `s`, returns
   `[url end]` where `end` is the exclusive end index after GFM-style
   trailing-punctuation trimming (balanced parens preserved). Otherwise nil.

   Does not consider whether the URL is embedded in a surrounding word —
   callers apply that policy (see `scheme-at`)."
  [^String s idx]
  (when-let [prefix (scheme-at s idx)]
    (let [len (count s)
          min-end (+ idx (count prefix))
          raw-end (loop [i min-end]
                    (if (or (>= i len) (boundary-char? (.charAt s i)))
                      i
                      (recur (inc i))))]
      (when (> raw-end min-end)
        (let [end (loop [e raw-end]
                    (if (<= e min-end)
                      e
                      (let [ch (.charAt s (dec e))]
                        (cond
                          ;; Trim a trailing ')' only when unbalanced, so URLs
                          ;; like …/Foo_(bar) keep their closing paren.
                          (= ch \))
                          (let [sub (subs s idx e)
                                opens (count (filter #(= % \() sub))
                                closes (count (filter #(= % \)) sub))]
                            (if (> closes opens) (recur (dec e)) e))
                          (contains? trailing-punct ch) (recur (dec e))
                          :else e))))]
          (when (> end min-end)
            [(subs s idx end) end]))))))
