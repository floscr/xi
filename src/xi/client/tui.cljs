(ns xi.client.tui
  "TUI client — subscribes to runtime events and renders them
   using the TUI component system. Also handles keyboard input
   and dispatches commands back via a transport abstraction.

   Transport is a map with:
     :dispatch! (fn [command-or-string] -> promise)
     :busy?     (fn [] -> bool)"
  (:require [clojure.string :as str]
            [xi.tui.ansi :as ansi]
            [xi.tui.core :as tui]
            [xi.tui.completion :as completion]
            [xi.tui.components :as comp]
            [xi.tui.editor :as editor]
            [xi.tui.markdown :as md]))

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
  (let [bg-code "\033[48;5;236m"
        box (comp/make-box {:padding-x 1 :padding-y 0 :bg-code bg-code})
        short-args (when (seq args-summary)
                     (truncate (first (str/split-lines args-summary)) 120))
        header-text (comp/make-text
                     (str (ansi/fg :accent (str "$ " tool-name))
                          (when short-args
                            (str " " (ansi/fg :dim short-args)))))
        output-text (comp/make-text "")
        start-time (js/Date.now)]
    ((:add-child box) (comp/make-spacer 1))
    ((:add-child box) header-text)
    ((:add-child box) (comp/make-spacer 1))
    ((:add-child box) output-text)
    {:component box
     :set-output (fn [text]
                   ((:set-text output-text) text))
     :finish (fn [is-error]
               (let [elapsed (- (js/Date.now) start-time)
                     duration (str (.toFixed (/ elapsed 1000) 1) "s")
                     color (if is-error :error :success)]
                 ((:add-child box) (comp/make-spacer 1))
                 ((:add-child box)
                  (comp/make-text (ansi/fg color (str "Took " duration))))
                 ((:add-child box) (comp/make-spacer 1))
                 (tui/request-render!)))}))

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
        ;; Track last locally-submitted prompt to avoid double-rendering
        last-local-prompt (atom nil)

        ;; Completion menu state
        active-menu (atom nil)       ;; current completion menu component, or nil
        menu-spacer (atom nil)       ;; spacer component inserted before menu

        ;; Create TUI
        root (tui/create-tui!)
        chat-container (tui/make-container)
        spacer (comp/make-spacer 1)
        loader (comp/make-loader "thinking...")

        add-status-message!
        (fn [text]
          ((:add-child chat-container) (comp/make-spacer 1))
          ((:add-child chat-container) (comp/make-text text))
          ((:add-child chat-container) (comp/make-spacer 1))
          (tui/render-now!))

        ;; ── Completion Menu Helpers ──────────────────────────────────────────

        ;; Forward-declared so hide/show-completion-menu! can reference editor
        editor-comp-ref (atom nil)

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

        ;; Editor at the bottom
        editor-comp
        (editor/make-editor
         {:prompt "xi> "
          :on-submit (fn [text]
                       (let [text (str/trim text)]
                         (when (and (seq text) (not (busy?)))
                           ;; Remember we sent this so :user-message doesn't double-render
                           (reset! last-local-prompt text)
                           ;; Dispatch via transport
                           (dispatch! text))))

          :on-escape (fn []
                       (when (busy?)
                         (dispatch! {:type :abort})))

          :on-interrupt (fn []
                          (tui/stop-tui!)
                          (println "Bye.")
                          (js/process.exit 0))})

        ;; Wire up forward reference
        _ (reset! editor-comp-ref editor-comp)

        ;; Event handler — maps runtime events to TUI mutations
        on-event
        (fn [event]
          (case (:type event)
            :ready
            nil ;; Header rendered separately

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
              (reset! current-tool nil))

            :turn-start
            (do ((:add-child chat-container) loader)
                ((:start loader))
                (tui/render-now!))

            :text-delta
            (do (when-not @text-started
                  ;; Remove loader, start markdown component
                  ((:stop loader))
                  ((:remove-child chat-container) loader)
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
            nil

            :tool-start
            (do ;; Stop loader if still showing
                (when-not @text-started
                  ((:stop loader))
                  ((:remove-child chat-container) loader))
                (reset! text-started false)
                (reset! current-md nil)
                ;; Create tool component
                (let [args-str (format-tool-args (:name event) (:arguments event))
                      tool-comp (make-tool-component (:name event) args-str)]
                  ((:add-child chat-container) (:component tool-comp))
                  (reset! current-tool tool-comp))
                (tui/render-now!))

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
                ;; Show loader for next iteration
                ((:add-child chat-container) loader)
                ((:start loader))
                (tui/render-now!))

            :error
            (do ((:stop loader))
                ((:remove-child chat-container) loader)
                (let [err (:error event)
                      err-msg (comp/make-text
                               (str (ansi/fg :error "[Error]") " "
                                    (ansi/fg :dim (pr-str err))))]
                  ((:add-child chat-container) err-msg)
                  ((:add-child chat-container) (comp/make-spacer 1)))
                (tui/render-now!))

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
            (do (tui/stop-tui!)
                (println "Bye.")
                (js/process.exit 0))

            ;; Unknown event — ignore
            nil))]

    ;; Build component tree
    ((:add-child root) chat-container)
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
     (fn []
       (tui/stop-tui!))}))
