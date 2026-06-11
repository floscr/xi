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
  (:require [clojure.string :as str]
            [xi.highlight.core :as hl]
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

(def ^:private display-tool-name
  "Map internal tool names to nicer display names."
  {"git_overview"                  "git diff --stat"
   "git_file_diff"                 "git diff"
   "git_hunk"                      "git diff"
   "git_stage_hunks"               "git add"
   "git_commit_with_user_approval" "git commit"})

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
  "Format tool arguments for display in the tool header."
  [tool-name arguments]
  (case tool-name
    "Bash"  (get-arg arguments :command)
    "Read"  (get-arg arguments :file_path)
    "Write" (get-arg arguments :file_path)
    "Edit"  (get-arg arguments :file_path)
    "Grep"  (str (get-arg arguments :pattern)
                 (when-let [g (get-arg arguments :glob)]
                   (str " --glob " g)))
    "Glob"  (get-arg arguments :pattern)
    "Agent" (or (get-arg arguments :description)
                (get-arg arguments :prompt)
                (get-arg arguments :task))
    ;; Xi's own lowercase tools
    "bash"  (get-arg arguments :command)
    "read"  (get-arg arguments :path)
    "write" (get-arg arguments :path)
    "edit"  (get-arg arguments :path)
    "grep"  (str (get-arg arguments :pattern)
                 (when-let [g (get-arg arguments :glob)]
                   (str " --glob " g)))
    "find"  (get-arg arguments :pattern)
    "ls"    (get-arg arguments :path)
    ;; Git tools
    "git_overview"    (if (get-arg arguments :staged) "--staged" nil)
    "git_file_diff"   (str/join " " (get-arg arguments :files))
    "git_hunk"        (get-arg arguments :file)
    "git_stage_hunks" (str/join " " (get-arg arguments :files))
    "git_commit_with_user_approval" (get-arg arguments :message)
    (format-tool-args-default arguments)))

(def ^:private shorten-tool-name util/strip-mcp-prefix)
(def ^:private truncate util/truncate)

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
  "Determine the highlighting grammar for a tool's output, or nil."
  [tool-name arguments]
  (let [path (case tool-name
               ("Read" "Write" "Edit") (get-arg arguments :file_path)
               ("read" "write" "edit") (get-arg arguments :path)
               "git_hunk" (get-arg arguments :file)
               "git_file_diff" (let [files (get-arg arguments :files)]
                                 (when (= 1 (count files)) (first files)))
               nil)]
    (when path
      (hl-grammars/get-grammar (file-ext path)))))

;; ── Syntax / diff highlighting ───────────────────────────────────────────────

;; Diff line background colors — blended with tool block bg (38,44,55)
(def ^:private diff-add-bg "\033[48;2;35;60;45m")
(def ^:private diff-del-bg "\033[48;2;65;40;42m")

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
                 (let [code (highlight-line grammar (subs line 2))
                       patched (str/replace code ansi/reset (str ansi/reset diff-add-bg))]
                   (str diff-add-bg "+ " patched))

                 (str/starts-with? line "- ")
                 (let [code (highlight-line grammar (subs line 2))
                       patched (str/replace code ansi/reset (str ansi/reset diff-del-bg))]
                   (str diff-del-bg "- " patched))

                 (str/starts-with? line "  ")
                 (str "  " (highlight-line grammar (subs line 2)))

                 (= line "...")
                 (ansi/fg :dim "...")

                 :else line)))
       (str/join "\n")))

(defn- diff-output?
  "Does tool output look like a unified diff (from the edit tool)?"
  [text]
  (let [lines (take 10 (str/split-lines text))]
    (some #(or (str/starts-with? % "+ ")
               (str/starts-with? % "- "))
          (rest lines))))

(defn- highlight-text
  "Apply syntax highlighting; auto-detects diff output."
  [grammar text]
  (if (diff-output? text)
    (highlight-diff-text grammar text)
    (let [tokens (-> (hl/tokenize grammar text) hl/merge-adjacent)
          lines (hl/split-tokens-by-line tokens)]
      (->> lines
           (mapv hl-theme/colorize)
           (str/join "\n")))))

;; ── Entry blocks ─────────────────────────────────────────────────────────────

(defn- never-update [_ _] false)

(defn- user-message-nodes
  "Node(s) for a user message. Renders code fences through markdown."
  [text suffix]
  (if (str/includes? text "```")
    (let [first-nl (str/index-of text "\n")
          first-line (if first-nl (subs text 0 first-nl) text)
          rest-text (when first-nl (subs text (inc first-nl)))]
      [(node/text (str (ansi/fg :bold "you") ": " first-line suffix))
       (when rest-text
         (md/make-markdown rest-text))])
    [(node/text (str (ansi/fg :bold "you") ": " text suffix))]))

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
                     (truncate (first (str/split-lines args-summary)) 120))
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

(defn- result-text
  "Extract display text from a tool result content (string or blocks)."
  [content]
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
    :else nil))

(defn- tool-block
  "Tool-call entry → box with header, output and (when live) spinner/timer.
   Entries that arrive already finished (resumed sessions) render statically."
  [entry]
  (let [bg-code "\033[48;2;38;44;55m"
        box (comp/make-box {:padding-x 1 :padding-y 0 :bg-code bg-code})
        short-name (shorten-tool-name (:tool entry))
        header-text (comp/make-text
                     (tool-header-str short-name
                                      (format-tool-args short-name (:arguments entry))))
        live? (= :running (:status entry))
        spinner (when live? (comp/make-spinner))
        start-time (js/Date.now)
        st #js {:outputSet false :finished false
                :grammar (tool-output-lang short-name (:arguments entry))}
        ensure-grammar! (fn [arguments]
                          (when-not (.-grammar st)
                            (set! (.-grammar st) (tool-output-lang short-name arguments))))
        set-output! (fn [content is-error]
                      (when-let [text (not-empty (result-text content))]
                        (let [display (if (and (.-grammar st) (not is-error))
                                        (truncate-output (highlight-text (.-grammar st) text) 20)
                                        (truncate-output text 20))]
                          ((:add-child box) (comp/make-spacer 1))
                          ((:add-child box) (comp/make-text display))))
                      (set! (.-outputSet st) true))
        finish! (fn [is-error]
                  (when spinner
                    ((:stop spinner))
                    ((:remove-child box) spinner)
                    (let [elapsed (- (js/Date.now) start-time)
                          duration (str (.toFixed (/ elapsed 1000) 1) "s")]
                      ((:add-child box) (comp/make-spacer 1))
                      ((:add-child box)
                       (comp/make-text (ansi/fg (if is-error :error :dim)
                                                (str "Took " duration))))))
                  (set! (.-finished st) true))]
    ((:add-child box) header-text)
    (if live?
      (do ((:add-child box) spinner)
          ((:start spinner)))
      ;; Already settled (resumed session) — render result statically
      (do (when (:result entry)
            (set-output! (:result entry) (:is-error entry)))
          (set! (.-finished st) true)))
    {:nodes [box (comp/make-spacer 1)]
     :update! (fn [old new]
                (when (and (= :tool-call (:kind new)) (= (:id old) (:id new)))
                  (when (not= (:arguments old) (:arguments new))
                    (ensure-grammar! (:arguments new))
                    ((:set-text header-text)
                     (tool-header-str short-name
                                      (format-tool-args short-name (:arguments new)))))
                  (when (and (:result new) (not (.-outputSet st)))
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

(defn launch-header
  "Header components shown at the top of a room's chat."
  [{:keys [model cwd agents-files]}]
  (node/children
   [(node/text (str (ansi/fg :bold "Xi") " " (ansi/fg :dim "— coding agent")))
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
        visible (->> entries
                     (filter #(or (nil? (:room-id %)) (= room-id (:room-id %))))
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

(defn buffer-view
  "Generic text buffer view ({:title :text} from room :ui :buffers)."
  [{:keys [title text]}]
  (let [c (tui/make-container)]
    (node/append-children! c
      [(node/text (ansi/fg :bold (or title "Buffer")))
       (node/spacer)
       (node/text (or text "(empty)"))
       (node/spacer)])
    c))
