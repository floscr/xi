(ns xi.server-control
  "Commands that stop or restart the xi server hosting this agent
   (`bb serve:restart` / `bb serve:stop`).

   Run inline they would kill the server mid-command: the agent's own turn dies
   with a severed connection instead of a result. So every executor that can
   run them checks first — the bash tool with `kind`, the argv executors
   (run_task, clj's (sh …), xi.api.sh) with `argv-kind` — and hands the command
   to `run-detached!` / `run-argv-detached!`, which return an explicit result
   immediately and run the command a moment later under setsid, so it
   survives the server it kills.

   Whether they may run at all is policy: the `server-control` default rule
   (xi.rules.defaults) asks first."
  (:require [clojure.string :as str]))

(defn kind
  "When bash command line `cmd` runs a server-control task, :restart or :stop;
   else nil. A substring test, since a shell line can't be taken apart — argv
   executors use `argv-kind`. Matches the :1414 xi-serve tasks only —
   serve:personal:* (:1415) don't contain these substrings, so they run
   normally."
  [cmd]
  (let [cmd (str cmd)]
    (cond
      (str/includes? cmd "serve:restart") :restart
      (str/includes? cmd "serve:stop")    :stop
      :else nil)))

(defn argv-kind
  "`kind` for an argv (clj's sh, xi.api.sh, run_task): only when the program is
   bb and its task — the first non-flag argument — is serve:restart or
   serve:stop, so an argument merely mentioning them runs normally."
  [argv]
  (when (re-find #"(?:^|/)bb$" (str (first argv)))
    (case (first (remove #(str/starts-with? (str %) "-") (rest argv)))
      "serve:restart" :restart
      "serve:stop"    :stop
      nil)))

(defn result-text
  "What the agent is told after a detached server-control run."
  [kind]
  (case kind
    :restart
    (str "Server restart initiated in the background (detached via setsid). "
         "Your WebSocket connection to :1414 will drop momentarily — this is "
         "EXPECTED and means the restart is working, NOT a failure or a "
         "permission error. A fresh server will be listening on :1414 within "
         "~15s (it waits for the watch's fresh build) and this turn will "
         "auto-resume. Do not retry the command; "
         "confirm it's back with `bb check` or by reloading the page.")
    :stop
    (str "Server stop initiated in the background (detached). Your connection "
         "to :1414 will drop — this is EXPECTED, NOT a failure. The server "
         "will stay down until it's started again with `bb serve`.")))

(defn- spawn-detached!
  "setsid the `bash -c` invocation `args`, after a short delay so the caller's
   result gets out first."
  [args cwd]
  (js/Bun.spawn
   (clj->js (into ["setsid" "bash" "-c"] args))
   #js {:cwd (or cwd (.cwd js/process))
        :stdin "ignore" :stdout "ignore" :stderr "ignore"}))

(defn run-detached!
  "Run bash command line `cmd` detached in `cwd` → the result text for its kind."
  [cmd cwd]
  (spawn-detached! [(str "sleep 0.3; " cmd)] cwd)
  (result-text (kind cmd)))

(defn run-argv-detached!
  "Run `argv` detached in `cwd` → the result text for its kind. The argv goes
   to the shell as positional parameters, never re-parsed."
  [argv cwd]
  (spawn-detached! (into ["sleep 0.3; exec \"$@\"" "bash"] argv) cwd)
  (result-text (argv-kind argv)))

(defn- as-tool-result [text]
  {:content [{:type "text" :text text}]
   :is-error false})

(defn tool-result
  "`run-detached!` wrapped as a tool result map."
  [cmd cwd]
  (as-tool-result (run-detached! cmd cwd)))

(defn argv-tool-result
  "`run-argv-detached!` wrapped as a tool result map."
  [argv cwd]
  (as-tool-result (run-argv-detached! argv cwd)))
