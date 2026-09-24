(ns xi.ext.permission-gate
  "Permission gate — confirm before dangerous writes and bash commands.

   The old :tool-call hook becomes a tool-gate: it confirms via the gate
   ctx's :confirm! (a dialog), allowing the tool-call on yes and blocking
   it (nil → 'Blocked by Xi permission gate') on no. With no client
   attached, :confirm! resolves to its safe default (false), so guarded
   operations are blocked in headless server mode."
  (:require [clojure.string :as str]
            [xi.core.state :as state]
            [xi.sandbox.core :as sandbox]
            ["fs" :as fs]
            ["os" :as os]
            ["path" :as node-path]))

(def ^:private BLOCKED_PATHS
  "Paths that should never be written to without confirmation."
  ["/Mail/" "/.ssh/" "/.gnupg/" "/.password-store/"])

(def ^:private BLOCKED_COMMANDS
  "Bash patterns that are always blocked."
  ["ssh " "scp " "rsync " "sftp "])

(def GUARDED_PATTERNS
  "Bash patterns that require extra caution. Public: the clj extension
   applies the same patterns to (sh …) argv strings. The Xi server control tasks
   (serve:restart / serve:stop) are handled separately (see
   server-control-kind) because running them inline would kill the very
   server hosting this agent mid-command; they still require approval but
   run detached and return an explicit result."
  ["rm -rf" "rm -r" "sudo " "chmod -R" "chown -R"
   "fs/delete-dir" "fs/delete-tree"
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

(defn outside-project?
  "True when path resolves outside both the project repo (working dir) and the
   OS tmp dir — i.e. a write/edit that escapes the repo. Tmp is always allowed.
   Symlinks are canonicalized (sandbox/real-resolve) so the check can't be
   laundered through a link created inside cwd."
  [cwd path]
  (boolean
   (when (and cwd path (not (str/blank? (str path))))
     (let [resolved (sandbox/real-resolve cwd (str path))
           real-cwd (sandbox/real-resolve cwd ".")
           tmp      (sandbox/real-resolve cwd (os/tmpdir))]
       (not (or (sandbox/path-within? resolved real-cwd)
                (sandbox/path-within? resolved tmp)))))))

(def ^:private ext-id :permission-gate)

(defn- git-repo-root
  "Walk up from path's parent directory looking for a .git entry (dir or
   worktree file); return the repo root, or nil when not inside a git repo.
   Works for not-yet-existing paths — missing intermediate dirs just fail
   the .git check and the walk continues upward."
  [path]
  (loop [dir (node-path/dirname (str path))]
    (cond
      (fs/existsSync (node-path/join dir ".git")) dir
      (= dir (node-path/dirname dir)) nil
      :else (recur (node-path/dirname dir)))))

(defn- allow-repo
  "Remember a repo root whose writes the user allowed for this room."
  [st {:keys [room-id repo]}]
  (when (and room-id (seq (str repo)))
    {:state (update-in st [:rooms room-id :ext ext-id :allowed-write-repos]
                       (fnil conj #{}) (str repo))}))

(defn approve-write-path
  "Approve a single write to `path` outside the project repo. Returns a promise
   resolving to the approved root (the repo root when the user picks [r], else
   the resolved path) or nil when denied. Auto-approves (no prompt) when the
   path already sits under a stored allowed-write-repo, or when no confirmer is
   attached (headless). On the [r] allow-repo answer records the repo root in
   room ext state so later writes under it skip the dialog. Reused by the clj
   tool's builtin write helpers so they prompt instead of hard-rejecting."
  [{:keys [confirm! dispatch! get-state room-id cwd]} path]
  (let [resolved (sandbox/real-resolve cwd (str path))
        repo     (git-repo-root resolved)
        allowed  (when get-state
                   (:allowed-write-repos (state/room-ext (get-state) room-id ext-id)))]
    (cond
      (some #(sandbox/path-within? resolved %) allowed)
      (js/Promise.resolve (or repo resolved))

      (not confirm!) (js/Promise.resolve (or repo resolved))

      :else
      (-> (confirm! (str "Write outside the project repo: " path
                         (when repo (str " (repo: " repo ")")))
                    (when repo {:options [:yes :no :allow-repo]}))
          (.then (fn [answer]
                   (cond
                     (= answer :repo)
                     (do (when dispatch!
                           (dispatch! {:type :ext.permission-gate/allow-repo
                                       :room-id room-id :repo repo}))
                         repo)
                     answer resolved
                     :else  nil)))))))

(defn- ask-outside-write
  "Confirm a write/edit outside the project repo. When the target sits inside
   another git repo the dialog offers a third option — [r] allow all writes to
   that repo — which records the repo root in room ext state so later writes
   under it skip the dialog."
  [tool-call ctx path]
  (-> (approve-write-path ctx path)
      (.then (fn [root] (when root tool-call)))))

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
  [tool-call {:keys [confirm! cwd] :as ctx}]
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

          (outside-project? cwd path)
          (ask-outside-write tool-call ctx path)

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
  {:id        ext-id
   :init      {:room {:allowed-write-repos #{}}}
   :handlers  {:ext.permission-gate/allow-repo allow-repo}
   :tool-gate tool-gate})
