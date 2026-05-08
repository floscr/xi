(ns xi.cli
  "Xi entry point — interactive agent REPL."
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
            [xi.tui.render :as render]
            ["node:readline" :as readline]))

(def ^:private DEFAULT_MODEL "claude-sonnet-4-20250514")

(defn- load-settings []
  (try
    (let [path (str (aget js/process.env "HOME") "/.pi/agent/settings.json")
          content (.readFileSync (js/require "node:fs") path "utf8")]
      (js->clj (js/JSON.parse content) :keywordize-keys true))
    (catch :default _e {})))

(defn- format-tool-args
  "Format tool arguments for display in the tool header."
  [name arguments]
  (case name
    "Bash"  (get arguments "command" (get arguments :command ""))
    "Read"  (get arguments "file_path" (get arguments :file_path ""))
    "Write" (get arguments "file_path" (get arguments :file_path ""))
    "Edit"  (get arguments "file_path" (get arguments :file_path ""))
    "Grep"  (str (get arguments "pattern" (get arguments :pattern ""))
                 (when-let [g (or (get arguments "glob") (get arguments :glob))]
                   (str " --glob " g)))
    "Glob"  (get arguments "pattern" (get arguments :pattern ""))
    (let [s (pr-str arguments)]
      (when (> (count s) 2) s))))

(defn- truncate-output
  "Truncate tool output to max lines."
  [text max-lines]
  (let [lines (str/split-lines text)]
    (if (<= (count lines) max-lines)
      text
      (str (str/join "\n" (take max-lines lines))
           "\n" (render/fg :dim (str "... (" (- (count lines) max-lines) " more lines)"))))))

(defn- run-agent-turn [sess prompt model]
  (let [text-started (atom false)
        tool-start-time (atom nil)
        cli-session-id (:cli-session-id sess)]
    (render/start-spinner "thinking...")
    (-> (loop/run-turn
         (cond-> {:model model
                  :prompt prompt
                  :on-text (fn [text]
                             (when-not @text-started
                               (render/stop-spinner)
                               (println)
                               (reset! text-started true))
                             (js/process.stdout.write text))
                  :on-thinking (fn [_text] nil)
                  :on-tool-start (fn [{:keys [name arguments]}]
                                   (render/stop-spinner)
                                   (reset! text-started false)
                                   (let [args-str (format-tool-args name arguments)]
                                     (reset! tool-start-time
                                             (render/tool-header name args-str))))
                  :on-tool-result (fn [{:keys [content is-error]}]
                                    (when (and (string? content) (seq content))
                                      (render/tool-output-line
                                       (truncate-output content 20)))
                                    (render/tool-footer
                                     (or @tool-start-time (js/Date.now))
                                     {:is-error is-error})
                                    (reset! tool-start-time nil))
                  :on-error (fn [err]
                              (render/stop-spinner)
                              (println (str (render/fg :error "[Rate limit]") " "
                                            (render/fg :dim (pr-str err)))))}
           cli-session-id (assoc :resume-session-id cli-session-id)))
        (.then (fn [result]
                 (render/stop-spinner)
                 result)))))

(defn- read-line-prompt [rl]
  (js/Promise.
   (fn [resolve _reject]
     (.question ^js rl "\nxi> "
                (fn [answer] (resolve answer))))))

(defn- format-session-source [s]
  (case (:source s)
    :xi     ""
    :claude (render/fg :dim " [claude]")
    :pi     (render/fg :dim " [pi]")
    ""))

(defn- print-session-list [sessions]
  (if (empty? sessions)
    (println "  (no previous sessions)")
    (doseq [[i s] (map-indexed vector (take 10 sessions))]
      (println (str "  " (inc i) ". "
                    (or (:name s) "(unnamed)")
                    " — " (:timestamp s)
                    (when (:user-messages s) (str " (" (:user-messages s) " msgs)"))
                    (format-session-source s))))))

(defn- truncate [s max-len]
  (if (> (count s) max-len)
    (str (subs s 0 max-len) "...")
    s))

(defn- print-conversation [messages]
  (doseq [{:keys [role text]} messages]
    (case role
      "user" (do (println (str "\n" (render/fg :bold "you") ": " (truncate text 200))))
      "assistant" (do (println (str "\n" (render/fg :accent "xi") ": " (truncate text 500))))
      nil)))

(defn- resume-session! [sess summary]
  (let [loaded (session/load-session summary)
        messages (session/read-session-messages summary)]
    (reset! sess loaded)
    (println (str "\n" (render/fg :dim "Resumed: ")
                 (or (:name loaded) (:cli-session-id loaded) (:id loaded))
                 (format-session-source summary)
                 (render/fg :dim (str " (" (count messages) " messages)"))
                 (when (= :pi (:source loaded))
                   (str "\n" (render/fg :dim "  (read-only — Pi sessions can't be continued)")))))
    ;; Show last few messages for context
    (let [recent (take-last 6 messages)]
      (when (> (count messages) (count recent))
        (println (render/fg :dim (str "\n  ... (" (- (count messages) (count recent)) " earlier messages)"))))
      (print-conversation recent))))

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

;; ── REPL ──────────────────────────────────────────────────────────────────────
;; Declared as vars so they can forward-reference each other without letfn issues.

(declare repl-loop handle-input handle-resume)

(def ^:private repl-state (atom nil))

(defn- init-repl-state! [rl sess cwd model]
  (reset! repl-state {:rl rl :sess sess :cwd cwd :model model}))

(defn- handle-resume* [sessions arg]
  (let [{:keys [rl sess]} @repl-state]
    (cond
      (empty? sessions)
      (do (println "\n  (no previous sessions)")
          (repl-loop))

      (= "" arg)
      (do (println "\nRecent sessions (pick a number):")
          (print-session-list sessions)
          (-> (read-line-prompt rl)
              (.then
               (fn [choice]
                 (let [choice (str/trim choice)]
                   (cond
                     (str/starts-with? choice "/")
                     (handle-input choice)

                     (= "" choice)
                     (repl-loop)

                     :else
                     (let [n (js/parseInt choice 10)
                           idx (dec n)]
                       (if (and (not (js/isNaN n)) (<= 0 idx) (< idx (count sessions)))
                         (do (resume-session! sess (nth sessions idx))
                             (repl-loop))
                         (do (println (str "\n" (render/fg :error "Invalid choice.")))
                             (repl-loop))))))))))

      :else
      (let [n (js/parseInt arg 10)]
        (if (and (not (js/isNaN n)) (<= 1 n) (<= n (count sessions)))
          (do (resume-session! sess (nth sessions (dec n)))
              (repl-loop))
          (do (println (str "\n" (render/fg :error "Session not found.")))
              (repl-loop)))))))

(defn- handle-input [input]
  (let [{:keys [sess cwd model]} @repl-state]
    (cond
      (= "/quit" input)
      (do (println "Bye.") (js/process.exit 0))

      (or (= "/sessions" input) (= "/ls" input))
      (do (println "\nRecent sessions:")
          (print-session-list (session/list-sessions cwd))
          (repl-loop))

      (= "/resume" input)
      (handle-resume* (session/list-sessions cwd) "")

      (str/starts-with? input "/resume ")
      (handle-resume* (session/list-sessions cwd)
                       (str/trim (subs input 8)))

      (= "/help" input)
      (do (println "\nCommands:")
          (println "  /sessions    — list recent sessions")
          (println "  /resume [n]  — resume a previous session")
          (println "  /model       — show current model")
          (println "  /clear       — start new session")
          (println "  /help        — show this help")
          (println "  /quit        — exit")
          (doseq [{:keys [name desc]} (ext/list-commands)]
            (println (str "  /" name (when desc (str "  — " desc)))))
          (repl-loop))

      (str/starts-with? input "/model")
      (let [parts (str/split input #"\s+" 2)]
        (if (= 1 (count parts))
          (println (str "\nCurrent model: " (render/fg :accent model)))
          (println (str "\nModel change not yet supported at runtime. "
                        "Set XI_MODEL=" (second parts) " env var.")))
        (repl-loop))

      (= "/clear" input)
      (do (reset! sess (session/create-session cwd))
          (println (str "\n" (render/fg :dim "New session started.")))
          (repl-loop))

      ;; Extension commands
      (and (str/starts-with? input "/")
           (ext/get-command (subs (first (str/split input #"\s")) 1)))
      (let [cmd-name (subs (first (str/split input #"\s")) 1)
            {:keys [handler]} (ext/get-command cmd-name)
            ctx {:session @sess :model model :cwd cwd}]
        (handler ctx)
        (repl-loop))

      (= "" input)
      (repl-loop)

      :else
      (-> (run-agent-turn @sess input model)
          (.then (fn [result]
                   (when-let [sid (:session-id result)]
                     (swap! sess assoc :cli-session-id sid)
                     (when-not (:name @sess)
                       (swap! sess assoc :name (subs input 0 (min 60 (count input)))))
                     (session/save-session! @sess))
                   (println)
                   (repl-loop)))
          (.catch (fn [err]
                    (render/stop-spinner)
                    (println (str "\n" (render/fg :error "[Error]") " " (.-message err)))
                    (repl-loop)))))))

(defn- repl-loop []
  (let [{:keys [rl]} @repl-state]
    (-> (read-line-prompt rl)
        (.then (fn [input] (handle-input (.trim input))))
        (.catch (fn [err]
                  (println (str "\n[Fatal: " (.-message err) "]"))
                  (js/process.exit 1))))))

(defn main []
  (register-extensions!)
  (let [settings (load-settings)
        model (or (aget js/process.env "XI_MODEL")
                  (:defaultModel settings)
                  DEFAULT_MODEL)
        cwd (.cwd js/process)
        sess (atom (session/create-session cwd))
        rl (.createInterface readline
                             #js {:input js/process.stdin
                                  :output js/process.stdout})]

    (init-repl-state! rl sess cwd model)

    (println (render/fg :bold "Xi") (render/fg :dim "— coding agent"))
    (println (str (render/fg :dim "Model: ") (render/fg :accent model)))
    (println (render/fg :dim "Type /quit to exit, /help for commands.\n"))

    (.on rl "close" (fn [] (println "\nBye.") (js/process.exit 0)))

    (repl-loop)))
