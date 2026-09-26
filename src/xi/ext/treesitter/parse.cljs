(ns xi.ext.treesitter.parse
  "Run the native xi-treesitter CLI (native/xi-treesitter) and work with the
   JSON parse tree it emits.

   The CLI is discovered at $XI_TREESITTER_DIR (default
   ~/.config/xi/treesitter) which must contain bin/xi-treesitter and
   grammars/<lang>.so — the layout produced by
   `nix-build native/xi-treesitter -o ~/.config/xi/treesitter`.

   Nodes are kept as raw JS objects for speed (trees for large files reach
   hundreds of thousands of nodes):
     {\"t\" type, \"sr\"/\"er\" 0-based start/end row, \"sb\"/\"eb\" byte range,
      \"a\" 1 when anonymous, \"f\" field name, \"c\" children}
   Node text is sliced out of the source Buffer via sb/eb (byte offsets, so
   multi-byte UTF-8 stays correct)."
  (:require [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as node-path]
            ["node:child_process" :as cp]))

;; ── CLI discovery ────────────────────────────────────────────────────────────

(defn root-dir []
  (or (aget js/process.env "XI_TREESITTER_DIR")
      (node-path/join (or (aget js/process.env "HOME") "") ".config" "xi" "treesitter")))

(defn bin-path [] (node-path/join (root-dir) "bin" "xi-treesitter"))
(defn grammars-dir [] (node-path/join (root-dir) "grammars"))

(defn available?
  "True when the CLI binary and grammar dir exist."
  []
  (and (fs/existsSync (bin-path))
       (fs/existsSync (grammars-dir))))

(defn grammar?
  "True when <lang>.so is installed."
  [lang]
  (fs/existsSync (node-path/join (grammars-dir) (str lang ".so"))))

(defn parse-file
  "Parse `path` with the grammar for `lang`. → Promise of the JS root node."
  [lang path]
  (js/Promise.
   (fn [resolve reject]
     (cp/execFile (bin-path) #js [(grammars-dir) lang path]
                  #js {:maxBuffer (* 128 1024 1024)}
                  (fn [err stdout _stderr]
                    (if err
                      (reject err)
                      (try
                        (resolve (js/JSON.parse stdout))
                        (catch :default e (reject e)))))))))

(defn parse-file-sync
  "Parse `path` with the grammar for `lang` synchronously → JS root node, or nil
   on any failure (missing CLI/grammar, parse error). Used by the rules store's
   `:node` consult, which runs inside the synchronous matcher pipeline."
  [lang path]
  (try
    (let [out (cp/execFileSync (bin-path) #js [(grammars-dir) lang path]
                               #js {:maxBuffer (* 128 1024 1024)})]
      (js/JSON.parse (.toString out "utf8")))
    (catch :default _ nil)))

;; ── Node accessors ───────────────────────────────────────────────────────────

(defn node-type [^js n] (.-t n))
(defn anon? [^js n] (boolean (.-a n)))
(defn field [^js n] (.-f n))

(defn children [^js n] (if n (or (.-c n) #js []) #js []))

(defn named-children [n]
  (into [] (remove anon?) (array-seq (children n))))

(defn child-by-field [n f]
  (some #(when (= f (field %)) %) (array-seq (children n))))

(defn child-of-type [n t]
  (some #(when (= t (node-type %)) %) (array-seq (children n))))

(defn children-of-type [n t]
  (filterv #(= t (node-type %)) (array-seq (children n))))

(defn start-line
  "1-based first line of the node."
  [^js n]
  (inc (.-sr n)))

(defn end-line
  "1-based last line that contains node content. When a node ends exactly at
   a line start (trailing newline), tree-sitter reports the next row — detect
   that via the source Buffer and step back."
  [^js src ^js n]
  (let [sr (.-sr n) er (.-er n) eb (.-eb n)]
    (if (and (> er sr) (pos? eb) (= 10 (aget src (dec eb))))
      er
      (inc er))))

(defn node-text
  "Literal text of a node, sliced from the source Buffer by byte range."
  [^js src ^js n]
  (.toString (.subarray src (.-sb n) (.-eb n)) "utf8"))

;; ── Text helpers ─────────────────────────────────────────────────────────────

(defn compact-ws [s]
  (str/trim (str/replace s #"\s+" " ")))

(defn truncate
  "Cap `s` at `max` chars, appending [truncated] like maki does."
  [s max]
  (if (<= (count s) max)
    s
    (str (subs s 0 (clojure.core/max 0 (- max 11))) "[truncated]")))

(defn text-before-child
  "Compacted node text up to (excluding) one of its child nodes — for grammars
   whose body/block is not a named field."
  [^js src ^js n ^js child]
  (compact-ws (.toString (.subarray src (.-sb n) (.-sb child)) "utf8")))

(defn sig-before-body
  "The node's text up to its `body` field — a language-agnostic signature
   (works for fns, classes, impls, traits, …). Falls back to full node text."
  [^js src ^js n]
  (if-let [^js body (child-by-field n "body")]
    (compact-ws (.toString (.subarray src (.-sb n) (.-sb body)) "utf8"))
    (compact-ws (node-text src n))))
