(ns xi.ext.permission-gate
  "Permission gate — confirm before dangerous writes and bash commands.

   The old :tool-call hook becomes a tool-gate: it confirms via the gate
   ctx's :confirm! (a dialog), allowing the tool-call on yes and blocking
   it (nil → 'Blocked by Xi permission gate') on no. With no client
   attached, :confirm! resolves to its safe default (false), so guarded
   operations are blocked in headless server mode."
  (:require [clojure.string :as str]))

(def ^:private BLOCKED_PATHS
  "Paths that should never be written to without confirmation."
  ["/Mail/" "/.ssh/" "/.gnupg/" "/.password-store/"])

(def ^:private BLOCKED_COMMANDS
  "Bash patterns that are always blocked."
  ["ssh " "scp " "rsync " "sftp "])

(def ^:private GUARDED_PATTERNS
  "Bash patterns that require extra caution. The Xi server control tasks
   (serve:restart / serve:stop) are handled separately (see
   server-control-kind) because running them inline would kill the very
   server hosting this agent mid-command; they still require approval but
   run detached and return an explicit result."
  ["rm -rf" "rm -r" "sudo " "chmod -R" "chown -R"
   "> /dev/" "mkfs" "dd if=" ":(){ " "fork bomb"
   "git push"
   "kill " "kill -" "pkill" "killall"])

(def ^:private BLOCKED_WRITE_PATHS
  "File patterns that should not be written to without confirmation."
  [".env" ".git/" "node_modules/"])

(defn- blocked-path?
  "True when path matches any of the patterns."
  [path patterns]
  (some #(str/includes? (str path) %) patterns))

(defn server-control-kind
  "When cmd runs the host-server bb control task that would kill the very
   server hosting this agent, return :restart or :stop; else nil. Matches the
   :7474 xi-serve tasks only — serve:personal:* (:7475) don't contain these
   substrings, so they're left to the normal gate."
  [cmd]
  (cond
    (str/includes? cmd "serve:restart") :restart
    (str/includes? cmd "serve:stop")    :stop
    :else nil))

(defn- server-control-result-text
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

(defn- run-server-control!
  "Spawn the server-control command detached (setsid) so it survives killing
   the pane/server that hosts this agent, and return an explicit tool result
   immediately — before the server dies — so the agent gets a clear success
   signal instead of a severed 'permission stream closed' error. Mirrors the
   setsid detach pattern in xi.ext.dev-server."
  [cmd kind]
  (js/Bun.spawn
   #js ["setsid" "bash" "-c" (str "sleep 0.3; " cmd)]
   #js {:stdin "ignore" :stdout "ignore" :stderr "ignore"})
  {:intercepted true
   :result {:content [{:type "text" :text (server-control-result-text kind)}]
            :is-error false}})

(defn- ask-server-control
  "Confirm a server-control command; on yes run it detached and return an
   explicit result, on no block (nil). With no :confirm! available (headless,
   no client), run detached — inline would self-kill the server."
  [confirm! cmd kind]
  (if confirm!
    (-> (confirm! (str "Guarded command: " cmd))
        (.then (fn [allowed?]
                 (if allowed? (run-server-control! cmd kind) nil))))
    (run-server-control! cmd kind)))

(defn- ask-confirmation
  "Confirm a guarded operation; allow on yes, block (nil) on no. With no
   :confirm! available the call passes through unchanged."
  [tool-call confirm! message]
  (if confirm!
    (-> (confirm! message)
        (.then (fn [allowed?] (if allowed? tool-call nil))))
    tool-call))

(defn- tool-gate
  "Guard dangerous operations with user confirmation. Tool names may be
   PascalCase (from the SDK) or lowercase."
  [tool-call {:keys [confirm!]}]
  (let [{:keys [name arguments]} tool-call
        lname (str/lower-case (or name ""))]
    (case lname
      ("write" "edit")
      (let [path (or (:path arguments) (:file_path arguments))]
        (cond
          (blocked-path? path BLOCKED_PATHS)
          (ask-confirmation tool-call confirm! (str "Write to sensitive path: " path))

          (blocked-path? path BLOCKED_WRITE_PATHS)
          (ask-confirmation tool-call confirm! (str "Write to protected path: " path))

          :else tool-call))

      "bash"
      (let [cmd (or (:command arguments) "")]
        (cond
          (some #(str/includes? cmd %) BLOCKED_COMMANDS)
          {:intercepted true
           :result {:content [{:type "text" :text "Blocked: remote shell commands (ssh, scp, rsync, sftp) are not allowed."}]
                    :is-error true}}

          (server-control-kind cmd)
          (ask-server-control confirm! cmd (server-control-kind cmd))

          (some #(str/includes? cmd %) GUARDED_PATTERNS)
          (ask-confirmation tool-call confirm! (str "Guarded command: " cmd))

          :else tool-call))

      ;; Everything else: allowed
      tool-call)))

(def extension
  {:id        :permission-gate
   :tool-gate tool-gate})
