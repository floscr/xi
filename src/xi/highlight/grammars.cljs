(ns xi.highlight.grammars
  "Lazy-loading grammar registry for syntax highlighting.
   Grammars are stored as EDN files in resources/highlight/grammars/
   and loaded on demand (first access), then cached in an atom.
   
   Auto-generated EDN from chroma lexer definitions (MIT licensed).
   See: https://github.com/alecthomas/chroma"
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            ["node:fs" :as fs]
            ["node:path" :as path]))

;; ── Resource resolution ───────────────────────────────────────────────────────

(defn- find-xi-root
  "Walk up from the main script's directory to find xi's project root.
   Looks for package.json."
  []
  (let [script-path (aget js/process.argv 1)
        start-dir (when script-path (.dirname path (.resolve path script-path)))]
    (when start-dir
      (loop [dir start-dir]
        (let [pkg (.join path dir "package.json")]
          (cond
            (fs/existsSync pkg) dir
            (= dir (.dirname path dir)) nil
            :else (recur (.dirname path dir))))))))

(def ^:private grammars-dir
  "Absolute path to the grammars EDN directory."
  (when-let [root (find-xi-root)]
    (.join path root "resources" "highlight" "grammars")))

;; ── Registry (alias → filename) ──────────────────────────────────────────────

(def ^:private registry-cache (atom nil))

(defn- load-registry
  "Load the registry.edn file mapping aliases to grammar filenames."
  []
  (or @registry-cache
      (when grammars-dir
        (let [registry-path (.join path grammars-dir "registry.edn")]
          (when (fs/existsSync registry-path)
            (let [content (.toString (fs/readFileSync registry-path "utf-8"))
                  reg (reader/read-string content)]
              (reset! registry-cache reg)
              reg))))))

;; ── Grammar cache ─────────────────────────────────────────────────────────────

(def ^:private grammar-cache
  "Cache of loaded grammars: filename → parsed grammar vector."
  (atom {}))

(defn- load-grammar-file
  "Load and parse a grammar EDN file by filename (without extension).
   Returns the grammar vector or nil."
  [filename]
  (if-let [cached (get @grammar-cache filename)]
    cached
    (when grammars-dir
      (let [edn-path (.join path grammars-dir (str filename ".edn"))]
        (when (fs/existsSync edn-path)
          (let [content (.toString (fs/readFileSync edn-path "utf-8"))
                grammar (reader/read-string content)]
            (swap! grammar-cache assoc filename grammar)
            grammar))))))

;; ── Public API ────────────────────────────────────────────────────────────────

(defn get-grammar
  "Look up a grammar by language name (case-insensitive).
   Lazy-loads the grammar EDN from disk on first access, then caches.
   Returns nil if unknown."
  [lang]
  (when lang
    (when-let [registry (load-registry)]
      (when-let [filename (get registry (-> lang .toLowerCase .trim))]
        (load-grammar-file filename)))))
