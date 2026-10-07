(ns xi.ext.clj-surgeon
  "clj-surgeon extension — structural Clojure refactoring via AST.
   Wraps the vendored babashka CLI as xi tools.

   Also auto-fixes parens after any write/edit to a Clojure file: rather
   than a side-effecting tool hook, it chains onto :agent/tool-result,
   inspects the just-finished tool-call in history, and emits a
   :clj-surgeon/parmezan effect when the edit targeted a Clojure file."
  (:require [clojure.string :as str]
            [xi.core.state :as state]
            [xi.tools.fs :as tfs]
            [xi.tools.util :as tutil]
            ["node:path" :as node-path]
            ["node:fs" :as fs]))

;; ── CLI Resolution ────────────────────────────────────────────────────────────

(defn- find-xi-root
  "Walk up from the main script's directory to find xi's project root.
   Looks for package.json."
  []
  (let [script-path (aget js/process.argv 1)
        start-dir (when script-path (.dirname node-path (.resolve node-path script-path)))]
    (when start-dir
      (loop [dir start-dir]
        (let [pkg (.join node-path dir "package.json")]
          (cond
            (fs/existsSync pkg) dir
            (= dir (.dirname node-path dir)) nil
            :else (recur (.dirname node-path dir))))))))

(def ^:private LIB_PATH
  "Path to the clj-surgeon source."
  (let [root (find-xi-root)]
    (when root
      (.join node-path root "src" "xi" "ext" "clj_surgeon"))))

(defn- clj-surgeon-available?
  "Check if clj-surgeon source is present."
  []
  (and LIB_PATH
       (fs/existsSync (.join node-path LIB_PATH "core.clj"))))

;; ── CLI Execution ─────────────────────────────────────────────────────────────

(defn- run-clj-surgeon
  "Run a clj-surgeon operation via babashka. Returns promise of tool result.
   args: vector of CLI arguments (e.g. [\":op\" \":ls\" \":file\" \"src/foo.clj\"])"
  [args cwd]
  (if-not (clj-surgeon-available?)
    (js/Promise.resolve {:content [{:type "text" :text "clj-surgeon not available (vendor/clj-surgeon not found)"}]
                         :is-error true})
    (js/Promise.
     (fn [resolve _reject]
       (let [cmd-args (into ["bb" "-cp" (.dirname node-path LIB_PATH)
                             "-m" "clj-surgeon.core"]
                            args)
             proc (js/Bun.spawn
                   (clj->js cmd-args)
                   #js {:stdout "pipe"
                        :stderr "pipe"
                        :cwd (or cwd (.cwd js/process))})
             timer (js/setTimeout
                    (fn []
                      (.kill proc)
                      (resolve {:content [{:type "text"
                                           :text "clj-surgeon timed out after 30s"}]
                                :is-error true}))
                    30000)]
         (-> (js/Promise.all
              #js [(.text (.-stdout proc))
                   (.text (.-stderr proc))
                   (.-exited proc)])
             (.then (fn [results]
                      (js/clearTimeout timer)
                      (let [stdout (aget results 0)
                            stderr (aget results 1)
                            code (aget results 2)
                            output (cond-> ""
                                     (seq stdout) (str stdout)
                                     (and (seq stderr) (not= 0 code))
                                     (str (when (seq stdout) "\n") "STDERR: " stderr))]
                        (resolve
                         (if (= 0 code)
                           {:content [{:type "text" :text (if (seq output) output "(no output)")}]}
                           {:content [{:type "text"
                                       :text (str (if (seq output) output "clj-surgeon failed")
                                                  "\nExit code: " code)}]
                            :is-error true})))))))))))

;; ── Argument helper ───────────────────────────────────────────────────────────

(defn- build-args
  "Convert a map of parameters to clj-surgeon CLI args.
   Filters out nil values."
  [params]
  (reduce-kv
   (fn [acc k v]
     (if (some? v)
       (conj acc (str ":" (name k)) (str v))
       acc))
   []
   params))

;; ── Parmezan (paren fixing) ───────────────────────────────────────────────────

(defn- clojure-file? [path]
  (some #(str/ends-with? (str path) %)
        [".clj" ".cljs" ".cljc" ".edn" ".bb"]))

(defn- run-parmezan
  "Run parmezan on a file. Returns promise of tool result."
  [file cwd]
  (js/Promise.
   (fn [resolve _reject]
     (let [proc (js/Bun.spawn
                 #js ["parmezan" file]
                 #js {:stdout "pipe"
                      :stderr "pipe"
                      :cwd (or cwd (.cwd js/process))})
           timer (js/setTimeout
                  (fn []
                    (.kill proc)
                    (resolve {:content [{:type "text" :text "parmezan timed out"}]
                              :is-error true}))
                  10000)]
       (-> (js/Promise.all
            #js [(.text (.-stdout proc))
                 (.text (.-stderr proc))
                 (.-exited proc)])
           (.then (fn [results]
                    (js/clearTimeout timer)
                    (let [stdout (aget results 0)
                          stderr (aget results 1)
                          code (aget results 2)
                          output (str (when (seq stdout) stdout)
                                      (when (seq stderr) (str "\n" stderr)))]
                      (resolve
                       (if (= 0 code)
                         {:content [{:type "text" :text (if (seq output)
                                                          (str "Fixed: " file "\n" output)
                                                          (str "OK: " file " (no changes needed)"))}]}
                         {:content [{:type "text"
                                     :text (str "parmezan failed on " file
                                                (when (seq output) (str "\n" output)))}]
                          :is-error true}))))))))))

(defn- run-replace
  "Run clj-replace structural replacement. Returns promise of tool result.
   On success the result text includes a unified diff of the change (same
   format as the edit tool) so clients render it as a diff."
  [file old-str new-str cwd]
  (let [script (.join node-path LIB_PATH "replace.clj")
        resolved (tfs/resolve-path file (or cwd (.cwd js/process)))
        before (when (tfs/file-exists? resolved)
                 (fs/readFileSync resolved "utf8"))]
    (js/Promise.
     (fn [resolve _reject]
       (let [proc (js/Bun.spawn
                   #js ["bb" script file old-str new-str]
                   #js {:stdout "pipe"
                        :stderr "pipe"
                        :cwd (or cwd (.cwd js/process))})
             timer (js/setTimeout
                    (fn []
                      (.kill proc)
                      (resolve {:content [{:type "text" :text "clj-replace timed out"}]
                                :is-error true}))
                    15000)]
         (-> (js/Promise.all
              #js [(.text (.-stdout proc))
                   (.text (.-stderr proc))
                   (.-exited proc)])
             (.then (fn [results]
                      (js/clearTimeout timer)
                      (let [stdout (aget results 0)
                            stderr (aget results 1)
                            code (aget results 2)
                            output (str (when (seq stdout) stdout)
                                        (when (seq stderr) stderr))]
                        (resolve
                         (if (= 0 code)
                           (let [after (when (tfs/file-exists? resolved)
                                         (fs/readFileSync resolved "utf8"))
                                 diff (when (and before after (not= before after))
                                        (tutil/unified-diff before after))
                                 base (if (seq output)
                                        (str/trim-newline output)
                                        "Replaced successfully")]
                             {:content [{:type "text"
                                         :text (if (seq diff)
                                                 (str base "\n" diff)
                                                 base)}]})
                           {:content [{:type "text" :text (or output "clj-replace failed")}]
                            :is-error true})))))))))))

;; ── Tool exec fns ──────────────────────────────────────────────────────────

(defn- clj-outline [{:keys [file]} {:keys [cwd]}]
  (or (tfs/assert-file-exists file cwd)
      (run-clj-surgeon (build-args {:op ":ls" :file file}) cwd)))

(defn- clj-tree [{:keys [dir grep]} {:keys [cwd]}]
  (run-clj-surgeon (build-args {:op ":ls-tree" :dir (or dir ".") :grep grep}) cwd))

(defn- clj-deps [{:keys [file form]} {:keys [cwd]}]
  (or (tfs/assert-file-exists file cwd)
      (run-clj-surgeon (build-args {:op ":ls-deps" :file file :form form}) cwd)))

(defn- clj-extract [{:keys [file forms to execute]} {:keys [cwd]}]
  (or (tfs/assert-file-exists file cwd)
      (run-clj-surgeon (build-args {:op (if execute ":extract!" ":extract")
                                    :file file :forms forms :to to})
                       cwd)))

(defn- clj-fix-declares [{:keys [file execute]} {:keys [cwd]}]
  (or (tfs/assert-file-exists file cwd)
      (run-clj-surgeon (build-args {:op (if execute ":fix-declares!" ":fix-declares")
                                    :file file})
                       cwd)))

(defn- clj-mv [{:keys [file form before dry_run]} {:keys [cwd]}]
  (or (tfs/assert-file-exists file cwd)
      (run-clj-surgeon (build-args {:op ":mv" :file file :form form :before before
                                    :dry-run (when dry_run "true")})
                       cwd)))

(defn- clj-rename-ns [{:keys [from to root execute]} {:keys [cwd]}]
  (run-clj-surgeon (build-args {:op (if execute ":rename-ns!" ":rename-ns")
                                :from from :to to :root (or root ".")})
                   cwd))

(defn- clj-topo [{:keys [file]} {:keys [cwd]}]
  (or (tfs/assert-file-exists file cwd)
      (run-clj-surgeon (build-args {:op ":topo" :file file}) cwd)))

(defn- clj-replace [{:keys [file old_str new_str]} {:keys [cwd]}]
  (or (tfs/assert-file-exists file cwd)
      (run-replace file old_str new_str cwd)))

(defn- clj-fix-parens [{:keys [file]} {:keys [cwd]}]
  (or (tfs/assert-file-exists file cwd)
      (run-parmezan file cwd)))

(def ^:private tool-defs
  [{:name "clj_outline"
    :description "Outline a Clojure file's structure — every top-level form with line boundaries, types, names, arglists, and forward-ref detection. Use BEFORE reading large .clj/.cljs/.cljc files (~50 tokens vs thousands). Returns EDN."
    :input_schema {:type "object"
                   :properties {:file {:type "string" :description "Path to .clj/.cljs/.cljc file"}}
                   :required ["file"]}}
   {:name "clj_tree"
    :description "Map an entire project's namespace tree — discovers projects via deps.edn/project.clj/bb.edn, outlines every source file. With :grep, searches across repos in seconds."
    :input_schema {:type "object"
                   :properties {:dir  {:type "string" :description "Directory to scan (default: .)"}
                                :grep {:type "string" :description "Optional regex pattern to filter files via ripgrep"}}
                   :required []}}
   {:name "clj_deps"
    :description "Show the transitive dependency tree of a form within a file. Shows which deps are leaves (safe to move), which have sub-deps, and which are circular."
    :input_schema {:type "object"
                   :properties {:file {:type "string" :description "Path to .clj file"}
                                :form {:type "string" :description "Form name to analyze"}}
                   :required ["file" "form"]}}
   {:name "clj_extract"
    :description "Extract forms to a new namespace. Creates new file with forms in topological order, removes from source, adds require. Use dry-run first (default), then pass execute=true."
    :input_schema {:type "object"
                   :properties {:file    {:type "string" :description "Source file path"}
                                :forms   {:type "string" :description "EDN vector of form names, e.g. '[foo bar baz]'"}
                                :to      {:type "string" :description "Destination file path"}
                                :execute {:type "boolean" :description "If true, write changes. Default is dry-run."}}
                   :required ["file" "forms" "to"]}}
   {:name "clj_fix_declares"
    :description "Eliminate unnecessary (declare ...) forms by reordering defns topologically. Use dry-run first (default), then pass execute=true."
    :input_schema {:type "object"
                   :properties {:file    {:type "string" :description "Path to .clj file"}
                                :execute {:type "boolean" :description "If true, write changes. Default is dry-run."}}
                   :required ["file"]}}
   {:name "clj_mv"
    :description "Reorder a form within a Clojure file — move a defn before another defn."
    :input_schema {:type "object"
                   :properties {:file    {:type "string" :description "Path to .clj file"}
                                :form    {:type "string" :description "Name of form to move"}
                                :before  {:type "string" :description "Name of form to place it before"}
                                :dry_run {:type "boolean" :description "If true, show plan without writing. Default false."}}
                   :required ["file" "form" "before"]}}
   {:name "clj_rename_ns"
    :description "Rename a namespace prefix across all files — structural AST rename (not text replace). Dry-run by default."
    :input_schema {:type "object"
                   :properties {:from    {:type "string" :description "Old namespace prefix"}
                                :to      {:type "string" :description "New namespace prefix"}
                                :root    {:type "string" :description "Project root (default: .)"}
                                :execute {:type "boolean" :description "If true, write changes. Default is dry-run."}}
                   :required ["from" "to"]}}
   {:name "clj_topo"
    :description "Topological sort of all forms in a file — shows the optimal ordering to eliminate forward references."
    :input_schema {:type "object"
                   :properties {:file {:type "string" :description "Path to .clj file"}}
                   :required ["file"]}}
   {:name "clj_replace"
    :description "Structural S-expression replacement in a Clojure file. Matches by code structure (ignoring whitespace/formatting), not text. old_str and new_str must each be complete, balanced forms — partial fragments (a few lines from inside a form) fail to parse; use the text edit tool for those. Use when edit tool fails due to formatting differences."
    :input_schema {:type "object"
                   :properties {:file    {:type "string" :description "Path to .clj/.cljs/.cljc file"}
                                :old_str {:type "string" :description "ONE complete, balanced Clojure form to find (matched structurally, not by text). Not a fragment."}
                                :new_str {:type "string" :description "Clojure expression to replace it with"}}
                   :required ["file" "old_str" "new_str"]}}
   {:name "clj_fix_parens"
    :description "Fix unbalanced parentheses/brackets/braces in a Clojure file using parmezan. Automatically infers the correct delimiters from context."
    :input_schema {:type "object"
                   :properties {:file {:type "string" :description "Path to .clj/.cljs/.cljc/.edn file"}}
                   :required ["file"]}}])

;; ── Auto-lint hook ─────────────────────────────────────────────────────────

(defn- auto-lint
  "Chained onto :agent/tool-result — after a successful write/edit to a
   Clojure file, fix parens in the background. The base handler has already
   stored the result on the tool-call entry, so we look it up by id to read
   the tool name and the edited path."
  [st {:keys [room-id id is-error]}]
  (when (and (not is-error) (state/get-room st room-id))
    (let [entry (->> (get-in st [:rooms room-id :history])
                     (filter #(and (= :tool-call (:kind %)) (= id (:id %))))
                     last)
          tool  (:tool entry)
          path  (get-in entry [:arguments :path])]
      (when (and (contains? #{"write" "edit"} tool) (clojure-file? path))
        {:effects [[:clj-surgeon/parmezan {:path path
                                           :cwd  (get-in st [:rooms room-id :cwd])}]]}))))

(defn- parmezan-fx
  "Fire-and-forget parmezan on the edited file."
  [_ {:keys [path cwd]}]
  (try
    (js/Bun.spawn #js ["parmezan" path]
                  #js {:stdout "ignore" :stderr "ignore"
                       :cwd (or cwd (.cwd js/process))})
    (catch :default _ nil)))

;; ── Extension ─────────────────────────────────────────────────────────────

(def ^:private CLOJURE_EDIT_PROMPT
  (str "## Editing Clojure\n"
       "For .clj/.cljs/.cljc/.edn files, prefer the structural clj-surgeon "
       "tools over edit/write. clj_replace matches by code structure (ignoring "
       "whitespace and formatting) and is scoped to a form, so it is far more "
       "robust than exact-text edit: it survives reformatting and concurrent "
       "edits elsewhere in the file, and two agents editing different forms "
       "never collide (a conflicting same-form edit fails the match cleanly "
       "instead of clobbering). Use clj_replace to change a form, "
       "clj_mv/clj_extract/clj_fix_declares to move or reorganize forms, and "
       "clj_rename_ns for namespace renames. Fall back to edit only for "
       "non-structural text (comments, strings, docstrings), partial fragments of "
       "a form (clj_replace only takes one complete, balanced form), or when "
       "clj_replace cannot match."))

(def extension
  {:id               :clj-surgeon
   :system-prompt    CLOJURE_EDIT_PROMPT
   :handlers         {:agent/tool-result auto-lint}
   :fx               {:clj-surgeon/parmezan parmezan-fx}
   :tool-definitions tool-defs
   :tool-registry    {"clj_outline"      clj-outline
                      "clj_tree"         clj-tree
                      "clj_deps"         clj-deps
                      "clj_extract"      clj-extract
                      "clj_fix_declares" clj-fix-declares
                      "clj_mv"           clj-mv
                      "clj_rename_ns"    clj-rename-ns
                      "clj_topo"         clj-topo
                      "clj_replace"      clj-replace
                      "clj_fix_parens"   clj-fix-parens}})
