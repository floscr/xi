(ns xi.runtime.commands
  "Command parsing and dispatch — extracted from cli.cljs.
   Commands return data (events to emit), never touch UI directly."
  (:require [clojure.string :as str]
            [xi.ext.core :as ext]
            [xi.provider :as provider]
            [xi.session :as session]))

(defn- truncate [s max-len]
  (if (> (count s) max-len)
    (str (subs s 0 max-len) "...")
    s))

(defn- format-session-list
  "Format session list as plain data."
  [sessions]
  (mapv (fn [i s]
          {:index (inc i)
           :name (or (:name s) "(unnamed)")
           :timestamp (:timestamp s)
           :user-messages (:user-messages s)
           :source (:source s)})
        (range) sessions))

(defn parse-input
  "Parse user input into a command map.
   Returns {:type :prompt :text ...} or {:type :command :name ... :args ...}."
  [input]
  (let [input (str/trim input)]
    (cond
      (empty? input)
      nil

      (not (str/starts-with? input "/"))
      {:type :prompt :text input}

      :else
      (let [parts (str/split input #"\s+" 2)
            cmd-name (subs (first parts) 1)
            args (when (second parts) (str/trim (second parts)))]
        {:type :command :name cmd-name :args args}))))

(defn handle-command
  "Execute a slash command. Returns a vector of events to emit.
   Pure data — no UI side effects."
  [{:keys [name args]} {:keys [sess cwd model]}]
  (case name
    "quit"
    [{:type :quit}]

    ("sessions" "ls")
    (let [sessions (session/list-sessions cwd)]
      [{:type :command-result
        :command "sessions"
        :sessions (format-session-list sessions)
        :raw-sessions sessions}])

    "help"
    (let [ext-cmds (ext/list-commands)]
      [{:type :command-result
        :command "help"
        :builtin-commands ["sessions" "resume [n]" "model" "buffers" "palette" "new" "clear" "help" "quit"]
        :extension-commands ext-cmds}])

    "model"
    (if (nil? args)
      [{:type :command-result :command "model" :model model}]
      [{:type :model-changed :model args}])

    "clear"
    (do (reset! sess (session/create-session cwd))
        (provider/clear-session!)
        [{:type :session-cleared}])

    "new"
    (do (when (:cli-session-id @sess)
          (session/save-session! @sess))
        (reset! sess (session/create-session cwd))
        (provider/clear-session!)
        [{:type :session-cleared}])

    "resume"
    (if (nil? args)
      ;; Show session list for resume
      (let [sessions (session/list-sessions cwd)]
        [{:type :command-result
          :command "resume-list"
          :sessions (format-session-list sessions)
          :raw-sessions sessions}])
      ;; Resume specific session
      (let [n (js/parseInt args 10)
            sessions (session/list-sessions cwd)]
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
          [{:type :command-error :command "resume" :text "Session not found."}])))

    ;; Try extension commands
    (if-let [{:keys [handler]} (ext/get-command name)]
      (let [result (handler {:session @sess :model model :cwd cwd :args args})]
        (if (and (map? result) (= :prompt (:type result)))
          [{:type :dispatch-prompt :text (:text result)}]
          [{:type :command-result :command name :text (str "Ran /" name)}]))
      [{:type :command-error :command name :text (str "Unknown command: /" name)}])))
