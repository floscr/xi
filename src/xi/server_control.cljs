(ns xi.server-control
  "Commands that stop or restart the xi server hosting this agent
   (`bb serve:restart` / `bb serve:stop`).

   Run inline they would kill the server mid-command: the agent's own turn dies
   with a severed connection instead of a result. So every executor that can
   run them — the bash tool, the bb tool, clj's (sh …) — checks `kind` first
   and hands the command to `run-detached!`, which returns an explicit result
   immediately and runs the command a moment later under setsid, so it
   survives the server it kills.

   Whether they may run at all is policy: the `server-control` default rule
   (xi.rules.defaults) asks first."
  (:require [clojure.string :as str]))

(defn kind
  "When `cmd` runs a server-control task, :restart or :stop; else nil. Matches
   the :7474 xi-serve tasks only — serve:personal:* (:7475) don't contain these
   substrings, so they run normally."
  [cmd]
  (let [cmd (str cmd)]
    (cond
      (str/includes? cmd "serve:restart") :restart
      (str/includes? cmd "serve:stop")    :stop
      :else nil)))

(defn result-text
  "What the agent is told after a detached server-control run."
  [kind]
  (case kind
    :restart
    (str "Server restart initiated in the background (detached via setsid). "
         "Your WebSocket connection to :7474 will drop momentarily — this is "
         "EXPECTED and means the restart is working, NOT a failure or a "
         "permission error. A fresh server will be listening on :7474 within "
         "~5s and this turn will auto-resume. Do not retry the command; "
         "confirm it's back with `bb check` or by reloading the page.")
    :stop
    (str "Server stop initiated in the background (detached). Your connection "
         "to :7474 will drop — this is EXPECTED, NOT a failure. The server "
         "will stay down until it's started again with `bb serve`.")))

(defn run-detached!
  "Spawn `cmd` detached (setsid, after a short delay so the caller's result
   gets out first) in `cwd`, and return the result text for its kind."
  [cmd cwd]
  (js/Bun.spawn
   #js ["setsid" "bash" "-c" (str "sleep 0.3; " cmd)]
   #js {:cwd (or cwd (.cwd js/process))
        :stdin "ignore" :stdout "ignore" :stderr "ignore"})
  (result-text (kind cmd)))

(defn tool-result
  "`run-detached!` wrapped as a tool result map."
  [cmd cwd]
  {:content [{:type "text" :text (run-detached! cmd cwd)}]
   :is-error false})
