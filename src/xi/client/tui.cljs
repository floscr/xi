(ns xi.client.tui
  "TUI client — subscribes to runtime events and renders them
   using the TUI component system. Also handles keyboard input
   and dispatches commands back via a transport abstraction.

   Transport is a map with:
     :dispatch! (fn [command-or-string] -> promise)
     :busy?     (fn [] -> bool)"
  (:require [clojure.string :as str]
            [xi.tui.ansi :as ansi]
            [xi.tui.buffers :as buffers]
            [xi.tui.command-palette :as palette]
            [xi.tui.core :as tui]
            [xi.tui.completion :as completion]
            [xi.tui.components :as comp]
            [xi.tui.editor :as editor]
            [xi.tui.markdown :as md]
            [xi.tui.terminal :as term]))

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

(defn- truncate [s max-len]
  (if (> (count s) max-len)
    (str (subs s 0 max-len) "...")
    s))

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
        short-args (when (seq args-summary)
                     (truncate (first (str/split-lines args-summary)) 120))
        header-text (comp/make-text
                     (str (ansi/fg :accent (str "$ " tool-name))
                          (when short-args
                            (str " " short-args))))
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
                      (let [short (when (seq new-args-summary)
                                    (truncate (first (str/split-lines new-args-summary)) 120))]
                        ((:set-text header-text)
                         (str (ansi/fg :accent (str "$ " new-tool-name))
                              (when short
                                (str " " short))))))
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

(defn- format-session-list-text [sessions]
  (if (empty? sessions)
    "  (no previous sessions)"
    (str/join "\n"
              (map (fn [s]
                     (str "  " (:index s) ". "
                          (:name s)
                          " — " (:timestamp s)
                          (when (:user-messages s) (str " (" (:user-messages s) " msgs)"))
                          (format-session-source s)))
                   sessions))))

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
        ;; Track last locally-submitted prompt to avoid double-rendering
        last-local-prompt (atom nil)

        ;; Completion menu state
        active-menu (atom nil)       ;; current completion menu component, or nil
        menu-spacer (atom nil)       ;; spacer component inserted before menu

        ;; Session buffers — capture stray stdout/stderr
        buffer-mgr (or (:buffer-mgr opts) (buffers/create-manager ["Logs"]))

        ;; Create TUI
        root (tui/create-tui!)
        chat-container (tui/make-container)
        view-wrapper (tui/make-container)  ;; holds the active buffer view
        active-view (atom "Chat")          ;; "Chat" or "Logs"
        spacer (comp/make-spacer 1)
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

        switch-to-buffer!
        (fn [name]
          ((:clear view-wrapper))
          (reset! active-view name)
          (case name
            "Chat" ((:add-child view-wrapper) chat-container)
            "Logs" ((:add-child view-wrapper) (build-logs-view!)))
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

        hide-completion-menu!
        (fn []
          (when-let [menu @active-menu]
            ;; Remove menu + its spacer from root
            ((:remove-child root) menu)
            (when-let [ms @menu-spacer]
              ((:remove-child root) ms))
            (reset! active-menu nil)
            (reset! menu-spacer nil)
            ;; Restore the editor
            ((:add-child root) @editor-comp-ref)
            (tui/set-focus! @editor-comp-ref)
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
            ;; Replace the editor with the menu — menu has its own prompt at the bottom
            ((:remove-child root) @editor-comp-ref)
            ((:add-child root) ms)
            ((:add-child root) menu)
            ;; Focus the menu (editor is hidden until menu is dismissed)
            (tui/set-focus! menu)
            (tui/render-now!)))

        ;; ── Local Command Handling ─────────────────────────────────────────────
        ;; Commands handled entirely in the TUI client (no runtime round-trip).

        handle-local-command!
        (fn [text]
          (cond
            (= text "/buffers")
            (let [items [{:label "Chat"
                          :description (when (= @active-view "Chat") "• active")
                          :value "Chat"}
                         {:label "Logs"
                          :description (let [n (count (buffers/get-entries buffer-mgr "Logs"))]
                                         (str n " entries"
                                              (when (= @active-view "Logs") " • active")))
                          :value "Logs"}]]
              (show-completion-menu!
               {:items items
                :prompt "buffer> "
                :on-select (fn [item]
                             (switch-to-buffer! (:value item)))})
              true)

            (= text "/palette")
            (do (@open-palette-fn) true)

            (str/starts-with? text "/palette ")
            (let [rest-text (str/trim (subs text 9))
                  [sub arg] (str/split rest-text #"\s+" 2)]
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
                (add-status-message! (ansi/fg :error "Usage: /palette add|remove|list <command>")))
              true)

            :else false))

        open-palette!
        (fn []
          (let [items (palette/build-items)]
            (show-completion-menu!
             {:items items
              :prompt "palette> "
              :on-select (fn [item]
                           (let [cmd (:value item)]
                             (palette/record-use! cmd)
                             (when-not (handle-local-command! cmd)
                               (dispatch! cmd))))})))
        _ (reset! open-palette-fn open-palette!)

        ;; Editor at the bottom
        editor-comp
        (editor/make-editor
         {:prompt "xi> "
          :on-submit (fn [text]
                       (let [text (str/trim text)]
                         (when (seq text)
                           (when (str/starts-with? text "/")
                             (palette/record-use! text))
                           ;; Try local commands first (work even when agent is busy)
                           (when-not (handle-local-command! text)
                             (when-not (busy?)
                               ;; Auto-switch to Chat if viewing another buffer
                               (when (not= @active-view "Chat")
                                 (switch-to-buffer! "Chat"))
                               ;; Remember we sent this so :user-message doesn't double-render
                               (reset! last-local-prompt text)
                               ;; Dispatch via transport
                               (dispatch! text))))))

          :on-escape (fn []
                       (when (busy?)
                         (dispatch! {:type :abort})))

          :on-interrupt (fn [] (shutdown!))

          :on-palette open-palette!})

        ;; Wire up forward reference
        _ (reset! editor-comp-ref editor-comp)

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
               (comp/make-text (str (ansi/fg :bold "you") ": " text)))
              ((:add-child chat-container) (comp/make-spacer 1))
              (tui/render-now!)
              ;; Clear after matching
              (when is-local
                (reset! last-local-prompt nil)))

            :busy-changed
            (when (:busy event)
              ;; Reset turn state for new turn
              (reset! text-started false)
              (reset! current-md nil)
              (reset! current-tool nil)
              (reset! thinking-text "")
              ((:clear-thinking loader)))

            :turn-start
            (do (reset! thinking-text "")
                ((:clear-thinking loader))
                ((:add-child chat-container) loader)
                ((:start loader))
                (tui/render-now!))

            :text-delta
            (do (when-not @text-started
                  ;; Persist thinking block as static text before removing loader
                  (let [thought @thinking-text]
                    (reset! thinking-text "")
                    ((:clear-thinking loader))
                    ((:stop loader))
                    ((:remove-child chat-container) loader)
                    (when (seq thought)
                      ((:add-child chat-container)
                       (comp/make-text (ansi/fg :dim thought)))
                      ((:add-child chat-container) (comp/make-spacer 1))))
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
              ((:set-thinking loader) @thinking-text))

            :tool-start
            (do ;; Persist thinking block before removing loader
                (let [thought @thinking-text]
                  (reset! thinking-text "")
                  ((:clear-thinking loader))
                  (when-not @text-started
                    ((:stop loader))
                    ((:remove-child chat-container) loader)
                    (when (seq thought)
                      ((:add-child chat-container)
                       (comp/make-text (ansi/fg :dim thought)))
                      ((:add-child chat-container) (comp/make-spacer 1)))))
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
                ;; Show loader for next iteration (clear stale thinking text)
                (reset! thinking-text "")
                ((:clear-thinking loader))
                ((:add-child chat-container) loader)
                ((:start loader))
                (tui/render-now!))

            :error
            (let [err (:error event)
                  msg (or (:message err) (pr-str err))]
              ;; Suppress spurious SDK errors during abort
              (when-not (and msg (str/includes? msg "null is not an object"))
                ((:stop loader))
                ((:remove-child chat-container) loader)
                ((:add-child chat-container)
                 (comp/make-text
                  (str (ansi/fg :error "[Error]") " "
                       (ansi/fg :dim msg))))
                ((:add-child chat-container) (comp/make-spacer 1))
                (tui/render-now!)))

            :turn-end
            (do ((:stop loader))
                ((:remove-child chat-container) loader)
                (tui/render-now!))

            :aborted
            (do ((:stop loader))
                ((:remove-child chat-container) loader)
                (add-status-message! (ansi/fg :dim "Interrupted."))
                (tui/render-now!))

            :session-cleared
            (do ((:clear chat-container))
                (reset! buffer-mgr {"Logs" []})
                (when (not= @active-view "Chat")
                  (switch-to-buffer! "Chat"))
                (add-status-message! (ansi/fg :dim "New session started.")))

            :session-resumed
            (let [{:keys [session summary messages]} event]
              (add-status-message!
               (str (ansi/fg :dim "Resumed: ")
                    (or (:name session) (:cli-session-id session) (:id session))
                    (format-session-source summary)
                    (ansi/fg :dim (str " (" (count messages) " messages)"))
                    (when (= :pi (:source session))
                      (str "\n" (ansi/fg :dim "  (read-only — Pi sessions can't be continued)")))
                    "\n"
                    (let [recent (take-last 6 messages)]
                      (str (when (> (count messages) (count recent))
                             (str (ansi/fg :dim (str "  ... (" (- (count messages) (count recent)) " earlier messages)")) "\n"))
                           (str/join "\n"
                                     (map (fn [{:keys [role text]}]
                                            (case role
                                              "user" (str (ansi/fg :bold "you") ": " (truncate text 200))
                                              "assistant" (str (ansi/fg :accent "xi") ": " (truncate text 500))
                                              ""))
                                          recent)))))))

            :command-result
            (case (:command event)
              "sessions"
              (add-status-message!
               (str (ansi/fg :bold "Recent sessions:\n")
                    (format-session-list-text (:sessions event))))

              "resume-list"
              (if (empty? (:sessions event))
                (add-status-message! "  (no previous sessions)")
                (let [items (mapv (fn [s]
                                   {:label (:name s)
                                    :description (str (:timestamp s)
                                                      (when (:user-messages s)
                                                        (str " (" (:user-messages s) " msgs)"))
                                                      (format-session-source s))
                                    :value (:index s)})
                                 (:sessions event))]
                  (show-completion-menu!
                   {:items items
                    :prompt "resume> "
                    :on-select (fn [item]
                                 (dispatch! (str "/resume " (:value item))))})))

              "help"
              (add-status-message!
               (str (ansi/fg :bold "Commands:\n")
                    (str/join "\n"
                              (map #(str "  /" %) (:builtin-commands event)))
                    (when (seq (:extension-commands event))
                      (str "\n"
                           (str/join "\n"
                                     (map (fn [{:keys [name desc]}]
                                            (str "  /" name (when desc (str "  — " desc))))
                                          (:extension-commands event)))))))

              "model"
              (if (:model event)
                (add-status-message! (str "Current model: " (ansi/fg :accent (:model event))))
                (add-status-message! (:text event)))

              ;; Default for extension commands
              (when (:text event)
                (add-status-message! (:text event))))

            :command-error
            (add-status-message! (ansi/fg :error (:text event)))

            :quit
            (shutdown!)

            :history
            (doseq [evt (:events event)]
              (@on-event-fn (update evt :type keyword)))

            ;; Unknown event — ignore
            nil))

        _ (reset! on-event-fn on-event)]

    ;; Build component tree — view-wrapper holds the active buffer view
    ((:add-child view-wrapper) chat-container)
    ((:add-child root) view-wrapper)
    ((:add-child root) spacer)
    ((:add-child root) editor-comp)

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
