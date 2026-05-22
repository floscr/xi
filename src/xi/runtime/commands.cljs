(ns xi.runtime.commands
  "Command parsing and dispatch — extracted from cli.cljs.
   Commands return data (events to emit), never touch UI directly."
  (:require [clojure.string :as str]
            [xi.command-registry :as registry]
            [xi.ext.core :as ext]
            [xi.provider :as provider]
            [xi.session :as session]
            [xi.system-prompt :as system-prompt]
            [xi.util :as util]))

(def ^:private truncate util/truncate)
(def ^:private claude-model? util/claude-model?)
(def ^:private extract-text-content util/extract-text-content)

(defn- flush-text
  "If there's accumulated text, append it as an assistant block and clear."
  [{:keys [parts current-text] :as acc}]
  (if (seq current-text)
    (assoc acc
           :parts (conj parts (str "### Assistant\n" current-text))
           :current-text "")
    acc))

(defn- scrollback-step
  "Reduce step: process one event, return updated accumulator."
  [acc event]
  (case (:type event)
    :user-message
    (let [{:keys [parts]} (flush-text acc)]
      (assoc acc
             :parts (conj parts (str "### User\n" (:text event)))
             :current-text ""))

    :text-delta
    (update acc :current-text str (:text event))

    :tool-args
    (let [{:keys [parts]} (flush-text acc)
          json-str (try (js/JSON.stringify (clj->js (:arguments event)) nil 2)
                        (catch :default _ "{}"))]
      (assoc acc
             :parts (conj parts
                          (str "### Tool: " (:name event) "\n"
                               "```json\n"
                               (truncate json-str 2000)
                               "\n```"))
             :current-text ""))

    :tool-result
    (let [text (extract-text-content (:content event))]
      (update acc :parts conj
              (str "### Tool Result"
                   (when (:is-error event) " (ERROR)")
                   "\n"
                   (truncate text 1000))))

    :turn-end
    (let [{:keys [parts]} (flush-text acc)]
      (assoc acc
             :parts (conj parts
                          (str "---\n_Turn end"
                               (when (:cost event) (str " | Cost: $" (:cost event)))
                               (when (:usage event) (str " | Tokens: " (pr-str (:usage event))))
                               "_"))
             :current-text ""))

    :error
    (update acc :parts conj (str "### Error\n" (pr-str (:error event))))

    :aborted
    (update acc :parts conj "_Aborted_")

    ;; Unknown event type — skip
    acc))

(defn format-scrollback
  "Format event history into a markdown scrollback string.
   Pure function — no atoms, no side effects."
  [events]
  (let [{:keys [parts current-text]}
        (reduce scrollback-step {:parts [] :current-text ""} events)
        final-parts (if (seq current-text)
                      (conj parts (str "### Assistant\n" current-text))
                      parts)]
    (str/join "\n\n" final-parts)))

(defn- format-session-list
  "Format session list as plain data."
  [sessions]
  (mapv (fn [i s]
          {:index (inc i)
           :name (or (:name s) "(unnamed)")
           :timestamp (:timestamp s)
           :user-messages (:user-messages s)
           :source (:source s)
           :cwd (:cwd s)})
        (range) sessions))

;; ── Built-in Command Handlers ─────────────────────────────────────────────────
;; Each handler receives ctx: {:sess :cwd :model :effort :busy :event-history :args}

(defn- cmd-quit [_ctx]
  [{:type :quit}])

(defn- cmd-help [_ctx]
  (let [all-cmds (registry/list-commands)
        builtin (remove #(= "ext" (:source %)) all-cmds)
        ext-cmds (filter #(= "ext" (:source %)) all-cmds)]
    [{:type :command-result
      :command "help"
      :builtin-commands (mapv (fn [c]
                                (str (:name c)
                                     (when (:description c)
                                       (str "  — " (:description c)))))
                              builtin)
      :extension-commands (mapv (fn [c]
                                  {:name (:name c) :desc (:description c)})
                                ext-cmds)}]))

(defn- cmd-debug [{:keys [sess model effort cwd busy event-history]}]
  (let [is-claude (claude-model? model)
        sdk-session-id (provider/get-session-id)
        scrollback (format-scrollback (or event-history []))
        text (str "# Xi Debug Info\n\n"
                  "## Runtime\n"
                  "- Model: " (or model "(none)") "\n"
                  "- Effort: " (or effort "(default)") "\n"
                  "- CWD: " cwd "\n"
                  "- Busy: " (boolean busy) "\n"
                  "- Extensions: " (str/join ", " (ext/list-extensions)) "\n"
                  "\n## Provider\n"
                  "- Type: " (if is-claude "Claude SDK Bridge" "OpenAI-compatible") "\n"
                  (when is-claude
                    (str "- SDK Session: " (or sdk-session-id "(none)") "\n"))
                  "\n## Session\n"
                  "- Xi ID: " (or (:id @sess) "(none)") "\n"
                  "- CLI Session ID: " (or (:cli-session-id @sess) "(none)") "\n"
                  "- Name: " (or (:name @sess) "(unnamed)") "\n"
                  "\n## Scrollback\n\n"
                  (if (seq scrollback) scrollback "(empty)"))]
    [{:type :command-result :command "debug" :text text}]))

(defn- cmd-prompt [{:keys [cwd]}]
  (let [agents-md (system-prompt/load-agents-md cwd)]
    [{:type :command-result
      :command "prompt"
      :text (or agents-md "(no AGENTS.md found)")}]))

(defn- cmd-model [{:keys [args model]}]
  (if (nil? args)
    [{:type :command-result :command "model" :model model}]
    [{:type :model-changed :model args}]))

(defn- cmd-compact [{:keys [args]}]
  [{:type :compact-requested :focus args}])

(defn- cmd-clear [{:keys [sess model cwd]}]
  (let [pa? (:personal-agent? @sess)
        agents-files (system-prompt/find-agents-md cwd)]
    (reset! sess (session/create-session (:cwd @sess)
                   (when pa? {:personal-agent? true})))
    (provider/clear-session!)
    [{:type :session-cleared :model model :cwd cwd :agents-files agents-files}]))

(defn- cmd-new [{:keys [sess cwd model personal-agent?]}]
  (let [agents-files (system-prompt/find-agents-md cwd)]
    (when (:cli-session-id @sess)
      (session/save-session! @sess))
    (reset! sess (session/create-session cwd
                   (when personal-agent? {:personal-agent? true})))
    (provider/clear-session!)
    [{:type :session-cleared :model model :cwd cwd :agents-files agents-files}]))

(defn- cmd-resume [{:keys [args sess cwd personal-agent?]}]
  (if (nil? args)
    ;; Show session list for resume
    (let [cwd-sessions (if personal-agent?
                         (session/list-personal-agent-sessions)
                         (session/list-sessions cwd))
          all-sessions (if personal-agent?
                         cwd-sessions
                         (session/list-all-sessions))]
      [{:type :command-result
        :command "resume-list"
        :sessions (format-session-list cwd-sessions)
        :all-sessions (format-session-list all-sessions)
        :raw-sessions cwd-sessions
        :raw-all-sessions all-sessions}])
    ;; Resume specific session — supports "N" (cwd) or "all:N" (all sessions)
    (let [[scope idx-str] (if (str/starts-with? args "all:")
                            [:all (subs args 4)]
                            [:cwd args])
          n (js/parseInt idx-str 10)
          sessions (if personal-agent?
                     (session/list-personal-agent-sessions)
                     (if (= :all scope)
                       (session/list-all-sessions)
                       (session/list-sessions cwd)))]
      (if (and (not (js/isNaN n)) (<= 1 n) (<= n (count sessions)))
        (let [summary (nth sessions (dec n))
              loaded (session/load-session summary)
              loaded (if (and (= :xi (:source loaded)) (:cwd loaded))
                       (session/touch-session! loaded)
                       loaded)
              messages (session/read-session-messages summary)]
          (reset! sess loaded)
          [{:type :session-resumed
            :session loaded
            :summary summary
            :messages messages}])
        [{:type :command-error :command "resume" :text "Session not found."}]))))

;; ── Registration ──────────────────────────────────────────────────────────────

(defn register-builtin-commands!
  "Register all built-in runtime commands into the central registry."
  []
  (registry/register-many!
   [{:name "quit"
     :description "Exit Xi"
     :handler cmd-quit
     :scope :runtime}

    {:name "help"
     :description "Show available commands"
     :handler cmd-help
     :scope :runtime}

    {:name "debug"
     :description "Copy debug info to clipboard"
     :handler cmd-debug
     :scope :runtime}

    {:name "prompt"
     :description "Show system prompt"
     :handler cmd-prompt
     :scope :runtime}

    {:name "model"
     :description "Show or set model"
     :handler cmd-model
     :scope :runtime}

    {:name "compact"
     :description "Summarize conversation to reduce context"
     :handler cmd-compact
     :scope :runtime}

    {:name "clear"
     :description "Clear current session"
     :handler cmd-clear
     :scope :runtime}

    {:name "new"
     :description "Start a new session"
     :handler cmd-new
     :scope :runtime}

    {:name "resume"
     :description "Resume a previous session"
     :handler cmd-resume
     :scope :runtime}]))

;; ── Parsing ───────────────────────────────────────────────────────────────────

(defn parse-input
  "Parse user input into a command map.
   Returns {:type :prompt :text ...} or {:type :command :name ... :args ...}.
   Input can be a string or a map with :text and optional :images."
  [input]
  (let [text (str/trim (if (map? input) (:text input "") input))
        images (when (map? input) (:images input))]
    (cond
      (empty? text)
      nil

      (not (str/starts-with? text "/"))
      (cond-> {:type :prompt :text text}
        (seq images) (assoc :images images))

      :else
      (let [parts (str/split text #"\s+" 2)
            cmd-name (subs (first parts) 1)
            args (when (second parts) (str/trim (second parts)))]
        {:type :command :name cmd-name :args args}))))

(defn handle-command
  "Execute a slash command. Returns a vector of events to emit.
   Looks up command in the central registry, falls back to extension commands."
  [{:keys [name args]} {:keys [sess cwd model effort busy event-history personal-agent?]}]
  (let [ctx {:name name
             :args args
             :sess sess
             :cwd cwd
             :model model
             :effort effort
             :busy busy
             :event-history event-history
             :personal-agent? personal-agent?}]
    (if-let [cmd (registry/get-command name :runtime)]
      ;; Dispatch from central registry (runtime-scoped only)
      ;; Use resolve-handler for subcommand routing
      (let [[handler remaining-args] (registry/resolve-handler cmd args)]
        (handler (assoc ctx :args remaining-args)))
      ;; Fallback: try extension commands (legacy path — extensions that
      ;; haven't migrated to the registry yet)
      (if-let [{:keys [handler]} (ext/get-command name)]
        (let [result (handler {:session @sess :model model :cwd cwd :args args})]
          (if (and (map? result) (= :prompt (:type result)))
            [{:type :dispatch-prompt :text (:text result)}]
            [{:type :command-result :command name :text (str "Ran /" name)}]))
        [{:type :command-error :command name :text (str "Unknown command: /" name)}]))))
