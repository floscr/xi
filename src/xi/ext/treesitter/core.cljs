(ns xi.ext.treesitter.core
  "Tree-sitter outline extension — maki-style context-token saving.

   Overrides the builtin `read` tool (a :tool-registry entry of the same name):
   reading a large supported source file (no offset/limit) returns a compact
   structural outline (imports, types, fn signatures with 1-based
   [line-ranges]) instead of the full contents; everything else falls through
   to the builtin read. The model then pulls only the lines it needs.

   Adds `read_source` for literal code: whole file, a line range, or one named
   definition located via tree-sitter node boundaries.

   Parsing is in-process WASM with the runtime and grammars vendored under
   resources/treesitter (see docs/treesitter.md); the factory returns nil when
   they are missing, so the extension silently stays off."
  (:require [clojure.string :as str]
            [xi.ext.treesitter.parse :as p]
            [xi.ext.treesitter.langs :as langs]
            [xi.ext.treesitter.skeleton :as skeleton]
            [xi.tools.fs :as tfs]
            [xi.tools.read :as read]
            [xi.tools.util :as util]
            ["node:fs" :as fs]))

(def ^:private min-lines
  "Files shorter than this are read whole — outlining tiny files saves nothing."
  120)

(def ^:private max-bytes
  "Files larger than this skip outlining (read caps output anyway)."
  (* 2 1024 1024))

(defn- line-count [^js buf]
  (let [len (.-length buf)]
    (loop [i 0 n 1]
      (if (< i len)
        (recur (inc i) (if (= 10 (aget buf i)) (inc n) n))
        n))))

(defn- slice-lines
  "1-based inclusive line range of a source string."
  [text start end]
  (let [lines (str/split text #"\n" -1)
        total (count lines)
        s (max 1 (or start 1))
        e (min total (or end total))]
    {:text (str/join "\n" (subvec lines (dec s) e))
     :start s :end e :total total}))

(defn- outline-result [path total skeleton-text]
  {:content
   [{:type "text"
     :text (str "[" path " — " total " lines. Structural outline; [n-m] are 1-based line ranges.\n"
                " Literal code: read_source(path, symbol) for one definition, "
                "read(path, offset, limit) for a range (offset is 0-based).]\n\n"
                skeleton-text)}]})

(defn- try-outline
  "→ Promise of the outline tool result, or nil (read the file normally)."
  [resolved display-path lang]
  (-> (js/Promise.resolve)
      (.then (fn [_]
               (let [src (fs/readFileSync resolved)
                     total (line-count src)]
                 (when (>= total min-lines)
                   (-> (p/parse-file lang resolved)
                       (.then (fn [root]
                                (let [entries ((langs/extractor lang) root src)
                                      text (skeleton/format-skeleton entries)]
                                  ;; Only intercept when the outline is a real
                                  ;; saving over the file itself.
                                  (when (and text
                                             (< (count text) (* 0.5 (.-length src))))
                                    (outline-result display-path total text))))))))))
      (.catch (fn [_] nil))))

(defn- outlinable
  "→ {:resolved :lang} when path is a supported, present, not-too-big source
   file, else nil."
  [path cwd]
  (let [resolved (tfs/resolve-path path cwd)
        lang (langs/lang-for resolved)]
    (when (and lang
               (p/grammar? lang)
               (langs/extractor lang)
               (fs/existsSync resolved)
               (not (.isDirectory (fs/statSync resolved)))
               (< (.-size (fs/statSync resolved)) max-bytes))
      {:resolved resolved :lang lang})))

(defn- read-with-outline
  "The `read` tool, overridden via :tool-registry: a plain read (no
   offset/limit) of a large supported source file returns its outline;
   everything else falls through to the builtin read."
  [{:keys [path offset limit] :as args} {:keys [cwd] :as ctx}]
  (if-let [{:keys [resolved lang]} (and path (nil? offset) (nil? limit)
                                        (outlinable path cwd))]
    (-> (try-outline resolved path lang)
        (.then (fn [outline] (or outline (read/execute args ctx)))))
    (read/execute args ctx)))

;; ── read_source tool ─────────────────────────────────────────────────────────

(defn- text-result [s] {:content [{:type "text" :text s}]})
(defn- error-result [s] {:content [{:type "text" :text s}] :is-error true})

(defn- read-symbol [resolved path symbol lang src]
  (-> (p/parse-file lang resolved)
      (.then
       (fn [root]
         (let [entries ((langs/extractor lang) root src)
               syms (skeleton/symbols entries)]
           (if-let [{:keys [start end]} (get syms symbol)]
             (let [full (.toString src "utf8")
                   {:keys [text total]} (slice-lines full start end)]
               (text-result (str "[" path " lines " start "-" end " of " total "]\n" text
                                 "\n[file-hash: " (util/content-hash full) "]")))
             (error-result
              (str "Symbol not found: " symbol "\nAvailable: "
                   (str/join ", " (sort (keys syms)))))))))))

(defn- read-source
  "Literal source: whole file, line range, or a named definition."
  [{:keys [path symbol start_line end_line]} {:keys [cwd]}]
  (let [resolved (tfs/resolve-path path cwd)]
    (cond
      (not (fs/existsSync resolved))
      (error-result (str "File not found: " path))

      symbol
      (let [lang (langs/lang-for resolved)]
        (if (and lang (p/grammar? lang) (langs/extractor lang))
          (read-symbol resolved path symbol lang (fs/readFileSync resolved))
          (error-result (str "No tree-sitter support for " path
                             " — use start_line/end_line instead."))))

      (or start_line end_line)
      (let [content (fs/readFileSync resolved "utf8")
            {:keys [text start end total]}
            (slice-lines content start_line end_line)]
        (text-result (str "[" path " lines " start "-" end " of " total "]\n" text
                          "\n[file-hash: " (util/content-hash content) "]")))

      :else
      (let [content (fs/readFileSync resolved "utf8")]
        (text-result (str content "\n[file-hash: " (util/content-hash content) "]"))))))

(def ^:private read-source-def
  {:name "read_source"
   :description
   (str "Follow-up to `read`: pull literal source for a definition you found in a "
        "read outline. Always `read` the file first — the outline shows what exists "
        "and where; only then fetch the parts you need. "
        "Modes: {path, symbol} → the full source of one named definition "
        "(function/class/type — names are shown in the outline; methods as Class.method); "
        "{path, start_line, end_line} → a 1-based inclusive line range; "
        "{path} alone → the whole file verbatim (last resort — defeats the outline's "
        "token savings).")
   :input_schema
   {:type "object"
    :properties {:path {:type "string" :description "Path to file"}
                 :symbol {:type "string" :description "Named definition to read (e.g. greet or Client.fetch)"}
                 :start_line {:type "integer" :description "First line, 1-based inclusive"}
                 :end_line {:type "integer" :description "Last line, 1-based inclusive"}}
    :required ["path"]}})

(def ^:private system-prompt
  (str "## Reading code\n"
       "For large source files the read tool returns a tree-sitter outline "
       "(definitions with [line-ranges]) instead of full contents — this is "
       "expected, not an error. Always start with read: the outline tells you "
       "what exists and where. Then pull just the definition you need with "
       "read_source(path, symbol) instead of reading line ranges. Never open a "
       "file with read_source, and treat whole-file read_source(path) as a "
       "last resort."))

(defn create
  "Extension factory — nil (disabled) when the runtime/grammars are absent.
   Starts loading the WASM runtime in the background so the synchronous rules
   `:node` consult is ready by the first tool call."
  [_ctx]
  (when (p/available?)
    (p/ready!)
    {:id :treesitter
     :tool-definitions [read-source-def]
     :tool-registry {"read"        read-with-outline
                     "read_source" read-source}
     :system-prompt system-prompt}))
