(ns xi.client.view
  "TUI view layer — builds component blocks from history entries.

   A block is the rendered form of one history entry:

     {:nodes   [component ...]        ;; what goes into the chat container
      :update! (fn [old-entry new-entry] bool)}  ;; absorb an in-place
                                                 ;; change (streaming delta,
                                                 ;; tool result); false →
                                                 ;; caller rebuilds the block

   Blocks are memoized per entry by xi.client.tui — since history entries
   are immutable values that only change at the tail while streaming, a
   render pass usually touches one block. All tool-arg formatting and
   syntax/diff highlighting knowledge is ported from master's client/tui."
  (:require-macros [xi.config-macros :refer [deftui-opt]])
  (:require [clojure.string :as str]
            [xi.clj-result :as clj-result]
            [xi.config]
            [xi.highlight.core :as hl]
            [xi.highlight.embedded :as embedded]
            [xi.highlight.grammars :as hl-grammars]
            [xi.highlight.theme :as hl-theme]
            [xi.tui.ansi :as ansi]
            [xi.tui.components :as comp]
            [xi.tui.core :as tui]
            [xi.tui.markdown :as md]
            [xi.tui.node :as node]
            [xi.util :as util]))

;; ── Tool call formatting ─────────────────────────────────────────────────────

(defn- get-arg
  "Get an argument by key, trying both string and keyword forms."
  [arguments k]
  (or (get arguments (name k))
      (get arguments k)))

(defn- arg-file-path
  "File-path argument, trying Claude's :file_path and Xi's :path."
  [arguments]
  (or (get-arg arguments :file_path)
      (get-arg arguments :path)))

(def ^:private display-tool-name
  "Map internal tool names to nicer display names."
  {"git_overview"                  "git diff --stat"
   "git_file_diff"                 "git diff"
   "git_hunk"                      "git diff"
   "git_stage_hunks"               "git add"
   "git_commit"                    "git commit"})

(defn- tool-display-name [tool-name]
  (or (display-tool-name tool-name) tool-name))

(defn- format-tool-args-default
  "Fallback: show key=value pairs for the first few short arguments."
  [arguments]
  (when (and arguments (or (map? arguments) (object? arguments)))
    (let [entries (if (object? arguments)
                    (map (fn [k] [k (unchecked-get arguments k)])
                         (js/Object.keys arguments))
                    (seq arguments))
          pairs (->> entries
                     (keep (fn [[k v]]
                             (when (and (some? v) (string? v) (<= (count v) 200))
                               (str (name k) "=" (first (str/split-lines v))))))
                     (take 3))]
      (when (seq pairs)
        (str/join " " pairs)))))

(defn format-tool-args
  "Format tool arguments for display in the tool header. Expects a canonical
   (lowercase) tool name — see `canonical-tool`."
  [tool-name arguments]
  (case tool-name
    "bash"  (get-arg arguments :command)
    "clj"   (get-arg arguments :code)
    ("read" "write" "edit") (arg-file-path arguments)
    "ls"    (get-arg arguments :path)
    "grep"  (str (get-arg arguments :pattern)
                 (when-let [g (get-arg arguments :glob)]
                   (str " --glob " g)))
    ("glob" "find") (get-arg arguments :pattern)
    "agent" (or (get-arg arguments :description)
                (get-arg arguments :prompt)
                (get-arg arguments :task))
    "git_overview"    (if (get-arg arguments :staged) "--staged" nil)
    "git_file_diff"   (str/join " " (get-arg arguments :files))
    "git_hunk"        (get-arg arguments :file)
    "git_stage_hunks" (str/join " " (get-arg arguments :files))
    "git_commit" (get-arg arguments :message)
    (format-tool-args-default arguments)))

(def ^:private shorten-tool-name util/strip-mcp-prefix)

(defn- canonical-tool
  "Provider-agnostic tool key: MCP prefix stripped, lowercased. Claude's
   capitalized built-ins (\"Read\", \"Bash\") and Xi's lowercase tools
   (\"read\", \"bash\") collapse to a single key."
  [tool-name]
  (some-> tool-name shorten-tool-name str/lower-case))

(def ^:private truncate util/truncate)

(def ^:private collapsed-tools
  "Canonical (lowercase) tool names whose output is hidden by default in the TUI."
  #{"read" "clj_outline"})

(deftui-opt truncate-output-block-after-n-lines 100
  "Max number of tool-output lines rendered in a tool block before the
   remainder is collapsed into a \"... (N more lines)\" marker.
   Overridable via :truncate-output-block-after-n-lines in xi.config/tui.")

(defn- truncate-output
  "Truncate tool output to max lines."
  [text max-lines]
  (let [lines (str/split-lines text)]
    (if (<= (count lines) max-lines)
      text
      (str (str/join "\n" (take max-lines lines))
           "\n" (ansi/fg :dim (str "... (" (- (count lines) max-lines) " more lines)"))))))

(defn- file-ext [path]
  (when (and path (str/includes? path "."))
    (-> path (str/split #"\.") last str/lower-case)))

(defn- tool-output-lang
  "Determine the highlighting grammar for a tool's output, or nil.
   Expects a canonical (lowercase) tool name."
  [tool-name arguments]
  (let [path (case tool-name
               ("read" "write" "edit" "read_source") (arg-file-path arguments)
               ("git_hunk" "clj_replace") (get-arg arguments :file)
               "git_file_diff" (let [files (get-arg arguments :files)]
                                 (when (= 1 (count files)) (first files)))
               nil)]
    (when path
      (hl-grammars/get-grammar (file-ext path)))))

;; ── Syntax / diff highlighting ───────────────────────────────────────────────

;; Diff line background colors — blended with the tool block bg. Mode-aware
;; (light/dark) via the syntax theme; see xi.highlight.theme.

;; ── Difftastic retint ────────────────────────────────────────────────────────
;; Difftastic emits 16-color palette codes (bright red/green/yellow + bold/dim)
;; whose look is dictated by the terminal palette, so they clash with the TUI's
;; truecolor theme. Rewrite them to theme colors — the same red/green/header/dim
;; mapping the web difft view applies via CSS (difft-del/difft-add/difft-hdr).

(def ^:private difft-del-fg "\033[38;2;191;97;106m")   ;; danger red
(def ^:private difft-add-fg "\033[38;2;163;190;140m")  ;; string green
(def ^:private difft-hdr-fg "\033[38;2;216;222;233m")  ;; bright fg (filename)
(def ^:private difft-dim-fg "\033[38;2;106;115;141m")  ;; muted gray-blue

(defn- difft-sgr-state
  "Fold one SGR escape's `;`-separated codes into the running style state.
   Only the codes difftastic emits (syntax-highlight off) are meaningful."
  [state codes]
  (reduce (fn [st code]
            (case code
              ("" "0")     {}
              "1"          (assoc st :bold true)
              "2"          (assoc st :dim true)
              "22"         (dissoc st :bold :dim)
              ("31" "91")  (assoc st :fg :red)
              ("32" "92")  (assoc st :fg :green)
              ("33" "93")  (assoc st :fg :yellow)
              "39"         (dissoc st :fg)
              st))
          state
          (str/split (or codes "") #";")))

(defn- difft-prefix
  "Theme ANSI codes for a difftastic style state (empty string when unstyled)."
  [{:keys [fg bold dim]}]
  (str (cond
         (= fg :red)    difft-del-fg
         (= fg :green)  difft-add-fg
         (= fg :yellow) difft-hdr-fg
         dim            difft-dim-fg)
       (when bold ansi/bold)))

(defn retint-difft
  "Rewrite difftastic's palette-colored output into the TUI theme palette so
   `/diff difft` reads like the rest of the UI (and the web difft view)."
  [text]
  (let [re (js/RegExp. "\\u001b\\[([0-9;]*)m" "g")]
    (loop [pos 0 state {} out ""]
      (if-let [m (.exec re text)]
        (let [idx    (.-index m)
              chunk  (subs text pos idx)
              prefix (difft-prefix state)
              out'   (str out (if (and (seq chunk) (seq prefix))
                                (str prefix chunk ansi/reset)
                                chunk))]
          (recur (+ idx (.-length (aget m 0)))
                 (difft-sgr-state state (aget m 1))
                 out'))
        (str out (subs text pos))))))

(defn- highlight-line [grammar line]
  (let [tokens (-> (hl/tokenize grammar line) hl/merge-adjacent)]
    (hl-theme/colorize tokens)))

(defn- highlight-diff-text
  "Highlight text containing a unified diff: strip +/- prefix before
   tokenizing, then re-apply the diff bg after any reset sequences so the
   bg survives syntax coloring."
  [grammar text]
  (->> (str/split-lines text)
       (mapv (fn [line]
               (cond
                 (str/starts-with? line "+ ")
                 (let [diff-add-bg (hl-theme/diff-add-bg)
                       code (highlight-line grammar (subs line 2))
                       patched (str/replace code ansi/reset (str ansi/reset diff-add-bg))]
                   (str diff-add-bg "+ " patched))

                 (str/starts-with? line "- ")
                 (let [diff-del-bg (hl-theme/diff-del-bg)
                       code (highlight-line grammar (subs line 2))
                       patched (str/replace code ansi/reset (str ansi/reset diff-del-bg))]
                   (str diff-del-bg "- " patched))

                 (str/starts-with? line "  ")
                 (str "  " (highlight-line grammar (subs line 2)))

                 (= line "...")
                 (ansi/fg :dim "...")

                 :else line)))
       (str/join "\n")))

(defn diff-preview-text
  "A confirm dialog's :diff preview ({:path :text}) as highlighted, truncated
   TUI text — the same rendering as the edit tool's result diff, capped at
   `max-lines`."
  [{:keys [path text]} max-lines]
  (let [grammar (some-> path file-ext hl-grammars/get-grammar)]
    (truncate-output (if grammar
                       (highlight-diff-text grammar text)
                       text)
                     max-lines)))

(defn- diff-output?
  "Does tool output look like a unified diff (from the edit tool)?"
  [text]
  (let [lines (take 10 (str/split-lines text))]
    (some #(or (str/starts-with? % "+ ")
               (str/starts-with? % "- "))
          (rest lines))))

(defn- colorize-tokens
  "Merged highlight tokens → ANSI text, colored line by line."
  [tokens]
  (->> (hl/split-tokens-by-line tokens)
       (mapv hl-theme/colorize)
       (str/join "\n")))

(defn- highlight-code
  "Apply syntax highlighting to plain source text."
  [grammar text]
  (colorize-tokens (-> (hl/tokenize grammar text) hl/merge-adjacent)))

(defn- highlight-text
  "Apply syntax highlighting; auto-detects diff output."
  [grammar text]
  (if (diff-output? text)
    (highlight-diff-text grammar text)
    (highlight-code grammar text)))

(def ^:private code-hang-indent
  "Extra columns a wrapped code line's continuation hangs below its indent."
  2)

(defn- clj-code-display
  "The clj tool's code, syntax highlighted and truncated like tool output —
   rendered as its own node under the `$ clj` header (not squeezed into it)."
  [arguments]
  (when-let [code (some-> (get-arg arguments :code) str str/trimr not-empty)]
    (truncate-output (if-let [tokens (embedded/tokenize-clj hl-grammars/get-grammar code)]
                       (colorize-tokens tokens)
                       code)
                     truncate-output-block-after-n-lines)))

(defn- clj-result-display
  "clj tool result → TUI text, the twin of the web's clj-result-view: stdout
   as-is, the error in red with its line:col, and the `=>` value — clj data
   pretty-printed + highlighted, continuation lines aligned under the `=> `."
  [text is-error]
  (let [{:keys [stdout error loc] :as parsed} (clj-result/parse-clj-result text is-error)
        {value :text :keys [data?]} (clj-result/value-display parsed)
        g   (hl-grammars/get-grammar "clj")
        cap #(truncate-output % truncate-output-block-after-n-lines)]
    (->> [(when stdout (cap stdout))
          (when error
            (str (ansi/fg :error (cap error))
                 (when loc (str " " (ansi/fg :dim loc)))))
          (when value
            (str (ansi/fg :dim "=> ")
                 (str/replace (cap (if (and g data?) (highlight-code g value) value))
                              "\n" "\n   ")))]
         (remove nil?)
         (str/join "\n\n"))))

;; ── Entry blocks ─────────────────────────────────────────────────────────────

(defn- never-update [_ _] false)

(defn- user-label
  "Highlighted 'you:' prefix — an accent gutter bar plus a bold accent label —
   so the user's own prompts stand out when scanning scrollback."
  []
  (str (ansi/fg :accent "▌ ")
       (ansi/fg :accent (ansi/fg :bold "you"))
       ": "))

(defn- user-message-nodes
  "Node(s) for a user message. Renders code fences through markdown."
  [text suffix]
  (if (str/includes? text "```")
    (let [first-nl (str/index-of text "\n")
          first-line (if first-nl (subs text 0 first-nl) text)
          rest-text (when first-nl (subs text (inc first-nl)))]
      [(node/text (str (user-label) first-line suffix))
       (when rest-text
         (md/make-markdown rest-text))])
    [(node/text (str (user-label) text suffix))]))

(defn- user-block [entry]
  (let [n (or (some-> (:images entry) count)
              (:image-count entry))
        suffix (when (and n (pos? n))
                 (str " " (ansi/fg :dim (str "(📎 " n " image" (when (> n 1) "s") ")"))))]
    {:nodes (-> [(comp/make-spacer 1)]
                (into (remove nil?) (user-message-nodes (:text entry) suffix))
                (conj (comp/make-spacer 1)))
     :update! never-update}))

(defn- text-block [entry]
  (let [m (md/make-markdown (:text entry))]
    {:nodes [m (comp/make-spacer 1)]
     :update! (fn [old new]
                (when (= :text (:kind new))
                  (when (not= (:text old) (:text new))
                    ((:set-text m) (:text new)))
                  true))}))

(defn- thinking-block [entry]
  (let [t (comp/make-text (ansi/fg :dim (:text entry)))]
    {:nodes [t (comp/make-spacer 1)]
     :update! (fn [old new]
                (when (= :thinking (:kind new))
                  (when (not= (:text old) (:text new))
                    ((:set-text t) (ansi/fg :dim (:text new))))
                  true))}))

(defn- tool-header-str [tool-name args-summary]
  (let [first-line (when (seq args-summary)
                     (first (str/split-lines args-summary)))
        rest-lines (when (seq args-summary)
                     (let [lines (rest (str/split-lines args-summary))]
                       (when (seq lines)
                         (str/join "\n" (take 20 lines)))))]
    (str (ansi/fg :accent (str "$ " (tool-display-name tool-name)))
         (when first-line (str " " first-line))
         (when rest-lines
           (str "\n" (ansi/fg :dim rest-lines)
                (when (> (count (str/split-lines args-summary)) 21)
                  (str "\n" (ansi/fg :dim "..."))))))))

(defn- strip-file-hash
  "Drop the trailing [file-hash: ...] freshness token from tool output — it's
   an agent-only guard and needn't be shown in the TUI."
  [text]
  (when text
    (str/replace text #"\n?\[file-hash: [0-9a-f]+\]\s*\z" "")))

(defn- result-text
  "Extract display text from a tool result content (string or blocks)."
  [content]
  (strip-file-hash
   (cond
     (string? content) content
     (sequential? content)
     (->> content
          (keep (fn [b]
                  (cond
                    (string? b) b
                    (= "text" (:type b)) (:text b)
                    :else nil)))
          (str/join "\n"))
     :else nil)))

(defn- pretty-json
  "If `text` is a JSON object or array, return it pretty-printed with 2-space
   indentation; otherwise nil. Scalars and plain text are left to the caller,
   so only structured JSON (the typical MCP tool payload) gets reformatted."
  [text]
  (let [t (str/trim (str text))]
    (when (and (seq t) (or (str/starts-with? t "{") (str/starts-with? t "[")))
      (try
        (js/JSON.stringify (js/JSON.parse t) nil 2)
        (catch :default _ nil)))))

(defn- tool-block
  "Tool-call entry → box with header, output and (when live) spinner/timer.
   Entries that arrive already finished (resumed sessions) render statically."
  [entry]
  (let [bg-code (hl-theme/block-bg)
        box (comp/make-box {:padding-x 1 :padding-y 0 :bg-code bg-code})
        short-name (shorten-tool-name (:tool entry))
        canonical (canonical-tool (:tool entry))
        mcp? (str/starts-with? (str (:tool entry)) "mcp__")
        clj? (= "clj" canonical)
        header-args (fn [arguments]
                      (when-not clj? (format-tool-args canonical arguments)))
        header-text (comp/make-text
                     (tool-header-str short-name (header-args (:arguments entry))))
        code-text (when clj?
                    (comp/make-text (clj-code-display (:arguments entry))
                                    {:hang-indent code-hang-indent}))
        live? (= :running (:status entry))
        spinner (when live? (comp/make-spinner))
        start-time (js/Date.now)
        collapsed? (collapsed-tools canonical)
        st #js {:outputSet false :finished false
                :grammar (tool-output-lang canonical (:arguments entry))}
        ensure-grammar! (fn [arguments]
                          (when-not (.-grammar st)
                            (set! (.-grammar st) (tool-output-lang canonical arguments))))
        set-output! (fn [content is-error]
                      (when-let [text (not-empty (result-text content))]
                        ((:add-child box) (comp/make-spacer 1))
                        ((:add-child box)
                         (if clj?
                           (comp/make-text (clj-result-display text is-error)
                                           {:hang-indent code-hang-indent})
                           ;; MCP tools return JSON payloads: pretty-print + JSON-
                           ;; highlight them; fall back to the tool's own grammar.
                           (let [pretty  (when (and mcp? (not is-error)) (pretty-json text))
                                 text    (or pretty text)
                                 grammar (if pretty (hl-grammars/get-grammar "json") (.-grammar st))]
                             (comp/make-text
                              (if (and grammar (not is-error))
                                (truncate-output (highlight-text grammar text) truncate-output-block-after-n-lines)
                                (truncate-output text truncate-output-block-after-n-lines)))))))
                      (set! (.-outputSet st) true))
        finish! (fn [is-error]
                  (when spinner
                    ((:stop spinner))
                    ((:remove-child box) spinner)
                    (let [elapsed (- (js/Date.now) start-time)]
                      (when (and (= short-name "bash") (>= elapsed 3000))
                        (let [duration (str (.toFixed (/ elapsed 1000) 1) "s")]
                          ((:add-child box) (comp/make-spacer 1))
                          ((:add-child box)
                           (comp/make-text (ansi/fg (if is-error :error :dim)
                                                    (str "Took " duration))))))))
                  (set! (.-finished st) true))]
    ((:add-child box) header-text)
    (when code-text ((:add-child box) code-text))
    (if live?
      (do ((:add-child box) spinner)
          ((:start spinner)))
      ;; Already settled (resumed session) — render result statically
      (do (when (and (:result entry) (not collapsed?))
            (set-output! (:result entry) (:is-error entry)))
          (set! (.-finished st) true)))
    {:nodes [box (comp/make-spacer 1)]
     :update! (fn [old new]
                (when (and (= :tool-call (:kind new)) (= (:id old) (:id new)))
                  (when (not= (:arguments old) (:arguments new))
                    (ensure-grammar! (:arguments new))
                    ((:set-text header-text)
                     (tool-header-str short-name (header-args (:arguments new))))
                    (when code-text
                      ((:set-text code-text) (clj-code-display (:arguments new)))))
                  (when (and (:result new) (not (.-outputSet st)) (not collapsed?))
                    (set-output! (:result new) (:is-error new)))
                  (when (and (not= :running (:status new)) (not (.-finished st)))
                    (finish! (= :error (:status new))))
                  true))}))

(defn- status-block [entry]
  {:nodes [(comp/make-text (ansi/fg :dim (:text entry)))
           (comp/make-spacer 1)]
   :update! never-update})

(defn- error-block [entry]
  (let [err (:error entry)
        msg (or (:message err) (pr-str err))]
    ;; Suppress spurious SDK errors during abort (parity with master)
    {:nodes (if (and msg (str/includes? msg "null is not an object"))
              []
              [(comp/make-text (str (ansi/fg :error "[Error]") " " (ansi/fg :dim msg)))
               (comp/make-spacer 1)])
     :update! never-update}))

(defn- aborted-block [_entry]
  {:nodes [(comp/make-text (ansi/fg :dim "Interrupted."))
           (comp/make-spacer 1)]
   :update! never-update})

(defn entry->block
  "Build a block for a history entry. See ns docstring for the shape."
  [entry]
  (case (:kind entry)
    :user      (user-block entry)
    :text      (text-block entry)
    :thinking  (thinking-block entry)
    :tool-call (tool-block entry)
    :status    (status-block entry)
    :error     (error-block entry)
    :aborted   (aborted-block entry)
    {:nodes [] :update! never-update}))

;; ── Launch header ────────────────────────────────────────────────────────────

(defn status-banner
  "Connection / pairing status shown by a client before a room is available:
   while the socket is still connecting, while a pairing code is awaiting
   approval on the server, or after the server denied this client key."
  [{:keys [status code]}]
  (node/children
   [(node/text (str (ansi/fg :bold "Xi") " " (ansi/fg :dim "— coding harness")))
    (node/spacer)
    (case status
      :pending
      [(node/text (str (ansi/fg :yellow "●") " Waiting for pairing approval"))
       (node/spacer)
       (node/text (str (ansi/fg :dim "Pairing code: ")
                       (ansi/fg :accent (or code "····"))))
       (node/text (ansi/fg :dim "Approve this client on the server — it connects automatically once approved:"))
       (node/text (str "  " (ansi/fg :accent (str "bb serve:approve " code))))
       (node/text (ansi/fg :dim "  …or click Approve on the web banner at the server URL."))]

      :denied
      [(node/text (str (ansi/fg :red "●") " Connection denied by the server"))
       (node/spacer)
       (node/text (ansi/fg :dim "The server rejected this client key. On the server, inspect clients with"))
       (node/text (str "  " (ansi/fg :accent "bb serve:clients")
                       (ansi/fg :dim " / ") (ansi/fg :accent "bb serve:pending")))]

      ;; :connecting (default)
      (node/text (str (ansi/fg :yellow "●") (ansi/fg :dim " Connecting to server…"))))
    (node/spacer)]))

(defn launch-header
  "Header components shown at the top of a room's chat."
  [{:keys [model cwd agents-files]}]
  (node/children
   [(node/text (str (ansi/fg :bold "Xi") " " (ansi/fg :dim "— coding harness")))
    (when model
      (node/text (str (ansi/fg :dim "Model: ") (ansi/fg :accent model))))
    (when cwd
      (node/text (str (ansi/fg :dim "cwd: ") (ansi/fg :accent cwd))))
    (node/text (ansi/fg :dim "Type /quit to exit, /help for commands."))
    (node/spacer)
    (when-let [files (seq agents-files)]
      [(node/text
        (str (ansi/fg :dim "Loaded ") (ansi/fg :accent (str (count files) " AGENTS.md"))
             (ansi/fg :dim (str " file" (when (> (count files) 1) "s")))))
       (node/spacer)])]))

;; ── Logs / prompt buffer views ───────────────────────────────────────────────

(defn- format-timestamp [ts]
  (let [d (js/Date. ts)]
    (str (.padStart (str (.getHours d)) 2 "0") ":"
         (.padStart (str (.getMinutes d)) 2 "0") ":"
         (.padStart (str (.getSeconds d)) 2 "0"))))

(defn- log-line [entry]
  (let [extras (-> entry
                   (dissoc :type :event/id :event/ts :room-id :log/effects)
                   (->> (filter (fn [[_ v] ] (some? v)))
                        (into {})))]
    (str (ansi/fg :dim (format-timestamp (:event/ts entry)))
         " " (ansi/fg :accent (str (:type entry)))
         (when (seq extras)
           (str " " (truncate (pr-str extras) 120)))
         (when-let [fx (seq (:log/effects entry))]
           (ansi/fg :dim (str " → " (str/join " " (map str fx))))))))

(defn logs-view
  "Build the logs buffer view from ring entries, filtered to a room
   (entries without a :room-id are connection-level — always shown)."
  [entries room-id]
  (let [c (tui/make-container)
        log? (fn [{:keys [type]}]
               (and (keyword? type) (= "log" (namespace type))))
        visible (->> entries
                     (filter #(and (log? %)
                                   (or (nil? (:room-id %)) (= room-id (:room-id %)))))
                     (take-last 200))]
    (node/append-children! c
      [(node/text (str (ansi/fg :bold "Logs")
                       (ansi/fg :dim (str " (" (count visible) ")"))))
       (node/spacer)
       (if (empty? visible)
         (node/text (ansi/fg :dim "(empty)"))
         (mapv #(node/text (log-line %)) visible))
       (node/spacer)])
    c))

(def ^:private buffer-text-renderers
  "Per-engine transforms applied to a buffer's raw text before it is shown.
   Keyed by the buffer's :engine; engines with no entry render verbatim.
   Add an engine by adding a key here rather than branching in buffer-view."
  {:difft retint-difft})

(defn- highlight-buffer-text
  "Syntax-highlight a file buffer's text using the grammar for its :path
   extension. Returns the text unchanged when no grammar matches."
  [path text]
  (if-let [grammar (some-> path file-ext hl-grammars/get-grammar)]
    (highlight-text grammar text)
    text))

(defn buffer-display-text
  "A buffer's display text: syntax-highlighted when it carries a file :path,
   otherwise its raw text with any per-engine renderer applied (e.g. difft
   retints its palette codes into the TUI theme). Blank text → empty string."
  [{:keys [text engine path]}]
  (let [render (get buffer-text-renderers engine identity)]
    (cond
      (str/blank? text) ""
      path              (highlight-buffer-text path (render text))
      :else             (render text))))

(defn buffer-view
  "Generic text buffer view ({:title :text} from room :ui :buffers).
   File buffers (carrying a :path) are syntax-highlighted; otherwise the
   buffer's :engine selects a text renderer from buffer-text-renderers
   (e.g. difft retints its palette codes into the TUI theme)."
  [{:keys [title] :as buf}]
  (let [c (tui/make-container)
        rendered (buffer-display-text buf)
        body (if (str/blank? rendered) "(empty)" rendered)]
    (node/append-children! c
      [(node/text (ansi/fg :bold (or title "Buffer")))
       (node/spacer)
       (node/text body)
       (node/spacer)])
    c))
