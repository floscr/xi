(ns xi.ext.permission-gate
  "Permission gate — confirm before dangerous writes and bash commands.

   The old :tool-call hook becomes a tool-gate: it confirms via the gate
   ctx's :confirm! (a dialog), allowing the tool-call on yes and blocking
   it (nil → 'Blocked by Xi permission gate') on no. With no client
   attached, :confirm! resolves to its safe default (false), so guarded
   operations are blocked in headless server mode."
  (:require [clojure.string :as str]))

(def GUARDED_PATTERNS
  "Bash patterns that require extra caution. Public: the clj extension
   applies the same patterns to (sh …) argv strings. (The tool-gate policy for
   these — plus the sensitive/protected/outside write gates and the blocked
   remote-shell commands — now lives in the rules engine, xi.rules.defaults.)
   The Xi server control tasks (serve:restart / serve:stop) are handled
   separately (see server-control-kind) because running them inline would kill
   the very server hosting this agent mid-command; they still require approval
   but run detached and return an explicit result."
  ["rm -rf" "rm -r" "sudo " "chmod -R" "chown -R"
   "fs/delete-dir" "fs/delete-tree"
   "> /dev/" "mkfs" "dd if=" ":(){ " "fork bomb"
   "git push"
   "kill " "kill -" "pkill" "killall"])

(def ^:private ext-id :permission-gate)

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

(defn ask-server-control
  "Confirm a server-control command; on yes run it detached and return an
   explicit result, on no block (nil). With no :confirm! available (headless,
   no client), run detached — inline would self-kill the server. Public:
   the clj extension delegates (sh \"bb\" \"serve:restart\") here so the
   detached-run semantics apply there too."
  [confirm! cmd kind]
  (if confirm!
    (-> (confirm! (str "Guarded command: " cmd))
        (.then (fn [allowed?]
                 (if allowed? (run-server-control! cmd kind) nil))))
    (run-server-control! cmd kind)))

(defn- tool-gate
  "Guard the server-control bash tasks (serve:restart / serve:stop) that would
   kill the very server hosting this agent: they run detached and return an
   explicit result. Every other policy gate — the sensitive/protected/outside
   write gates and the blocked/guarded bash commands — now lives in the rules
   engine (xi.rules.defaults), which runs first in the tool-gate chain.
   Tool names may be PascalCase (from the SDK) or lowercase."
  [tool-call {:keys [confirm!]}]
  (let [{:keys [name arguments]} tool-call]
    (if (= "bash" (str/lower-case (or name "")))
      (let [cmd (or (:command arguments) "")]
        (if-let [kind (server-control-kind cmd)]
          (ask-server-control confirm! cmd kind)
          tool-call))
      tool-call)))

(def extension
  {:id        ext-id
   :tool-gate tool-gate})
