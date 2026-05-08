(ns xi.cli
  "Xi entry point — TUI-based interactive agent."
  (:require [clojure.string :as str]
            [xi.ext.core :as ext]
            [xi.ext.commit :as ext-commit]
            [xi.ext.done-notify :as ext-done-notify]
            [xi.ext.kb :as ext-kb]
            [xi.ext.parmezan :as ext-parmezan]
            [xi.ext.permission-gate :as ext-permission-gate]
            [xi.ext.plan-mode :as ext-plan-mode]
            [xi.ext.terminal-title :as ext-terminal-title]
            [xi.ext.web :as ext-web]
            [xi.loop :as loop]
            [xi.session :as session]
            [xi.tui.ansi :as ansi]
            [xi.tui.core :as tui]
            [xi.tui.components :as comp]
            [xi.tui.editor :as editor]
            [xi.tui.markdown :as md]))

(def ^:private DEFAULT_MODEL "claude-sonnet-4-20250514")

(defn- load-settings []
  (try
    (let [path (str (aget js/process.env "HOME") "/.pi/agent/settings.json")
          content (.readFileSync (js/require "node:fs") path "utf8")]
      (js->clj (js/JSON.parse content) :keywordize-keys true))
    (catch :default _e {})))

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
    ;; Default: skip dumping the full map
    nil))

(defn- truncate-output
  "Truncate tool output to max lines."
  [text max-lines]
  (let [lines (str/split-lines text)]
    (if (<= (count lines) max-lines)
      text
      (str (str/join "\n" (take max-lines lines))
           "\n" (ansi/fg :dim (str "... (" (- (count lines) max-lines) " more lines)"))))))

(defn- truncate [s max-len]
  (if (> (count s) max-len)
    (str (subs s 0 max-len) "...")
    s))

;; ── Tool Execution Component ─────────────────────────────────────────────────

(defn- make-tool-component
  "Create a tool execution display component (box with header, output, footer)."
  [tool-name args-summary]
  (let [bg-fn (fn [text] (str "\033[48;5;236m" text "\033[0m"))
        box (comp/make-box {:padding-x 1 :padding-y 0 :bg-fn bg-fn})
        ;; Truncate long args for the header line
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

;; ── Session Management ───────────────────────────────────────────────────────

(defn- format-session-source [s]
  (case (:source s)
    :xi     ""
    :claude (ansi/fg :dim " [claude]")
    :pi     (ansi/fg :dim " [pi]")
    ""))

(defn- format-session-list [sessions]
  (if (empty? sessions)
    "  (no previous sessions)"
    (str/join "\n"
              (map-indexed
               (fn [i s]
                 (str "  " (inc i) ". "
                      (or (:name s) "(unnamed)")
                      " — " (:timestamp s)
                      (when (:user-messages s) (str " (" (:user-messages s) " msgs)"))
                      (format-session-source s)))
               (take 10 sessions)))))

;; ── Agent Turn ───────────────────────────────────────────────────────────────

(defn- run-agent-turn
  "Run one agent turn. Adds components to chat container as events stream in."
  [chat-container sess loader prompt model]
  (let [text-started (atom false)
        current-md (atom nil)
        current-tool (atom nil)
        cli-session-id (:cli-session-id sess)]

    ;; Show loader
    ((:add-child chat-container) loader)
    ((:start loader))
    (tui/render-now!)

    (-> (loop/run-turn
         (cond-> {:model model
                  :prompt prompt
                  :on-text (fn [text]
                             (when-not @text-started
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
                                     new-text (str prev text)]
                                 ((:set-text m) new-text))))

                  :on-thinking (fn [_text] nil)

                  :on-tool-start (fn [{:keys [name arguments]}]
                                   ;; Stop loader if still showing
                                   (when-not @text-started
                                     ((:stop loader))
                                     ((:remove-child chat-container) loader))
                                   (reset! text-started false)
                                   (reset! current-md nil)

                                   ;; Create tool component
                                   (let [args-str (format-tool-args name arguments)
                                         tool-comp (make-tool-component name args-str)]
                                     ((:add-child chat-container) (:component tool-comp))
                                     (reset! current-tool tool-comp))
                                   (tui/render-now!))

                  :on-tool-result (fn [{:keys [content is-error]}]
                                    (when-let [tool @current-tool]
                                      (let [text (cond
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
                                      ((:finish tool) is-error))
                                    (reset! current-tool nil)
                                    ;; Show loader for next iteration
                                    ((:add-child chat-container) loader)
                                    ((:start loader))
                                    (tui/render-now!))

                  :on-error (fn [err]
                              ((:stop loader))
                              ((:remove-child chat-container) loader)
                              (let [err-msg (comp/make-text
                                             (str (ansi/fg :error "[Rate limit]") " "
                                                  (ansi/fg :dim (pr-str err))))]
                                ((:add-child chat-container) err-msg)
                                ((:add-child chat-container) (comp/make-spacer 1)))
                              (tui/render-now!))}
           cli-session-id (assoc :resume-session-id cli-session-id)))

        (.then (fn [result]
                 ;; Clean up loader
                 ((:stop loader))
                 ((:remove-child chat-container) loader)
                 (tui/render-now!)
                 result)))))

;; ── Command Handling ─────────────────────────────────────────────────────────

(defn- add-status-message!
  "Add a temporary status message to chat."
  [chat-container text]
  ((:add-child chat-container) (comp/make-spacer 1))
  ((:add-child chat-container) (comp/make-text text))
  ((:add-child chat-container) (comp/make-spacer 1))
  (tui/render-now!))

(defn- handle-command
  "Handle slash commands. Returns true if handled."
  [input chat-container sess cwd model]
  (cond
    (= "/quit" input)
    (do (tui/stop-tui!)
        (println "Bye.")
        (js/process.exit 0)
        true)

    (or (= "/sessions" input) (= "/ls" input))
    (do (add-status-message! chat-container
                             (str (ansi/fg :bold "Recent sessions:\n")
                                  (format-session-list (session/list-sessions cwd))))
        true)

    (= "/help" input)
    (do (add-status-message! chat-container
                             (str (ansi/fg :bold "Commands:\n")
                                  "  /sessions    — list recent sessions\n"
                                  "  /resume [n]  — resume a previous session\n"
                                  "  /model       — show current model\n"
                                  "  /clear       — start new session\n"
                                  "  /help        — show this help\n"
                                  "  /quit        — exit\n"
                                  (str/join "\n"
                                            (map (fn [{:keys [name desc]}]
                                                   (str "  /" name (when desc (str "  — " desc))))
                                                 (ext/list-commands)))))
        true)

    (str/starts-with? input "/model")
    (let [parts (str/split input #"\s+" 2)]
      (if (= 1 (count parts))
        (add-status-message! chat-container
                             (str "Current model: " (ansi/fg :accent model)))
        (add-status-message! chat-container
                             (str "Model change not yet supported at runtime. "
                                  "Set XI_MODEL=" (second parts) " env var.")))
      true)

    (= "/clear" input)
    (do (reset! sess (session/create-session cwd))
        ((:clear chat-container))
        (add-status-message! chat-container
                             (ansi/fg :dim "New session started."))
        true)

    (= "/resume" input)
    (let [sessions (session/list-sessions cwd)]
      (add-status-message! chat-container
                           (if (empty? sessions)
                             "  (no previous sessions)"
                             (str (ansi/fg :bold "Recent sessions (enter /resume N):\n")
                                  (format-session-list sessions))))
      true)

    (str/starts-with? input "/resume ")
    (let [arg (str/trim (subs input 8))
          n (js/parseInt arg 10)
          sessions (session/list-sessions cwd)]
      (if (and (not (js/isNaN n)) (<= 1 n) (<= n (count sessions)))
        (let [summary (nth sessions (dec n))
              loaded (session/load-session summary)
              messages (session/read-session-messages summary)]
          (reset! sess loaded)
          (add-status-message! chat-container
                               (str (ansi/fg :dim "Resumed: ")
                                    (or (:name loaded) (:cli-session-id loaded) (:id loaded))
                                    (format-session-source summary)
                                    (ansi/fg :dim (str " (" (count messages) " messages)"))
                                    (when (= :pi (:source loaded))
                                      (str "\n" (ansi/fg :dim "  (read-only — Pi sessions can't be continued)")))
                                    "\n"
                                    ;; Show recent messages
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
        (add-status-message! chat-container
                             (ansi/fg :error "Session not found.")))
      true)

    ;; Extension commands
    (and (str/starts-with? input "/")
         (ext/get-command (subs (first (str/split input #"\s")) 1)))
    (let [cmd-name (subs (first (str/split input #"\s")) 1)
          {:keys [handler]} (ext/get-command cmd-name)
          ctx {:session @sess :model model :cwd cwd}]
      (handler ctx)
      true)

    :else false))

;; ── Extensions ───────────────────────────────────────────────────────────────

(defn- register-extensions! []
  (doseq [ext [ext-plan-mode/extension
               ext-permission-gate/extension
               ext-kb/extension
               ext-commit/extension
               ext-web/extension
               ext-parmezan/extension
               ext-done-notify/extension
               ext-terminal-title/extension]]
    (ext/register-extension! ext)))

;; ── Main ─────────────────────────────────────────────────────────────────────

(defn main []
  (register-extensions!)
  (let [settings (load-settings)
        model (or (aget js/process.env "XI_MODEL")
                  (:defaultModel settings)
                  DEFAULT_MODEL)
        cwd (.cwd js/process)
        sess (atom (session/create-session cwd))

        ;; Create TUI
        root (tui/create-tui!)
        chat-container (tui/make-container)
        spacer (comp/make-spacer 1)
        loader (comp/make-loader "thinking...")

        busy (atom false)

        ;; Editor at the bottom
        editor-comp (editor/make-editor
                     {:prompt "xi> "
                      :on-submit (fn [text]
                                   (let [text (str/trim text)]
                                     (when (and (seq text) (not @busy))
                                       ;; Add user message to chat
                                       ((:add-child chat-container) (comp/make-spacer 1))
                                       ((:add-child chat-container)
                                        (comp/make-text (str (ansi/fg :bold "you") ": " text)))
                                       ((:add-child chat-container) (comp/make-spacer 1))
                                       (tui/render-now!)

                                       (if (handle-command text chat-container sess cwd model)
                                         (tui/render-now!)
                                         ;; Run agent turn
                                         (do (reset! busy true)
                                             (-> (run-agent-turn chat-container @sess loader text model)
                                                 (.then (fn [result]
                                                          (reset! busy false)
                                                          (when-let [sid (:session-id result)]
                                                            (swap! sess assoc :cli-session-id sid)
                                                            (when-not (:name @sess)
                                                              (swap! sess assoc :name (subs text 0 (min 60 (count text)))))
                                                            (session/save-session! @sess))
                                                          (tui/render-now!)))
                                                 (.catch (fn [err]
                                                           (reset! busy false)
                                                           ((:stop loader))
                                                           ((:remove-child chat-container) loader)
                                                           (add-status-message! chat-container
                                                                                (str (ansi/fg :error "[Error]") " " (.-message err)))
                                                           (tui/render-now!)))))))))

                      :on-interrupt (fn []
                                      (tui/stop-tui!)
                                      (println "Bye.")
                                      (js/process.exit 0))})]

    ;; Build component tree
    ;; Header
    ((:add-child chat-container)
     (comp/make-text (str (ansi/fg :bold "Xi") " " (ansi/fg :dim "— coding agent"))))
    ((:add-child chat-container)
     (comp/make-text (str (ansi/fg :dim "Model: ") (ansi/fg :accent model))))
    ((:add-child chat-container)
     (comp/make-text (ansi/fg :dim "Type /quit to exit, /help for commands.")))
    ((:add-child chat-container) (comp/make-spacer 1))

    ;; Add to root: chat + spacer + editor
    ((:add-child root) chat-container)
    ((:add-child root) spacer)
    ((:add-child root) editor-comp)

    ;; Focus the editor
    (tui/set-focus! editor-comp)

    ;; Initial render
    (tui/render-now!)))
