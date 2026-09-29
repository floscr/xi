(ns xi.tools.bash
  "Bash command execution tool."
  (:require [xi.server-control :as server-control]
            [xi.tools.truncate :as trunc]))

(def ^:private DEFAULT_TIMEOUT 30000)
(def ^:private MAX_OUTPUT 50000)

(defn- truncate-output [s max-len]
  (trunc/truncate s max-len
                  {:keep   :tail
                   :prefix "bash"
                   :how    "inspect it with grep/tail or the read tool with offset/limit."}))

(defn- execute-inline
  [{:keys [command timeout]} {:keys [cwd]}]
  (let [timeout-ms (or timeout DEFAULT_TIMEOUT)
        ;; setsid creates a new session with no controlling terminal,
        ;; preventing child processes (e.g. ssh) from opening /dev/tty
        ;; and writing interactive prompts directly to the terminal,
        ;; which would corrupt the TUI.
        argv ["setsid" "bash" "-c" command]]
    (js/Promise.
     (fn [resolve _reject]
       (let [proc (js/Bun.spawn
                   (into-array argv)
                   #js {:stdin "ignore"
                        :stdout "pipe"
                        :stderr "pipe"
                        :env (unchecked-get js/process "env")
                        :cwd (or cwd (.cwd js/process))})
             timer (js/setTimeout
                    (fn []
                      (.kill proc)
                      (resolve {:content [{:type "text"
                                           :text (str "Command timed out after " timeout-ms "ms")}]
                                :is-error true}))
                    timeout-ms)]
         (-> (js/Promise.all
              #js [(.text (.-stdout proc))
                   (.text (.-stderr proc))])
             (.then (fn [results]
                      (js/clearTimeout timer)
                      (let [stdout (aget results 0)
                            stderr (aget results 1)
                            code (.-exitCode proc)
                            output (str (when (seq stdout) (truncate-output stdout MAX_OUTPUT))
                                        (when (and (seq stdout) (seq stderr)) "\n")
                                        (when (seq stderr) (str "STDERR:\n" (truncate-output stderr MAX_OUTPUT)))
                                        (when (and code (not= code 0))
                                          (str "\nExit code: " code)))]
                        (resolve {:content [{:type "text" :text (if (seq output) output "(no output)")}]
                                  :is-error (and code (not= code 0))}))))))))))

(defn execute
  "Execute a bash command. Returns promise of tool result. Server-control
   commands (xi.server-control) run detached instead of inline."
  [{:keys [command] :as args} {:keys [cwd] :as ctx}]
  (if (server-control/kind command)
    (js/Promise.resolve (server-control/tool-result command cwd))
    (execute-inline args ctx)))

(def definition
  {:name "bash"
   :description "Execute a bash command. Returns stdout, stderr, and exit code."
   :input_schema {:type "object"
                  :properties {:command {:type "string" :description "Bash command to execute"}
                               :timeout {:type "integer" :description "Timeout in ms (default 30000)"}}
                  :required ["command"]}})
