(ns xi.ext.treesitter.parse
  "Parse source files with tree-sitter (web-tree-sitter, WASM, in-process) and
   work with the JSON-shaped parse tree.

   Everything lives in one directory — $XI_TREESITTER_DIR, else
   resources/treesitter next to the compiled script (falling back to the cwd):

     web-tree-sitter.cjs, web-tree-sitter.wasm    the runtime
     grammars/<lang>.wasm                         one file per language

   Loading is asynchronous (`ready!`); once it has finished `parse-file-sync`
   works too. Before that it returns nil, like a missing grammar does.

   Nodes are kept as raw JS objects for speed (trees for large files reach
   hundreds of thousands of nodes):
     {\"t\" type, \"sr\"/\"er\" 0-based start/end row, \"sb\"/\"eb\" byte range,
      \"a\" 1 when anonymous, \"f\" field name, \"c\" children}
   Node text is sliced out of the source Buffer via sb/eb (UTF-8 byte offsets,
   so multi-byte text stays correct — web-tree-sitter counts UTF-16 units and
   `byte-offsets` translates)."
  (:require [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as node-path]))

;; ── Discovery ────────────────────────────────────────────────────────────────

(defn root-dir []
  (or (aget js/process.env "XI_TREESITTER_DIR")
      (let [candidates (cond-> []
                         (exists? js/__dirname)
                         (conj (.resolve node-path js/__dirname ".." "resources" "treesitter"))
                         true
                         (conj (.resolve node-path (.cwd js/process) "resources" "treesitter")))]
        (or (some #(when (fs/existsSync (node-path/join % "web-tree-sitter.cjs")) %) candidates)
            (last candidates)))))

(defn runtime-path [] (node-path/join (root-dir) "web-tree-sitter.cjs"))
(defn grammars-dir [] (node-path/join (root-dir) "grammars"))

(defn available?
  "True when the runtime and the grammar dir exist."
  []
  (and (fs/existsSync (runtime-path))
       (fs/existsSync (grammars-dir))))

(defn grammar?
  "True when <lang>.wasm is installed."
  [lang]
  (fs/existsSync (node-path/join (grammars-dir) (str lang ".wasm"))))

;; ── Loading ──────────────────────────────────────────────────────────────────

(defonce ^:private loaded (atom nil))         ; {:Parser <class> :langs {name Language}}
(defonce ^:private loading (atom nil))        ; the memoized `ready!` promise

(defn- grammar-names []
  (into [] (keep #(second (re-matches #"(.+)\.wasm" %)))
        (array-seq (fs/readdirSync (grammars-dir)))))

(defn- load-all! [^js ts]
  (let [^js Parser (.-Parser ts)
        ^js Language (.-Language ts)]
    (-> (.init Parser #js {:locateFile (fn [name _dir] (node-path/join (root-dir) name))})
        (.then (fn [_]
                 (js/Promise.all
                  (clj->js
                   (for [l (grammar-names)]
                     (-> (.load Language (node-path/join (grammars-dir) (str l ".wasm")))
                         (.then (fn [lang] [l lang]))
                         ;; one broken grammar must not take the others down
                         (.catch (fn [_] nil))))))))
        (.then (fn [pairs]
                 (reset! loaded {:Parser Parser
                                 :langs (into {} (remove nil?) (array-seq pairs))})
                 true)))))

(defn ready!
  "Load the WASM runtime and every grammar. → Promise of true when parsing is
   possible, false when the runtime is missing or failed to load. Idempotent:
   the first call starts the load, later calls share it."
  []
  (or @loading
      (reset! loading
              (if-not (available?)
                (js/Promise.resolve false)
                (-> (js/Promise.resolve)
                    (.then (fn [_] (load-all! (js/require (runtime-path)))))
                    (.catch (fn [_] false)))))))

;; ── Parsing ──────────────────────────────────────────────────────────────────

(defn- byte-offsets
  "UTF-16 index → UTF-8 byte offset table for `text`."
  [^js text]
  (let [n (.-length text)
        m (js/Uint32Array. (inc n))]
    (loop [i 0 b 0]
      (if (< i n)
        (let [c (.charCodeAt text i)]
          (aset m i b)
          (cond
            (< c 0x80)  (recur (inc i) (+ b 1))
            (< c 0x800) (recur (inc i) (+ b 2))
            ;; surrogate pair: one 4-byte code point
            (and (>= c 0xd800) (< c 0xdc00))
            (do (aset m (inc i) (+ b 4))
                (recur (+ i 2) (+ b 4)))
            :else       (recur (inc i) (+ b 3))))
        (do (aset m n b) m)))))

(defn- walk-node
  "The cursor's current node and everything below it, as the JSON-shaped
   object. `off` maps a UTF-16 index to a byte offset."
  [^js cursor off]
  (let [^js n (.-currentNode cursor)
        ^js sp (.-startPosition n)
        ^js ep (.-endPosition n)
        o #js {"t" (.-type n)
               "sr" (.-row sp)
               "er" (.-row ep)
               "sb" (off (.-startIndex n))
               "eb" (off (.-endIndex n))}]
    (when-not (.-isNamed n) (aset o "a" 1))
    (when-let [f (.-currentFieldName cursor)] (aset o "f" f))
    (when (.gotoFirstChild cursor)
      (let [cs #js []]
        (loop []
          (.push cs (walk-node cursor off))
          (when (.gotoNextSibling cursor) (recur)))
        (.gotoParent cursor)
        (aset o "c" cs)))
    o))

(defn- parse-buffer
  "JS root node of `buf` parsed as `lang`, or nil when the runtime isn't loaded,
   the grammar is unknown, or the parse fails."
  [lang ^js buf]
  (when-let [{:keys [^js Parser langs]} @loaded]
    (when-let [language (get langs lang)]
      (let [text (.toString buf "utf8")
            off  (if (= (.-length buf) (.-length text))
                   identity
                   (let [m (byte-offsets text)] (fn [i] (aget m i))))
            parser (Parser.)]
        (try
          (.setLanguage parser language)
          (when-let [^js tree (.parse parser text)]
            (try
              (let [^js cursor (.walk tree)]
                (try (walk-node cursor off)
                     (finally (.delete cursor))))
              (finally (.delete tree))))
          (finally (.delete parser)))))))

(defn parse-file
  "Parse `path` with the grammar for `lang`. → Promise of the JS root node."
  [lang path]
  (-> (ready!)
      (.then (fn [ok]
               (or (when ok (parse-buffer lang (fs/readFileSync path)))
                   (throw (js/Error. (str "tree-sitter: cannot parse " path " as " lang))))))))

(defn parse-file-sync
  "Parse `path` with the grammar for `lang` synchronously → JS root node, or nil
   on any failure (runtime not loaded yet, missing grammar, parse error). Used
   by the rules store's `:node` consult, which runs inside the synchronous
   matcher pipeline — so the load is started here if nothing has started it,
   and until it finishes a `:node` rule simply doesn't match."
  [lang path]
  (when-not @loaded (ready!))
  (try
    (parse-buffer lang (fs/readFileSync path))
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
