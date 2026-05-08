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
            [xi.system-prompt :as system-prompt]
            [xi.tools.registry :as tools]
            [xi.tui.render :as render]
            ["node:readline" :as readline]))

(def ^:private DEFAULT_MODEL "claude-sonnet-4-20250514")

(defn- load-settings
  "Load model/provider from ~/.pi/agent/settings.json if available."
  []
  (try
    (let [path (str (aget js/process.env "HOME") "/.pi/agent/settings.json")
          content (.readFileSync (js/require "node:fs") path "utf8")]
      (js->clj (js/JSON.parse content) :keywordize-keys true))
    (catch :default _e {})))

(defn- load-system-prompt []
  (system-prompt/build (tools/tool-definitions)))

(defn- format-tool-result [{:keys [toolName content isError]}]
  (let [text (->> content
                  (filter #(= "text" (:type %)))
                  (map :text)
                  (str/join "\n"))
        status (if isError
                 (render/fg :error "ERR")
                 (render/fg :success "OK"))]
    (str "  [" status " " (render/fg :dim toolName) "] "
         (let [first-line (first (.split text "\n"))]
           (render/fg :dim
                      (if (> (count first-line) 100)
                        (str (subs first-line 0 100) "...")
                        first-line))))))

(defn- run-agent-turn
  "Run one agent turn via Claude CLI bridge. Returns promise of updated session."
  [sess prompt model]
  (let [text-started (atom false)]
    (render/start-spinner "thinking...")
    (-> (loop/run-turn
         {:model model
          :prompt prompt
          :on-text (fn [text]
                     (when-not @text-started
                       (render/stop-spinner)
                       (println)
                       (reset! text-started true))
                     (js/process.stdout.write text))
          :on-thinking (fn [_text]
                         ;; Thinking blocks are shown by the spinner
                         nil)
          :on-tool-start (fn [{:keys [name]}]
                           (render/stop-spinner)
                           (println)
                           (println (str "  " (render/fg :accent "→") " " (render/fg :bold name)))
                           (render/start-spinner (str name "...")))
          :on-tool-result (fn [{:keys [name is-error]}]
                            (render/stop-spinner)
                            (let [status (if is-error
                                          (render/fg :error "ERR")
                                          (render/fg :success "OK"))]
                              (println (str "  [" status " " (render/fg :dim (or name "tool")) "]"))))
          :on-error (fn [err]
                      (render/stop-spinner)
                      (println (str (render/fg :error "[Rate limit]") " "
                                    (render/fg :dim (pr-str err)))))})
        (.then (fn [result]
                 (render/stop-spinner)
                 ;; Persist assistant message to session
                 (reduce (fn [s msg]
                           (session/append-message s msg))
                         sess
                         (:messages result)))))))

(defn- read-line-prompt
  "Read a line from stdin. Returns promise of string or nil (EOF)."
  [rl]
  (js/Promise.
   (fn [resolve _reject]
     (.question ^js rl "\nxi> "
                (fn [answer]
                  (resolve answer))))))

(defn- print-session-list [sessions]
  (if (empty? sessions)
    (println "  (no previous sessions)")
    (doseq [[i s] (map-indexed vector (take 10 sessions))]
      (println (str "  " (inc i) ". "
                    (or (:name s) "(unnamed)")
                    " — " (:timestamp s)
                    " (" (:user-messages s) " messages)")))))

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

(defn main []
  (register-extensions!)
  (let [settings (load-settings)
        model (or (aget js/process.env "XI_MODEL")
                  (:defaultModel settings)
                  DEFAULT_MODEL)
        cwd (.cwd js/process)
        ;; Create a new session
        sess (atom (session/create-session cwd))
        messages (atom [])
        rl (.createInterface readline
                             #js {:input js/process.stdin
                                  :output js/process.stdout})]

    ;; Write initial model change
    (swap! sess session/append-model-change "anthropic" model)

    (println (render/fg :bold "Xi") (render/fg :dim "— coding agent"))
    (println (str (render/fg :dim "Model: ") (render/fg :accent model)))
    (println (str (render/fg :dim "Session: ") (render/fg :dim (:session-id @sess))))
    (println (render/fg :dim "Type /quit to exit, /help for commands.\n"))

    ;; Handle close
    (.on rl "close" (fn [] (println "\nBye.") (js/process.exit 0)))

    (letfn [(repl-loop []
              (-> (read-line-prompt rl)
                  (.then (fn [input]
                           (let [input (.trim input)]
                             (cond
                               (= "/quit" input)
                               (do (println "Bye.") (js/process.exit 0))

                               (= "/sessions" input)
                               (do (println "\nRecent sessions:")
                                   (print-session-list (session/list-sessions cwd))
                                   (repl-loop))

                               (= "/help" input)
                               (do (println "\nCommands:")
                                   (println "  /sessions    — list recent sessions")
                                   (println "  /tree        — visualize session tree")
                                   (println "  /rewind <id> — rewind to a node")
                                   (println "  /model       — show current model")
                                   (println "  /clear       — clear conversation")
                                   (println "  /help        — show this help")
                                   (println "  /quit        — exit")
                                   (doseq [{:keys [name desc]} (ext/list-commands)]
                                     (println (str "  /" name (when desc (str "  — " desc)))))
                                   (repl-loop))

                               (str/starts-with? input "/model")
                               (let [parts (str/split input #"\s+" 2)]
                                 (if (= 1 (count parts))
                                   (println (str "\nCurrent model: " (render/fg :accent model)))
                                   (let [new-model (second parts)]
                                     ;; Can't rebind model in letfn, so just print
                                     (println (str "\nModel change not yet supported at runtime. "  
                                                   "Set XI_MODEL=" new-model " env var."))))
                                 (repl-loop))

                               (= "/tree" input)
                               (do (println (str "\n" (session/format-tree @sess)))
                                   (repl-loop))

                               (str/starts-with? input "/rewind ")
                               (let [target-id (str/trim (subs input 8))]
                                 (try
                                   (swap! sess session/rewind-to target-id)
                                   (reset! messages (session/get-messages @sess))
                                   (println (str "\n" (render/fg :dim "Rewound to node: ") target-id))
                                   (catch :default e
                                     (println (str "\n" (render/fg :error (.-message e))))))
                                 (repl-loop))

                               (= "/clear" input)
                               (do (reset! messages [])
                                   (reset! sess (session/create-session cwd))
                                   (swap! sess session/append-model-change "anthropic" model)
                                   (println (str "\n" (render/fg :dim "Conversation cleared. New session: ")
                                                 (render/fg :dim (:session-id @sess))))
                                   (repl-loop))

                               ;; Extension commands
                               (and (str/starts-with? input "/")
                                    (ext/get-command (subs input 1)))
                               (let [cmd-name (subs input 1)
                                     {:keys [handler]} (ext/get-command cmd-name)
                                     ctx {:session @sess :messages @messages
                                          :model model :cwd cwd}]
                                 (handler ctx)
                                 (repl-loop))

                               (= "" input)
                               (repl-loop)

                               :else
                               (let [user-msg {:role "user"
                                               :content [{:type "text" :text input}]}]
                                 ;; Persist user message to session
                                 (swap! sess session/append-message user-msg)
                                 (swap! messages conj user-msg)
                                 (-> (run-agent-turn @sess input model)
                                     (.then (fn [updated-sess]
                                              ;; Update session and messages from persisted state
                                              (reset! sess updated-sess)
                                              (reset! messages (session/get-messages updated-sess))
                                              (println)
                                              (repl-loop)))
                                     (.catch (fn [err]
                                               (render/stop-spinner)
                                               (println (str "\n" (render/fg :error "[Error]") " " (.-message err)))
                                               (repl-loop)))))))))
                  (.catch (fn [err]
                            (println (str "\n[Fatal: " (.-message err) "]"))
                            (js/process.exit 1)))))]
      (repl-loop))))
