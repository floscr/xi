(ns xi.client.tui
  "TUI client — subscribes to runtime events and renders them
   using the TUI component system. Also handles keyboard input
   and dispatches commands back via a transport abstraction.

   Transport is a map with:
     :dispatch! (fn [command-or-string] -> promise)
     :busy?     (fn [] -> bool)"
  (:require [clojure.string :as str]
            [xi.command-registry :as cmd-registry]
            [xi.ext.core :as ext]
            [xi.ext.done-notify :as ext-done-notify]
            [xi.highlight.core :as hl]
            [xi.highlight.grammars :as hl-grammars]
            [xi.highlight.theme :as hl-theme]
            [xi.tui.ansi :as ansi]
            [xi.tui.buffers :as buffers]
            [xi.tui.command-palette :as palette]
            [xi.tui.core :as tui]
            [xi.tui.completion :as completion]
            [xi.tui.components :as comp]
            [xi.tui.editor :as editor]
            [xi.tui.node :as node]
            [xi.tui.clipboard-image :as clip-image]
            [xi.tui.diff-buffer :as diff-buffer]
            [xi.tui.markdown :as md]
            [xi.tui.terminal :as term]
            [xi.tui.tree-selector :as tree-selector]
            [xi.session.tree :as session-tree]
            [xi.util :as util]))

(defn- shutdown!
  ([] (shutdown! nil))
  ([{:keys [message exit?] :or {exit? true}}]
   (term/restore-stdout!)
   (tui/stop-tui!)
   (when message (println message))
   (when exit? (js/process.exit 0))))

;; ── Tool Call Formatting ──────────────────────────────────────────────────────

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

(defn- tool-display-name
  "Get display name for a tool, falling back to the raw name."
  [tool-name]
  (or (display-tool-name tool-name) tool-name))

(defn- format-tool-args
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
    ;; Also handle lowercase tool names from Xi's own tools
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
    "git_overview"   (if (get-arg arguments :staged) "--staged" nil)
    "git_file_diff"  (str/join " " (get-arg arguments :files))
    "git_hunk"       (get-arg arguments :file)
    "git_stage_hunks" (str/join " " (get-arg arguments :files))
    "git_commit_with_user_approval" (get-arg arguments :message)
    nil))

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

(defn- file-ext
  "Extract file extension from a path, lowercased. Returns nil if none."
  [path]
  (when (and path (str/includes? path "."))
    (-> path (str/split #"\.") last str/lower-case)))

(defn- tool-output-lang
  "Determine the highlighting language for a tool's output.
   Returns a grammar or nil."
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

;; Diff line background colors — blended with tool block bg (38,44,55)
(def ^:private diff-add-bg "\033[48;2;35;60;45m")  ;; green-tinted
(def ^:private diff-del-bg "\033[48;2;65;40;42m")  ;; red-tinted
(def ^:private diff-hunk-fg "\033[38;2;129;161;193m") ;; blue (same as keyword)

(defn- highlight-line
  "Syntax-highlight a single line of code."
  [grammar line]
  (let [tokens (-> (hl/tokenize grammar line) hl/merge-adjacent)]
    (hl-theme/colorize tokens)))

(defn- highlight-diff-text
  "Highlight text that contains a unified diff.
   Strips +/- prefix before tokenizing, then applies diff bg colors.
   The bg is set at line start and persists — the box's apply-bg-to-line
   will handle reset at line boundaries. We re-apply the diff bg after
   any reset sequences from syntax highlighting so the bg survives."
  [grammar text]
  (->> (str/split-lines text)
       (mapv (fn [line]
               (cond
                 ;; Added line: green bg, highlight code without prefix
                 (str/starts-with? line "+ ")
                 (let [code (highlight-line grammar (subs line 2))
                       ;; Re-apply diff bg after any resets from syntax coloring
                       patched (str/replace code ansi/reset (str ansi/reset diff-add-bg))]
                   (str diff-add-bg "+ " patched))

                 ;; Removed line: red bg, highlight code without prefix
                 (str/starts-with? line "- ")
                 (let [code (highlight-line grammar (subs line 2))
                       patched (str/replace code ansi/reset (str ansi/reset diff-del-bg))]
                   (str diff-del-bg "- " patched))

                 ;; Context line: highlight code without prefix
                 (str/starts-with? line "  ")
                 (str "  " (highlight-line grammar (subs line 2)))

                 ;; Hunk separator
                 (= line "...")
                 (ansi/fg :dim "...")

                 ;; First line (filename) or other — pass through
                 :else line)))
       (str/join "\n")))

(defn- diff-output?
  "Check if tool output looks like a unified diff (from edit tool)."
  [text]
  (let [lines (take 5 (str/split-lines text))]
    (some #(or (str/starts-with? % "+ ")
               (str/starts-with? % "- ")
               (str/starts-with? % "  "))
          (rest lines))))

(defn- highlight-text
  "Apply syntax highlighting to text using a grammar.
   Auto-detects diff output and applies diff bg colors."
  [grammar text]
  (if (diff-output? text)
    (highlight-diff-text grammar text)
    (->> (str/split-lines text)
         (mapv (fn [line] (highlight-line grammar line)))
         (str/join "\n"))))

;; ── Tool Execution Component ─────────────────────────────────────────────────

(defn- make-tool-component
  "Create a tool execution display component."
  [tool-name args-summary]
  (let [bg-code "\033[48;2;38;44;55m"
        box (comp/make-box {:padding-x 1 :padding-y 0 :bg-code bg-code})
        first-line (when (seq args-summary)
                     (truncate (first (str/split-lines args-summary)) 120))
        rest-lines (when (seq args-summary)
                    (let [lines (rest (str/split-lines args-summary))]
                      (when (seq lines)
                        (str/join "\n" (take 20 lines)))))
        shown-name (tool-display-name tool-name)
        header-text (comp/make-text
                     (str (ansi/fg :accent (str "$ " shown-name))
                          (when first-line
                            (str " " first-line))
                          (when rest-lines
                            (str "\n" (ansi/fg :dim rest-lines)
                                 (when (> (count (str/split-lines args-summary)) 21)
                                   (str "\n" (ansi/fg :dim "...")))))))
        spinner (comp/make-spinner)
        output-text (comp/make-text "")
        start-time (js/Date.now)]
    ((:add-child box) header-text)
    ((:add-child box) spinner)
    ((:start spinner))
    {:component box
     :set-output (fn [text]
                   ((:stop spinner))
                   ((:remove-child box) spinner)
                   ((:add-child box) (comp/make-spacer 1))
                   ((:add-child box) output-text)
                   ((:set-text output-text) text))
     :update-header (fn [new-tool-name new-args-summary]
                      (let [first-ln (when (seq new-args-summary)
                                       (truncate (first (str/split-lines new-args-summary)) 120))
                            rest-lns (when (seq new-args-summary)
                                       (let [lines (rest (str/split-lines new-args-summary))]
                                         (when (seq lines)
                                           (str/join "\n" (take 20 lines)))))]
                        ((:set-text header-text)
                         (str (ansi/fg :accent (str "$ " (tool-display-name new-tool-name)))
                              (when first-ln
                                (str " " first-ln))
                              (when rest-lns
                                (str "\n" (ansi/fg :dim rest-lns)
                                     (when (> (count (str/split-lines new-args-summary)) 21)
                                       (str "\n" (ansi/fg :dim "...")))))))))
     :finish (fn [is-error]
               ((:stop spinner))
               ((:remove-child box) spinner)
               (let [elapsed (- (js/Date.now) start-time)
                     duration (str (.toFixed (/ elapsed 1000) 1) "s")
                     color (if is-error :error :dim)]
                 ((:add-child box) (comp/make-spacer 1))
                 ((:add-child box)
                  (comp/make-text (ansi/fg color (str "Took " duration))))
                 (tui/request-render!)))}))


(defn- make-static-tool-component
  "Create a static tool display for history replay (no spinner/timer)."
  [tool-name args-summary output is-error]
  (let [bg-code "\033[48;2;38;44;55m"
        box (comp/make-box {:padding-x 1 :padding-y 0 :bg-code bg-code})
        short-name (tool-display-name (shorten-tool-name tool-name))
        first-line (when (seq args-summary)
                     (truncate (first (str/split-lines args-summary)) 120))
        rest-lines (when (seq args-summary)
                    (let [lines (rest (str/split-lines args-summary))]
                      (when (seq lines)
                        (str/join "\n" (take 20 lines)))))
        header-text (comp/make-text
                     (str (ansi/fg :accent (str "$ " short-name))
                          (when first-line
                            (str " " first-line))
                          (when rest-lines
                            (str "\n" (ansi/fg :dim rest-lines)
                                 (when (> (count (str/split-lines args-summary)) 21)
                                   (str "\n" (ansi/fg :dim "...")))))))]
    (node/append-children! box
      [header-text
       (when (seq output)
         [(node/spacer)
          (node/text (truncate-output output 20))])])
    box))

;; ── Buffer Helpers ─────────────────────────────────────────────────────────

(defn- format-timestamp [ts]
  (let [d (js/Date. ts)]
    (str (.padStart (str (.getHours d)) 2 "0") ":"
         (.padStart (str (.getMinutes d)) 2 "0") ":"
         (.padStart (str (.getSeconds d)) 2 "0"))))

;; ── Status Message Formatting ────────────────────────────────────────────────

(defn- format-session-source [s]
  (case (:source s)
    :xi     ""
    :claude (ansi/fg :dim " [claude]")
    :pi     (ansi/fg :dim " [pi]")
    ""))


;; ── Launch Header ─────────────────────────────────────────────────────────────

(defn launch-header
  "Build launch header nodes as a flat vector.
   opts may include :model, :cwd, :agents-files, and/or :details
   (vec of {:label :value} maps) for custom info lines (e.g. Room)."
  [{:keys [model cwd agents-files details]}]
  (node/children
   [(node/text (str (ansi/fg :bold "Xi") " " (ansi/fg :dim "— coding agent")))
    (when model
      (node/text (str (ansi/fg :dim "Model: ") (ansi/fg :accent model))))
    (when cwd
      (node/text (str (ansi/fg :dim "cwd: ") (ansi/fg :accent cwd))))
    (for [{:keys [label value]} details]
      (node/text (str (ansi/fg :dim (str label ": ")) (ansi/fg :accent value))))
    (node/text (ansi/fg :dim "Type /quit to exit, /help for commands."))
    (node/spacer)
    (when-let [files (seq agents-files)]
      [(node/text
        (str (ansi/fg :dim "Loaded ") (ansi/fg :accent (str (count files) " AGENTS.md"))
             (ansi/fg :dim (str " file" (when (> (count files) 1) "s")))))
       (node/spacer)])]))

(defn- render-launch-header!
  "Render the launch header into a chat container."
  [chat-container opts]
  (node/append-children! chat-container (launch-header opts)))

;; ── TUI Client ───────────────────────────────────────────────────────────────

(defn create!
  "Create a TUI client. Returns a client map with :on-event.

   opts:
     :transport - map with :dispatch! and :busy? fns
     :header    - optional map with :model for the header line"
  [opts]
  (let [transport (:transport opts)
        dispatch! (:dispatch! transport)
        busy? (:busy? transport)

        ;; Mutable state for tracking current turn rendering
        text-started (atom false)
        current-md (atom nil)
        current-tool (atom nil)
        thinking-text (atom "")
        current-thinking-comp (atom nil)
        ;; Track last locally-submitted prompt to avoid double-rendering
        last-local-prompt (atom nil)

        ;; Queued prompt — submitted while busy (e.g. right after abort)
        queued-prompt (atom nil)

        ;; Pending clipboard images attached via Alt+V
        pending-images (atom [])

        ;; Whether we explicitly asked to switch rooms (vs initial connect)
        switching-rooms (atom false)

        ;; Prompt buffer content
        prompt-content (atom nil)

        ;; Completion menu state
        active-menu (atom nil)       ;; current completion menu component, or nil
        menu-spacer (atom nil)       ;; spacer component inserted before menu

        ;; Session buffers — capture stray stdout/stderr
        buffer-mgr (or (:buffer-mgr opts) (buffers/create-manager ["Logs"]))

        ;; Create TUI
        content (tui/create-tui!)  ;; scrollable content area
        chat-container (tui/make-container)
        view-wrapper (tui/make-container)  ;; holds the active buffer view
        active-view (atom "Chat")          ;; "Chat" or "Logs"
        active-modal-buffer (atom nil)     ;; diff/special buffer component when active
        session-start-commit (atom (try
                                     (let [proc (js/Bun.spawnSync
                                                  #js ["git" "rev-parse" "HEAD"]
                                                  #js {:cwd (.cwd js/process)})]
                                       (when (zero? (.-exitCode proc))
                                         (str/trim (.toString (.-stdout proc) "utf-8"))))
                                     (catch :default _ nil)))
        session-files (atom #{})           ;; files the agent touched this session
        loader (comp/make-loader "thinking...")

        ;; ── Buffer View Switching ───────────────────────────────────────────

        build-logs-view!
        (fn []
          (let [c (tui/make-container)
                entries (buffers/get-entries buffer-mgr "Logs")]
            (node/append-children! c
              [(node/text (str (ansi/fg :bold "Logs")
                              (ansi/fg :dim (str " (" (count entries) " entries)"))))
               (node/spacer)
               (if (empty? entries)
                 (node/text (ansi/fg :dim "(empty)"))
                 (for [entry (take-last 100 entries)]
                   (node/text (str (ansi/fg :dim (format-timestamp (:timestamp entry)))
                                   " " (str/trim-newline (:text entry))))))
               (node/spacer)])
            c))

        build-prompt-view!
        (fn []
          (let [c (tui/make-container)
                content (or @prompt-content "(no prompt loaded)")]
            (node/append-children! c
              [(node/text (ansi/fg :bold "System Prompt"))
               (node/spacer)
               (node/text content)
               (node/spacer)])
            c))

        switch-to-buffer!
        (fn [name]
          ((:clear view-wrapper))
          (reset! active-view name)
          (case name
            "Chat" ((:add-child view-wrapper) chat-container)
            "Logs" ((:add-child view-wrapper) (build-logs-view!))
            "Prompt" ((:add-child view-wrapper) (build-prompt-view!))
            "Diff" (when-let [buf @active-modal-buffer]
                     ((:add-child view-wrapper) buf)))
          (tui/render-now!))

        add-status-message!
        (fn [text]
          (node/append-children! chat-container
            [(node/spacer) (node/text text) (node/spacer)])
          ;; Only render if Chat is the active view
          (when (= @active-view "Chat")
            (tui/render-now!)))

        ;; ── Completion Menu Helpers ──────────────────────────────────────────

        ;; Forward-declared so hide/show-completion-menu! can reference editor
        editor-comp-ref (atom nil)
        open-palette-fn (atom nil)

        restore-editor-panel!
        (fn []
          (tui/set-bottom-panel! @editor-comp-ref)
          (tui/set-focus! @editor-comp-ref))

        hide-completion-menu!
        (fn []
          (when @active-menu
            (reset! active-menu nil)
            (reset! menu-spacer nil)
            ;; Restore editor as bottom panel
            (restore-editor-panel!)
            (tui/render-now!)))

        show-completion-menu!
        (fn [opts]
          ;; Close any existing menu first
          (when @active-menu
            (hide-completion-menu!))
          (let [menu (completion/make-completion-menu
                      (merge opts
                             {:on-select
                              (fn [item]
                                (hide-completion-menu!)
                                (when-let [cb (:on-select opts)]
                                  (cb item)))
                              :on-cancel
                              (fn []
                                (hide-completion-menu!)
                                (when-let [cb (:on-cancel opts)]
                                  (cb)))}))
                ms (comp/make-spacer 1)]
            (reset! active-menu menu)
            (reset! menu-spacer ms)
            ;; Replace the editor with the menu as bottom panel
            (let [menu-panel (tui/make-container)]
              ((:add-child menu-panel) ms)
              ((:add-child menu-panel) menu)
              (tui/set-bottom-panel! menu-panel))
            ;; Focus the menu (editor is hidden until menu is dismissed)
            (tui/set-focus! menu)
            (tui/render-now!)))

        ;; ── Tree Selector ────────────────────────────────────────────────────

        show-tree-selector!
        (fn []
          (when-let [get-tree (:get-session-tree transport)]
            (when-let [tree (get-tree)]
              (let [tree-nodes (session-tree/get-tree tree)
                    leaf-id (session-tree/get-leaf-id tree)]
                (when (seq tree-nodes)
                  ;; Close any existing menu
                  (when @active-menu (hide-completion-menu!))
                  (let [selector
                        (tree-selector/make-tree-selector
                         {:tree-nodes tree-nodes
                          :leaf-id leaf-id
                          :max-visible (max 10 (- (term/rows) 10))
                          :on-select
                          (fn [entry-id mode]
                            (hide-completion-menu!)
                            (when-let [navigate! (:navigate-tree! transport)]
                              (-> (navigate! entry-id mode)
                                  (.then (fn [result]
                                           (when-let [text (:editor-text result)]
                                             (when-let [ed @editor-comp-ref]
                                               ((:set-text ed) text)))
                                           (tui/request-render!)))
                                  (.catch (fn [err]
                                            (add-status-message!
                                             (str (ansi/fg :error "Navigate failed: ")
                                                  (.-message err))))))))
                          :on-cancel
                          (fn []
                            (hide-completion-menu!))})
                        ms (comp/make-spacer 1)]
                    (reset! active-menu selector)
                    (reset! menu-spacer ms)
                    (let [menu-panel (tui/make-container)]
                      ((:add-child menu-panel) ms)
                      ((:add-child menu-panel) selector)
                      (tui/set-bottom-panel! menu-panel))
                    (tui/set-focus! selector)
                    (tui/render-now!)))))))

        ;; ── Git / External Process ───────────────────────────────────────────

        open-git!
        (fn []
          (tui/run-external!
           ["ngit"]
           {:on-suspend (fn [] (term/restore-stdout!))
            :on-resume (fn []
                         (term/intercept-stdout!
                          (fn [_stream text]
                            (buffers/append! buffer-mgr "Logs" text))))}))

        ;; ── Permission Gate Confirmation ─────────────────────────────────────────
        ;; Wire up the confirmation handler so permission-gate can ask the user
        ;; before allowing blocked operations.

        _ (ext/set-confirm-handler!
            (fn [message]
              (js/Promise.
                (fn [resolve]
                  (show-completion-menu!
                    {:items [{:label "Allow" :description "Execute the blocked operation" :value true}
                             {:label "Deny" :description "Block the operation" :value false}]
                     :header-fn (fn [] (str "  " (ansi/fg :warning "⚠ ") message))
                     :prompt "> "
                     :on-select (fn [item] (resolve (:value item)))
                     :on-cancel (fn [] (resolve false))})))))

        ;; ── Client Command Registration ─────────────────────────────────────────
        ;; Register TUI-local commands into the central registry.
        ;; These run entirely in the client — no runtime round-trip.

        _ (cmd-registry/register-many!
           [{:name "git"
             :description "Open ngit"
             :scope :client
             :show-busy true
             :handler (fn [_ctx] (open-git!) nil)}

            {:name "model"
             :description "Show or set model"
             :scope :client
             :show-busy true
             :handler (fn [{:keys [args]}]
                        ;; Bare /model — show Ollama picker (client-only)
                        ;; /model <name> — return :pass-through to let runtime handle it
                        (if (some? args)
                          :pass-through
                          (do (-> (js/fetch "http://localhost:11434/api/tags")
                                  (.then (fn [res] (.json res)))
                                  (.then (fn [^js data]
                                           (let [models (js->clj (.-models data) :keywordize-keys true)
                                                 items (mapv (fn [m]
                                                               {:label (:name m)
                                                                :description (get-in m [:details :parameter_size])
                                                                :value (:name m)})
                                                             models)]
                                             (show-completion-menu!
                                              {:items items
                                               :prompt "model> "
                                               :on-select (fn [item]
                                                            (dispatch! (str "/model " (:value item))))})
                                             (tui/render-now!))))
                                  (.catch (fn [_err]
                                            (add-status-message!
                                             (ansi/fg :error "Could not fetch Ollama models (is ollama running?)"))
                                            (tui/render-now!))))
                              nil)))}

            {:name "join"
             :description "Join an existing room"
             :scope :client
             :show-busy true
             :handler (fn [_ctx]
                        (if-let [leave! (:leave! transport)]
                          (do (reset! switching-rooms true)
                              (leave!))
                          (add-status-message!
                           (ansi/fg :error "Room switching not available (standalone mode)")))
                        nil)}

            {:name "create"
             :description "Create a new room"
             :scope :client
             :show-busy true
             :handler (fn [_ctx]
                        (if-let [join! (:join! transport)]
                          (do (when-let [leave! (:leave! transport)] (leave!))
                              (join! "new"))
                          (add-status-message!
                           (ansi/fg :error "Room creation not available (standalone mode)")))
                        nil)}

            {:name "buffers"
             :description "Switch buffer view"
             :scope :client
             :show-busy true
             :handler (fn [_ctx]
                        (let [items (cond-> [{:label "Chat"
                                              :description (when (= @active-view "Chat") "• active")
                                              :value "Chat"}
                                             {:label "Logs"
                                              :description (let [n (count (buffers/get-entries buffer-mgr "Logs"))]
                                                             (str n " entries"
                                                                  (when (= @active-view "Logs") " • active")))
                                              :value "Logs"}]
                                      @prompt-content
                                      (conj {:label "Prompt"
                                             :description (when (= @active-view "Prompt") "• active")
                                             :value "Prompt"})
                                      @active-modal-buffer
                                      (conj {:label "Diff"
                                             :description (when (= @active-view "Diff") "• active")
                                             :value "Diff"}))]
                          (show-completion-menu!
                           {:items items
                            :prompt "buffer> "
                            :on-select (fn [item]
                                         (let [v (:value item)]
                                           (switch-to-buffer! v)
                                           (if (and (= v "Diff") @active-modal-buffer)
                                             (do (tui/set-focus! @active-modal-buffer)
                                                 (tui/scroll-to-offset! 999999))
                                             (tui/set-focus! @editor-comp-ref))))}))
                        nil)}

            {:name "palette"
             :description "Open command palette"
             :scope :client
             :show-busy true
             :hidden true
             :handler (fn [{:keys [args]}]
                        (if (nil? args)
                          ;; No args — open the palette picker
                          (@open-palette-fn)
                          ;; Sub-commands: add, remove, list
                          (let [[sub arg] (str/split (str/trim args) #"\s+" 2)]
                            (cond
                              (= sub "add")
                              (if (seq arg)
                                (if (palette/add-custom! arg)
                                  (add-status-message! (str "Added to palette: " (ansi/fg :accent arg)))
                                  (add-status-message! (str "Already in palette: " arg)))
                                (add-status-message! (ansi/fg :error "Usage: /palette add <command>")))

                              (= sub "remove")
                              (if (seq arg)
                                (if (palette/remove-custom! arg)
                                  (add-status-message! (str "Removed from palette: " arg))
                                  (add-status-message! (str "Not found: " arg)))
                                (add-status-message! (ansi/fg :error "Usage: /palette remove <command>")))

                              (= sub "list")
                              (let [customs (palette/list-custom)]
                                (if (empty? customs)
                                  (add-status-message! (ansi/fg :dim "No custom commands."))
                                  (add-status-message!
                                   (str (ansi/fg :bold "Custom commands:\n")
                                        (str/join "\n" (map #(str "  " (:command %)) customs))))))

                              :else
                              (add-status-message! (ansi/fg :error "Usage: /palette add|remove|list <command>")))))
                        nil)}

            {:name "attach-image"
             :description "Attach an image file (or paste from clipboard with no args)"
             :scope :client
             :handler (fn [{:keys [args]}]
                        (if (seq args)
                          ;; Attach from file path
                          (let [fs (js/require "node:fs")
                                path (js/require "node:path")
                                file-path (str/trim args)
                                resolved (.resolve path file-path)]
                            (if (.existsSync fs resolved)
                              (let [buffer (.readFileSync fs resolved)
                                    ext (-> (.extname path resolved) (subs 1) str/lower-case)
                                    media-type (case ext
                                                 "png" "image/png"
                                                 "jpg" "image/jpeg"
                                                 "jpeg" "image/jpeg"
                                                 "webp" "image/webp"
                                                 "gif" "image/gif"
                                                 nil)]
                                (if media-type
                                  (do (swap! pending-images conj
                                            {:data (.toString buffer "base64")
                                             :media-type media-type})
                                      (add-status-message!
                                       (str (ansi/fg :accent "📎 ") file-path
                                            (ansi/fg :dim (str " (" (count @pending-images) " total)")))))
                                  (add-status-message!
                                   (ansi/fg :error (str "Unsupported image format: ." ext)))))
                              (add-status-message!
                               (ansi/fg :error (str "File not found: " file-path)))))
                          ;; No args — read from clipboard
                          (if-let [img (clip-image/read-clipboard-image)]
                            (do (swap! pending-images conj img)
                                (add-status-message!
                                 (str (ansi/fg :accent "📎 ") "clipboard"
                                      (ansi/fg :dim (str " (" (count @pending-images) " total)")))))
                            (add-status-message!
                             (ansi/fg :dim "No image in clipboard"))))
                        (tui/request-render!)
                        nil)}

            {:name "clear-images"
             :description "Clear all pending image attachments"
             :scope :client
             :handler (fn [_]
                        (let [n (count @pending-images)]
                          (reset! pending-images [])
                          (add-status-message!
                           (if (pos? n)
                             (ansi/fg :dim (str "Cleared " n " image(s)"))
                             (ansi/fg :dim "No images to clear"))))
                        (tui/request-render!)
                        nil)}

            {:name "tree"
             :description "Show session tree for navigation/forking"
             :scope :client
             :handler (fn [_]
                        (show-tree-selector!)
                        nil)}

            ;; ── /diff (with subcommands) ─────────────────────────────────────
            (let [open-diff! (fn [diff-text title]
                              (if (empty? (str/trim diff-text))
                                (add-status-message! (ansi/fg :dim "No changes."))
                                (let [buf (diff-buffer/make-diff-buffer
                                            {:diff-text diff-text
                                             :title title
                                             :on-close (fn []
                                                         (reset! active-modal-buffer nil)
                                                         (tui/scroll-to-offset! 0)
                                                         (switch-to-buffer! "Chat")
                                                         (tui/set-focus! @editor-comp-ref))
                                             :on-command-mode (fn []
                                                                (tui/set-focus! @editor-comp-ref))})]
                                  (reset! active-modal-buffer buf)
                                  (switch-to-buffer! "Diff")
                                  (tui/set-focus! buf)
                                  (tui/scroll-to-offset! 999999)
                                  (tui/render-now!))))
                  run-git-diff (fn [& git-args]
                                (let [proc (js/Bun.spawnSync (into-array (cons "git" git-args))
                                             #js {:cwd (.cwd js/process)})]
                                  (if (zero? (.-exitCode proc))
                                    (.toString (.-stdout proc) "utf-8")
                                    (do (add-status-message!
                                          (ansi/fg :error (str "git diff failed: "
                                                               (.toString (.-stderr proc) "utf-8"))))
                                        nil))))
                  untracked-diff (fn []
                                  (let [proc (js/Bun.spawnSync
                                               #js ["git" "ls-files" "--others" "--exclude-standard"]
                                               #js {:cwd (.cwd js/process)})
                                        files (when (zero? (.-exitCode proc))
                                                (->> (.toString (.-stdout proc) "utf-8")
                                                     str/trim
                                                     str/split-lines
                                                     (remove empty?)))]
                                    (when (seq files)
                                      (->> files
                                           (map (fn [f]
                                                  (let [p (js/Bun.spawnSync
                                                             #js ["git" "diff" "--no-index" "--" "/dev/null" f]
                                                             #js {:cwd (.cwd js/process)})]
                                                    (.toString (.-stdout p) "utf-8"))))
                                           (str/join "\n")))))]
              {:name "diff"
               :description "Show diff viewer"
               :scope :client
               :show-busy true
               :subcommands
               [{:name "git"
                 :description "All changes (unstaged + staged + untracked)"
                 :handler (fn [_]
                            (let [unstaged  (or (run-git-diff "diff") "")
                                  staged    (or (run-git-diff "diff" "--staged") "")
                                  untracked (or (untracked-diff) "")
                                  combined  (str/trim (str unstaged "\n" staged "\n" untracked))]
                              (open-diff! combined "All Git Changes"))
                            nil)}
                {:name "staged"
                 :description "Staged changes only"
                 :handler (fn [_]
                            (when-let [text (run-git-diff "diff" "--staged")]
                              (open-diff! text "Staged Changes"))
                            nil)}
                {:name "unstaged"
                 :description "Unstaged changes only"
                 :handler (fn [_]
                            (when-let [text (run-git-diff "diff")]
                              (open-diff! text "Unstaged Changes"))
                            nil)}]
               :handler
               (fn [{:keys [args]}]
                 ;; Default: session diff, or pass arg directly to git diff
                 (let [git-args (if (nil? args)
                                  (if-let [start @session-start-commit]
                                    ["diff" start]
                                    ["diff"])
                                  ["diff" args])
                       title (if (nil? args) "Session Changes" (str "Diff: " args))]
                   (when-let [text (apply run-git-diff git-args)]
                     (open-diff! text title)))
                 nil)})])

        ;; ── Local Command Dispatch ──────────────────────────────────────────────
        ;; Check the central registry for :client-scoped commands.

        handle-local-command!
        (fn [text]
          (when (str/starts-with? text "/")
            (let [parts (str/split (subs text 1) #"\s+" 2)
                  cmd-name (first parts)
                  args (when (second parts) (str/trim (second parts)))]
              (when-let [cmd (cmd-registry/get-command cmd-name :client)]
                (let [[handler remaining-args] (cmd-registry/resolve-handler cmd args)
                      result (handler {:args remaining-args})]
                  ;; :pass-through means the client handler declined —
                  ;; let the command fall through to runtime dispatch
                  (not= :pass-through result))))))

        open-palette!
        (fn []
          (let [items (palette/build-items)]
            (show-completion-menu!
             {:items items
              :prompt "palette> "
              :on-select (fn [item]
                           (let [cmd (:value item)]
                             ((:set-text @editor-comp-ref) "")
                             (palette/record-use! cmd)
                             (when-not (handle-local-command! cmd)
                               (dispatch! cmd))))})))
        _ (reset! open-palette-fn open-palette!)

        ;; Editor at the bottom
        editor-comp
        (editor/make-editor
         {:prompt "xi> "
          :on-submit (fn [text]
                       (let [text (str/trim text)
                             images @pending-images]
                         (when (or (seq text) (seq images))
                           (let [final-text (if (and (empty? text) (seq images))
                                              (let [n (count images)]
                                                (if (= 1 n) "[Attached image]" (str "[Attached " n " images]")))
                                              text)]
                             ;; Clear pending images
                             (reset! pending-images [])
                             (when (str/starts-with? final-text "/")
                               (palette/record-use! final-text))
                             ;; Try local commands first (work even when agent is busy)
                             (when-not (handle-local-command! final-text)
                               (let [payload (if (seq images)
                                               {:type :prompt :text final-text :images images}
                                               final-text)]
                                 (if-not (busy?)
                                   (do
                                     ;; Auto-switch to Chat if viewing another buffer
                                     (when (not= @active-view "Chat")
                                       (switch-to-buffer! "Chat"))
                                     ;; Remember we sent this so :user-message doesn't double-render
                                     (reset! last-local-prompt final-text)
                                     ;; Dispatch via transport
                                     (dispatch! payload))
                                   ;; Busy — queue prompt so it dispatches when the turn ends
                                   (reset! queued-prompt payload))))))
                         ;; Refocus modal buffer after commands (not regular prompts)
                         (when (and @active-modal-buffer
                                    (seq text)
                                    (str/starts-with? text "/"))
                           (tui/set-focus! @active-modal-buffer))))

          :on-paste-image
          (fn []
            (if-let [img (clip-image/read-clipboard-image)]
              (do
                (swap! pending-images conj img)
                (add-status-message!
                 (str (ansi/fg :accent "📎 ")
                      (ansi/fg :dim (str (count @pending-images) " image(s) attached")))))
              (add-status-message!
               (ansi/fg :dim "No image in clipboard"))))

          :on-escape (fn []
                       (if @active-modal-buffer
                         ;; Return focus to the modal buffer (diff viewer, etc.)
                         (tui/set-focus! @active-modal-buffer)
                         (cond
                           (tui/scrolled-up?)
                           (tui/scroll-to-bottom!)

                           (busy?)
                           (dispatch! {:type :abort})

                           ;; Empty editor + not busy → show tree selector
                           :else
                           (when-let [ed @editor-comp-ref]
                             (when (empty? (str/trim ((:get-text ed))))
                               (show-tree-selector!))))))

          :on-interrupt (fn [] (shutdown!))

          :on-palette open-palette!
          :on-git open-git!
          :on-notify-toggle (fn []
                              (ext-done-notify/toggle!)
                              (tui/request-render!))
          :ext-keybindings (ext/get-keybindings)
          :prompt-suffix-fn (fn []
                              (let [badges (ext/collect-prompt-badges)
                                    n (count @pending-images)]
                                (if (pos? n)
                                  (str badges (ansi/fg :accent (str " 📎" n)))
                                  badges)))})

        ;; Wire up forward reference
        _ (reset! editor-comp-ref editor-comp)

        ;; Wire TUI bridge so extensions can show completion menus and insert text
        _ (ext/set-completion-handler! show-completion-menu!)
        _ (ext/set-insert-text-handler!
           (fn [text]
             ((:insert-text @editor-comp-ref) text)))

        ;; Set up stdout/stderr interception — capture external writes to Logs buffer
        _ (term/intercept-stdout!
           (fn [_stream text]
             (buffers/append! buffer-mgr "Logs" text)))

        ;; Event handler — maps runtime events to TUI mutations
        on-event-fn (atom nil)
        on-event
        (fn [event]
          (case (:type event)
            :ready
            ;; Show AGENTS.md status when loaded
            (when-let [agents-files (seq (:agents-files event))]
              (node/append-children! chat-container
                [(node/text
                  (str (ansi/fg :dim "Loaded ") (ansi/fg :accent (str (count agents-files) " AGENTS.md"))
                       (ansi/fg :dim (str " file" (when (> (count agents-files) 1) "s")))))
                 (node/spacer)])
              (tui/render-now!))

            :user-message
            (let [text (:text event)
                  is-local (= text @last-local-prompt)]
              ;; Always show the message — local or remote
              (node/append-children! chat-container
                [(node/spacer)
                 (node/text (str (ansi/fg :bold "you") ": " text
                                 (when (seq (:images event))
                                   (str " " (ansi/fg :dim (str "(" (count (:images event)) " image(s))"))))))
                 (node/spacer)])
              (tui/render-now!)
              ;; Clear after matching
              (when is-local
                (reset! last-local-prompt nil)))

            :busy-changed
            (if (:busy event)
              (do ;; Reset turn state for new turn
                (reset! text-started false)
                (reset! current-md nil)
                (when-let [tool @current-tool]
                  ((:finish tool) false))
                (reset! current-tool nil)
                (reset! thinking-text "")
                (reset! current-thinking-comp nil))
              ;; No longer busy — dispatch queued prompt if any
              (let [text @queued-prompt]
                (reset! queued-prompt nil)
                (when text
                  ;; Auto-switch to Chat if viewing another buffer
                  (when (not= @active-view "Chat")
                    (switch-to-buffer! "Chat"))
                  (reset! last-local-prompt text)
                  (dispatch! text))))

            :turn-start
            (let [tc (comp/make-text "" {})]
              (reset! thinking-text "")
              (reset! current-thinking-comp tc)
              ((:add-child chat-container) tc)
              ((:add-child chat-container) loader)
              ((:start loader))
              (tui/render-now!))

            :text-delta
            (do (when-not @text-started
                  ((:stop loader))
                  ((:remove-child chat-container) loader)
                  ;; Add spacer after thinking if it had content
                  (when (seq @thinking-text)
                    ((:add-child chat-container) (comp/make-spacer 1)))
                  (reset! current-thinking-comp nil)
                  (let [m (md/make-markdown "")]
                    ((:add-child chat-container) m)
                    ((:add-child chat-container) (comp/make-spacer 1))
                    (reset! current-md m))
                  (reset! text-started true))
                ;; Append text to current markdown
                (when-let [m @current-md]
                  (let [prev ((:get-text m))
                        new-text (str prev (:text event))]
                    ((:set-text m) new-text))))

            :thinking
            (when-let [t (:text event)]
              (swap! thinking-text str t)
              (when-let [tc @current-thinking-comp]
                ((:set-text tc) (ansi/fg :dim @thinking-text))))

            :tool-start
            (do ;; Track session files for /diff
                (let [tool-name (:name event)
                      args (:arguments event)]
                  (when (#{"edit" "write" "mcp__xi-tools__edit" "mcp__xi-tools__write"} tool-name)
                    (when-let [path (get-arg args :path)]
                      (swap! session-files conj path))))
                (when-not @text-started
                  ((:stop loader))
                  ((:remove-child chat-container) loader)
                  ;; Add spacer after thinking if it had content
                  (when (seq @thinking-text)
                    ((:add-child chat-container) (comp/make-spacer 1))))
                (reset! current-thinking-comp nil)
                (reset! text-started false)
                (reset! current-md nil)
                ;; Create tool component
                (let [short-name (shorten-tool-name (:name event))
                      args-str (format-tool-args short-name (:arguments event))
                      tool-comp (make-tool-component short-name args-str)
                      grammar (tool-output-lang short-name (:arguments event))]
                  ((:add-child chat-container) (:component tool-comp))
                  (reset! current-tool (cond-> tool-comp
                                         grammar (assoc :grammar grammar))))
                (tui/render-now!))

            :tool-args
            (when-let [tool @current-tool]
              (let [short-name (shorten-tool-name (:name event))
                    args-str (format-tool-args short-name (:arguments event))]
                ((:update-header tool) short-name args-str)))

            :tool-result
            (do (when-let [tool @current-tool]
                  (let [content (:content event)
                        text (cond
                               (string? content) content
                               (sequential? content)
                               (->> content
                                    (keep (fn [b]
                                            (cond
                                              (string? b) b
                                              (= "text" (:type b)) (:text b)
                                              :else nil)))
                                    (str/join "\n"))
                               :else nil)]
                    (when (seq text)
                      (let [display-text (if-let [g (:grammar tool)]
                                           (truncate-output (highlight-text g text) 20)
                                           (truncate-output text 20))]
                        ((:set-output tool) display-text))))
                  ((:finish tool) (:is-error event)))
                (reset! current-tool nil)
                ;; Empty line after block
                ((:add-child chat-container) (comp/make-spacer 1))
                ;; Show loader for next iteration with fresh thinking comp
                (reset! thinking-text "")
                (let [tc (comp/make-text "" {})]
                  (reset! current-thinking-comp tc)
                  ((:add-child chat-container) tc))
                ((:add-child chat-container) loader)
                ((:start loader))
                (tui/render-now!))

            :error
            (let [err (:error event)
                  msg (or (:message err) (pr-str err))]
              ;; Suppress spurious SDK errors during abort
              (when-not (and msg (str/includes? msg "null is not an object"))
                (when-let [tool @current-tool]
                  ((:finish tool) true)
                  (reset! current-tool nil))
                ((:stop loader))
                ((:remove-child chat-container) loader)
                (node/append-children! chat-container
                  [(node/text
                    (str (ansi/fg :error "[Error]") " "
                         (ansi/fg :dim msg)))
                   (node/spacer)])
                (tui/render-now!)))

            :turn-end
            (do (when-let [tool @current-tool]
                  ((:finish tool) false)
                  (reset! current-tool nil))
                ((:stop loader))
                ((:remove-child chat-container) loader)
                (ext/dispatch-hook :agent-end)
                (tui/render-now!))

            :aborted
            (do (when-let [tool @current-tool]
                  ((:finish tool) false)
                  (reset! current-tool nil))
                ((:stop loader))
                ((:remove-child chat-container) loader)
                (add-status-message! (ansi/fg :dim "Interrupted."))
                (tui/render-now!))

            :session-cleared
            (do ((:clear chat-container))
                (reset! buffer-mgr {"Logs" []})
                (when (not= @active-view "Chat")
                  (switch-to-buffer! "Chat"))
                (render-launch-header! chat-container event)
                (tui/render-now!))

            :compact-start
            (add-status-message! (ansi/fg :dim "Compacting conversation..."))

            :session-compacted
            (do ((:clear chat-container))
                (reset! buffer-mgr {"Logs" []})
                (when (not= @active-view "Chat")
                  (switch-to-buffer! "Chat"))
                (add-status-message! (ansi/fg :dim "Session compacted. Summary preserved as context.")))

            :tree-navigated
            (do ((:clear chat-container))
                (when (not= @active-view "Chat")
                  (switch-to-buffer! "Chat"))
                ;; Re-render from branch entries
                (node/append-children! chat-container
                  (for [entry (:branch-entries event)]
                    (case (:type entry)
                      "user-message"
                      [(node/spacer)
                       (node/text (str (ansi/fg :bold "you") ": " (:text entry)))
                       (node/spacer)]

                      "assistant-text"
                      [(md/make-markdown (:text entry))
                       (node/spacer)]

                      "tool-use"
                      (let [name (:name entry)
                            args-str (str (or (:arguments entry) ""))
                            short-args (subs args-str 0 (min 80 (count args-str)))]
                        (node/text (ansi/fg :dim (str "[" name ": " short-args "]"))))

                      ;; Skip tool-result, turn-end, etc. in re-render
                      nil)))
                (add-status-message!
                 (ansi/fg :dim (str "Navigated to "
                                    (if (:editor-text event) "message (text in editor)" "branch point"))))
                (tui/render-now!))

            :session-resumed
            (do
              (when (not= @active-view "Chat")
                (switch-to-buffer! "Chat"))
              (let [{:keys [session summary messages]} event
                    ;; Build tool-use-id → tool-result lookup
                    results-by-id (into {}
                                        (comp (filter #(= :tool-result (:type %)))
                                              (map (fn [r] [(:tool-use-id r) r])))
                                        messages)]
                (add-status-message!
                 (str (ansi/fg :dim "Resumed: ")
                      (or (:name session) (:cli-session-id session) (:id session))
                      (format-session-source summary)
                      (ansi/fg :dim (str " (" (count messages) " messages)"))
                      (when (= :pi (:source session))
                        (str "\n" (ansi/fg :dim "  (read-only — Pi sessions can't be continued)")))))
                ;; Render full chat history
                ;; Track pending image count to attach to user text messages
                (let [pending-img-count (atom 0)]
                  (doseq [block messages]
                    (case (:type block)
                      :text
                      (case (:role block)
                        "user"
                        (let [n @pending-img-count
                              img-suffix (when (pos? n)
                                           (str " " (ansi/fg :dim (str "(📎 " n " image" (when (> n 1) "s") ")"))))]
                          (reset! pending-img-count 0)
                          (node/append-children! chat-container
                            [(node/spacer)
                             (node/text (str (ansi/fg :bold "you") ": " (:text block) img-suffix))
                             (node/spacer)]))
                        "assistant"
                        (do (node/append-children! chat-container
                              [(md/make-markdown (:text block))
                               (node/spacer)]))
                        nil)

                      :image
                      (swap! pending-img-count inc)

                      :tool-use
                      (let [short-name (shorten-tool-name (:name block))
                            args-str (format-tool-args short-name (:arguments block))
                            result (get results-by-id (:tool-use-id block))
                            output (:content result)
                            is-error (:is-error result)
                            grammar (tool-output-lang short-name (:arguments block))
                            output (if (and grammar (seq output) (not is-error))
                                     (highlight-text grammar output)
                                     output)
                            comp (make-static-tool-component
                                  (:name block) args-str output is-error)]
                        (node/append-children! chat-container
                          [comp (node/spacer)]))

                      ;; Skip :tool-result (rendered inline with :tool-use)
                      nil)))
                (tui/render-now!)))

            :command-result
            (case (:command event)
              "resume-list"
              (let [home (aget js/process.env "HOME")
                    shorten-cwd (fn [cwd]
                                  (when cwd
                                    (if (and home (str/starts-with? cwd home))
                                      (str "~" (subs cwd (count home)))
                                      cwd)))
                    make-items (fn [sessions scope]
                                 (mapv (fn [s]
                                         {:label (:name s)
                                          :description
                                          (str (when-let [cwd (and (= scope :all) (:cwd s))]
                                                 (str (ansi/fg :dim (shorten-cwd cwd)) " "))
                                               (:timestamp s)
                                               (when (:user-messages s)
                                                 (str " (" (:user-messages s) " msgs)"))
                                               (format-session-source s))
                                          :value (if (= scope :all)
                                                   (str "all:" (:index s))
                                                   (str (:index s)))})
                                       sessions))
                    cwd-items (make-items (:sessions event) :cwd)
                    all-items (make-items (:all-sessions event) :all)
                    active-tab (atom :cwd)
                    tab-header (fn []
                                 (let [tab @active-tab]
                                   (str "  "
                                        (if (= tab :cwd)
                                          (str (ansi/fg :accent "◉ Current Folder")
                                               (ansi/fg :dim " | ")
                                               (ansi/fg :dim "○ All"))
                                          (str (ansi/fg :dim "○ Current Folder")
                                               (ansi/fg :dim " | ")
                                               (ansi/fg :accent "◉ All")))
                                        (ansi/fg :dim "  (Tab to switch)"))))]
                (if (and (empty? cwd-items) (empty? all-items))
                  (add-status-message! "  (no previous sessions)")
                  (show-completion-menu!
                   {:items cwd-items
                    :prompt "resume> "
                    :header-fn tab-header
                    :key-bindings
                    [{:key-fn (fn [data] (= data "\t"))
                      :handler
                      (fn [state-atom update-items!]
                        (let [new-tab (if (= @active-tab :cwd) :all :cwd)]
                          (reset! active-tab new-tab)
                          (update-items! (if (= new-tab :all) all-items cwd-items))))}]
                    :on-select (fn [item]
                                 (dispatch! (str "/resume " (:value item))))})))

              "help"
              (let [all-cmds (concat (:builtin-commands event)
                                     (:extension-commands event))]
                (add-status-message!
                 (str (ansi/fg :bold "Commands:\n")
                      (str/join "\n"
                                (map (fn [c]
                                       (if (string? c)
                                         (str "  /" c)
                                         (str "  /" (:name c)
                                              (when (:desc c) (str "  — " (:desc c))))))
                                     all-cmds)))))

              "prompt"
              (do (reset! prompt-content (:text event))
                  (switch-to-buffer! "Prompt"))

              "model"
              (if (:model event)
                (add-status-message! (str "Current model: " (ansi/fg :accent (:model event))))
                (add-status-message! (:text event)))

              "debug"
              (let [text (:text event)
                    b64 (.toString (js/Buffer.from text "utf-8") "base64")]
                (term/write! (str "\033]52;c;" b64 "\007"))
                (add-status-message! (ansi/fg :dim "Debug info copied to clipboard.")))

              ;; Default for extension commands
              (when (:text event)
                (add-status-message! (:text event))))

            :model-changed
            (add-status-message! (str "Model set to: " (ansi/fg :accent (:model event))))

            :command-error
            (add-status-message! (ansi/fg :error (:text event)))

            :quit
            (shutdown!)

            :waiting-for-join
            ;; Only show room picker if user explicitly asked (via /join)
            (when @switching-rooms
              (reset! switching-rooms false)
              (let [rooms (:rooms event)
                    join! (:join! transport)]
                (if (and join! (seq rooms))
                  (let [items (into [{:label "New Room"
                                      :description "Create a fresh room"
                                      :value "new"}]
                                    (map (fn [r]
                                           {:label (:id r)
                                            :description (str (:clients r) " client(s)")
                                            :value (:id r)})
                                         rooms))]
                    (show-completion-menu!
                     {:items items
                      :prompt "room> "
                      :on-select (fn [item] (join! (:value item)))
                      :on-cancel (fn [] (join! (or (:id (first rooms)) "new")))}))
                  ;; No rooms — just create new
                  (when join! (join! "new")))))

            :room-joined
            ;; Switched to a new room — clear chat and reset turn state
            (do ((:clear chat-container))
                (reset! text-started false)
                (reset! current-md nil)
                (reset! current-tool nil)
                (reset! thinking-text "")
                (reset! current-thinking-comp nil)
                (render-launch-header! chat-container
                  {:details [{:label "Room" :value (:room-id event)}]})
                ;; Switch to Chat view if we were on another buffer
                (when (not= @active-view "Chat")
                  (switch-to-buffer! "Chat"))
                (tui/render-now!))

            :history
            (doseq [evt (:events event)]
              (@on-event-fn (update evt :type keyword)))

            ;; Unknown event — ignore
            nil))

        _ (reset! on-event-fn on-event)]

    ;; Build component tree — view-wrapper holds the active buffer view
    ((:add-child view-wrapper) chat-container)
    ((:add-child content) view-wrapper)

    ;; Set editor as pinned bottom panel
    (tui/set-bottom-panel! editor-comp)

    ;; Focus the editor
    (tui/set-focus! editor-comp)

    ;; Return client map
    {:on-event on-event
     :show-completion-menu! show-completion-menu!
     :hide-completion-menu! hide-completion-menu!

     :on-connect
     (fn [rt-or-info]
       (let [model (or (:model rt-or-info)
                       (some-> (:state rt-or-info) deref :model))
             cwd (or (:cwd rt-or-info)
                     (some-> (:state rt-or-info) deref :cwd))]
         (render-launch-header! chat-container {:model model :cwd cwd}))
       (tui/render-now!))


     :on-disconnect
     (fn [] (shutdown! {:exit? false}))}))