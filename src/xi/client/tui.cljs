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
            [xi.tui.ansi :as ansi]
            [xi.tui.buffers :as buffers]
            [xi.tui.command-palette :as palette]
            [xi.tui.core :as tui]
            [xi.tui.completion :as completion]
            [xi.tui.components :as comp]
            [xi.tui.editor :as editor]
            [xi.tui.clipboard-image :as clip-image]
            [xi.tui.markdown :as md]
            [xi.tui.terminal :as term]
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
        header-text (comp/make-text
                     (str (ansi/fg :accent (str "$ " tool-name))
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
                         (str (ansi/fg :accent (str "$ " new-tool-name))
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
                     color (if is-error :error :success)]
                 ((:add-child box) (comp/make-spacer 1))
                 ((:add-child box)
                  (comp/make-text (ansi/fg color (str "Took " duration))))
                 (tui/request-render!)))}))


(defn- make-static-tool-component
  "Create a static tool display for history replay (no spinner/timer)."
  [tool-name args-summary output is-error]
  (let [bg-code "\033[48;2;38;44;55m"
        box (comp/make-box {:padding-x 1 :padding-y 0 :bg-code bg-code})
        short-name (shorten-tool-name tool-name)
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
    ((:add-child box) header-text)
    (when (seq output)
      ((:add-child box) (comp/make-spacer 1))
      ((:add-child box) (comp/make-text (truncate-output output 20))))
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
        loader (comp/make-loader "thinking...")

        ;; ── Buffer View Switching ───────────────────────────────────────────

        build-logs-view!
        (fn []
          (let [c (tui/make-container)
                entries (buffers/get-entries buffer-mgr "Logs")]
            ((:add-child c)
             (comp/make-text (str (ansi/fg :bold "Logs")
                                 (ansi/fg :dim (str " (" (count entries) " entries)")))))
            ((:add-child c) (comp/make-spacer 1))
            (if (empty? entries)
              ((:add-child c) (comp/make-text (ansi/fg :dim "(empty)")))
              (doseq [entry (take-last 100 entries)]
                ((:add-child c)
                 (comp/make-text (str (ansi/fg :dim (format-timestamp (:timestamp entry)))
                                     " " (str/trim-newline (:text entry)))))))
            ((:add-child c) (comp/make-spacer 1))
            c))

        build-prompt-view!
        (fn []
          (let [c (tui/make-container)
                content (or @prompt-content "(no prompt loaded)")]
            ((:add-child c)
             (comp/make-text (ansi/fg :bold "System Prompt")))
            ((:add-child c) (comp/make-spacer 1))
            ((:add-child c) (comp/make-text content))
            ((:add-child c) (comp/make-spacer 1))
            c))

        switch-to-buffer!
        (fn [name]
          ((:clear view-wrapper))
          (reset! active-view name)
          (case name
            "Chat" ((:add-child view-wrapper) chat-container)
            "Logs" ((:add-child view-wrapper) (build-logs-view!))
            "Prompt" ((:add-child view-wrapper) (build-prompt-view!)))
          (tui/render-now!))

        add-status-message!
        (fn [text]
          ((:add-child chat-container) (comp/make-spacer 1))
          ((:add-child chat-container) (comp/make-text text))
          ((:add-child chat-container) (comp/make-spacer 1))
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
                                             :value "Prompt"}))]
                          (show-completion-menu!
                           {:items items
                            :prompt "buffer> "
                            :on-select (fn [item]
                                         (switch-to-buffer! (:value item)))}))
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
                        nil)}])

        ;; ── Local Command Dispatch ──────────────────────────────────────────────
        ;; Check the central registry for :client-scoped commands.

        handle-local-command!
        (fn [text]
          (when (str/starts-with? text "/")
            (let [parts (str/split (subs text 1) #"\s+" 2)
                  cmd-name (first parts)
                  args (when (second parts) (str/trim (second parts)))]
              (when-let [cmd (cmd-registry/get-command cmd-name :client)]
                (let [result ((:handler cmd) {:args args})]
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
                                   (reset! queued-prompt payload))))))))

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
                       (if (tui/scrolled-up?)
                         (tui/scroll-to-bottom!)
                         (when (busy?)
                           (dispatch! {:type :abort}))))

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
              ((:add-child chat-container)
               (comp/make-text
                (str (ansi/fg :dim "Loaded ") (ansi/fg :accent (str (count agents-files) " AGENTS.md"))
                     (ansi/fg :dim (str " file" (when (> (count agents-files) 1) "s"))))))
              ((:add-child chat-container) (comp/make-spacer 1))
              (tui/render-now!))

            :user-message
            (let [text (:text event)
                  is-local (= text @last-local-prompt)]
              ;; Always show the message — local or remote
              ((:add-child chat-container) (comp/make-spacer 1))
              ((:add-child chat-container)
               (comp/make-text (str (ansi/fg :bold "you") ": " text
                                    (when (seq (:images event))
                                      (str " " (ansi/fg :dim (str "(" (count (:images event)) " image(s))")))))))                                  
              ((:add-child chat-container) (comp/make-spacer 1))
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
            (do (when-not @text-started
                  ((:stop loader))
                  ((:remove-child chat-container) loader)
                  ;; Add spacer after thinking if it had content
                  (when (seq @thinking-text)
                    ((:add-child chat-container) (comp/make-spacer 1))))
                (reset! current-thinking-comp nil)
                (reset! text-started false)
                (reset! current-md nil)
                ;; Create tool component
                (let [args-str (format-tool-args (:name event) (:arguments event))
                      tool-comp (make-tool-component (:name event) args-str)]
                  ((:add-child chat-container) (:component tool-comp))
                  (reset! current-tool tool-comp))
                (tui/render-now!))

            :tool-args
            (when-let [tool @current-tool]
              (let [args-str (format-tool-args (:name event) (:arguments event))]
                ((:update-header tool) (:name event) args-str)))

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
                      ((:set-output tool)
                       (truncate-output text 20))))
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
                ((:add-child chat-container)
                 (comp/make-text
                  (str (ansi/fg :error "[Error]") " "
                       (ansi/fg :dim msg))))
                ((:add-child chat-container) (comp/make-spacer 1))
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
                (add-status-message! (ansi/fg :dim "New session started.")))

            :compact-start
            (add-status-message! (ansi/fg :dim "Compacting conversation..."))

            :session-compacted
            (do ((:clear chat-container))
                (reset! buffer-mgr {"Logs" []})
                (when (not= @active-view "Chat")
                  (switch-to-buffer! "Chat"))
                (add-status-message! (ansi/fg :dim "Session compacted. Summary preserved as context.")))

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
                          ((:add-child chat-container) (comp/make-spacer 1))
                          ((:add-child chat-container)
                           (comp/make-text (str (ansi/fg :bold "you") ": " (:text block) img-suffix)))
                          ((:add-child chat-container) (comp/make-spacer 1)))
                        "assistant"
                        (do ((:add-child chat-container) (md/make-markdown (:text block)))
                            ((:add-child chat-container) (comp/make-spacer 1)))
                        nil)

                      :image
                      (swap! pending-img-count inc)

                      :tool-use
                      (let [short-name (shorten-tool-name (:name block))
                            args-str (format-tool-args short-name (:arguments block))
                            result (get results-by-id (:tool-use-id block))
                            output (:content result)
                            is-error (:is-error result)
                            comp (make-static-tool-component
                                  (:name block) args-str output is-error)]
                        ((:add-child chat-container) comp)
                        ((:add-child chat-container) (comp/make-spacer 1)))

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
                ;; Add header for new room
                ((:add-child chat-container)
                 (comp/make-text (str (ansi/fg :bold "Xi") " " (ansi/fg :dim "— coding agent"))))
                ((:add-child chat-container)
                 (comp/make-text (str (ansi/fg :dim "Room: ")
                                     (ansi/fg :accent (:room-id event)))))
                ((:add-child chat-container)
                 (comp/make-text (ansi/fg :dim "Type /quit to exit, /help for commands.")))
                ((:add-child chat-container) (comp/make-spacer 1))
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
       ;; Add header — works with both runtime map and plain info map
       (let [model (or (:model rt-or-info)
                       (some-> (:state rt-or-info) deref :model))]
         ((:add-child chat-container)
          (comp/make-text (str (ansi/fg :bold "Xi") " " (ansi/fg :dim "— coding agent"))))
         (when model
           ((:add-child chat-container)
            (comp/make-text (str (ansi/fg :dim "Model: ") (ansi/fg :accent model)))))
         ((:add-child chat-container)
          (comp/make-text (ansi/fg :dim "Type /quit to exit, /help for commands.")))
         ((:add-child chat-container) (comp/make-spacer 1)))
       (tui/render-now!))


     :on-disconnect
     (fn [] (shutdown! {:exit? false}))}))
