(ns xi.rules.nodes
  "Tree-sitter node extraction for the rules engine's opt-in `:node` matcher.

   Node-only: shells out (synchronously) to the xi-treesitter CLI via
   `xi.ext.treesitter.parse`. Returns a seq of {:type :name :text} maps that the
   pure matcher (`xi.rules`'s `match-node`) checks — `:type` is the raw
   tree-sitter node type, `:name` the node's `name` field text, `:text` its
   (truncated) source. Everything degrades to nil when treesitter, the grammar,
   or the language is unavailable, so a `:node` rule simply never matches then."
  (:require [clojure.string :as str]
            [xi.ext.treesitter.parse :as p]
            [xi.ext.treesitter.langs :as langs]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]))

(def ^:private text-cap
  "Cap `:text` per node so a `:contains` regex never scans a whole huge def."
  4000)

(defn- resolve-path
  "Absolute path for `p`: `~` → home, relative → against `cwd`."
  [cwd p]
  (let [p (str p)
        p (if (str/starts-with? p "~") (str (os/homedir) (subs p 1)) p)]
    (if (node-path/isAbsolute p)
      p
      (node-path/resolve (or cwd (.cwd js/process)) p))))

(defn- named-name
  "Text of the node's `name` field, compacted — nil when it has none."
  [src n]
  (some->> (p/child-by-field n "name") (p/node-text src) p/compact-ws))

(defn- node->map [src n]
  {:type (p/node-type n)
   :name (named-name src n)
   :text (p/truncate (p/node-text src n) text-cap)})

(defn- edit-line-range
  "0-based [start end] rows spanned by `old-text` in `content` (first
   occurrence), or nil when absent."
  [content old-text]
  (when (and content (string? old-text) (seq old-text))
    (let [idx (.indexOf content old-text)]
      (when (>= idx 0)
        (let [start (count (re-seq #"\n" (subs content 0 idx)))]
          [start (+ start (count (re-seq #"\n" old-text)))])))))

(defn- collect-enclosing
  "Named nodes (non-anonymous, with a `name` field) whose row span fully
   contains the edited [rs re] region — the enclosing defs of the edit."
  [src root [rs re]]
  (let [acc (transient [])]
    (letfn [(walk [^js n]
              (when (and (<= (.-sr n) rs) (>= (.-er n) re))
                (when (and (not (p/anon? n)) (named-name src n))
                  (conj! acc (node->map src n)))
                (doseq [c (p/named-children n)] (walk c))))]
      (walk root))
    (persistent! acc)))

(defn- top-level-defs
  "Top-level named defs of `root` — its named children with a `name`, descending
   one level into unnamed wrappers (export/decorated statements)."
  [src root]
  (into []
        (mapcat (fn [n]
                  (if (named-name src n)
                    [(node->map src n)]
                    (into []
                          (comp (filter #(named-name src %)) (map #(node->map src %)))
                          (p/named-children n)))))
        (p/named-children root)))

(defn- edit-nodes [lang path arguments]
  (let [content (try (fs/readFileSync path "utf8") (catch :default _ nil))
        old     (some-> arguments :edits first :oldText)
        range   (edit-line-range content old)]
    (when range
      (when-let [root (p/parse-file-sync lang path)]
        (collect-enclosing (fs/readFileSync path) root range)))))

(defn- write-nodes [lang path arguments]
  (let [content (:content arguments)]
    (when (string? content)
      (let [tmp (node-path/join
                 (os/tmpdir)
                 (str "xi-rules-node-" (.getTime (js/Date.)) "-" (rand-int 1000000000)
                      (node-path/extname path)))]
        (try
          (fs/writeFileSync tmp content)
          (when-let [root (p/parse-file-sync lang tmp)]
            (top-level-defs (fs/readFileSync tmp) root))
          (finally
            (try (fs/unlinkSync tmp) (catch :default _ nil))))))))

(defn nodes-for
  "The {:type :name :text} nodes for decision request `req`'s target file, for
   the `:node` matcher. nil when treesitter/grammar/language is unavailable (the
   matcher then never matches a `:node` rule). `:edit` targets the enclosing
   named node(s) of the edited region; `:write` targets the new content's
   top-level defs."
  [{:keys [tool path effective-cwd arguments]}]
  (when (and path (p/available?))
    (let [resolved (resolve-path effective-cwd path)
          lang     (langs/lang-for resolved)]
      (when (and lang (p/grammar? lang))
        (case tool
          :edit  (edit-nodes lang resolved arguments)
          :write (write-nodes lang resolved arguments)
          nil)))))
