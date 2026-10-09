(ns xi.cli
  "Xi entry point — one core, three connection modes.

   Subcommands:
     xi               → connect to a running server in a fresh room (the empty
                        chat page); otherwise a standalone TUI (one local room,
                        no sockets). --no-auto-join forces standalone.
     xi server        → host rooms over WS; local TUI joins via WS
                        (--headless for server-only)
     xi prompt <text> → one-shot: run a single prompt headless, print the
                        response and exit (aka `xi -p`; reads stdin when no
                        text is given; --stream to stream tokens live)
     xi join [url]    → connect TUI to the latest room on a server
     xi create [url]  → connect TUI to a new room on a server

   Assembly per mode (same pure core everywhere):
     standalone — handlers (core+agent+commands+compaction with
                  turn-end/abort chains) + provider/session/TUI effects,
                  rendered by the TUI client.
     server     — same handlers + room-manager handlers and auto-destroy
                  chains; provider/session effects + WS effects; no
                  renderer. Clients are remote.
     client     — handlers wrapped by xi.client.ws-transport (forward
                  local events, mirror :remote? broadcasts); only
                  transport + TUI effects, rendered by the same TUI.

   Flags:
     --model M        override the default model
     --session SID    resume the saved session with this id on launch
                      (standalone, join, or create)
     --port N         WS port (server/join/create; default 7474)
     --host ADDR,...  server bind addresses (default 0.0.0.0; XI_HOST)
     --headless       server only, no local TUI
     --user ID        the user this process acts as (default root / XI_USER);
                      a joining client claims it, a server owns its prompts
                      under it — see the Users note in xi.server.ws
     --prompt <text>  launch the TUI with an initial prompt already submitted
                      (works standalone or with --join/--create; e.g. from
                      a shell wrapper that reports an error)
     --join           connect to a running server's latest room instead of
                      standalone (same as the `join` subcommand)
     --create         connect to a running server on a new room instead of
                      standalone (same as the `create` subcommand)
     --no-auto-join   force a local standalone room even when a server is
                      running (default: `xi` connects to a running server in a
                      fresh room)
     --stream         (prompt) stream response tokens to stdout as they arrive
     --debug-events   (standalone) write the full event stream to
                      ~/.pi/agent/logs/<session>.events.jsonl"
  (:require [clojure.string :as str]
            [xi.agent :as agent]
            [xi.agent-profile :as profile]
            [xi.auth :as auth]
            [xi.client.tui :as client-tui]
            [xi.crash-log :as crash-log]
            [xi.client.ws-transport :as ws-transport]
            [xi.commands :as commands]
            [xi.compaction :as compaction]
            [xi.core.app :as app]
            [xi.core.events :as events]
            [xi.core.jsonl :as core-jsonl]
            [xi.core.log :as log]
            [xi.core.state :as state]
            [xi.config :as config]
            [xi.env :as env]
            [xi.ext.core :as ext]
            [xi.ext.rules :as rules-ext]
            [xi.holds :as holds]
            [xi.ext.clj-worker :as clj-worker]
            [xi.ext.clj-socket :as clj-socket]
            [xi.ext.manager :as manager]
            [xi.ext.persist :as ext-persist]
            [xi.ext.mcp :as mcp]
            [xi.ext.user :as user-ext]
            [xi.fx :as fx]
            [xi.naming :as naming]
            [xi.providers.fake :as fake]
            [xi.quick-replies :as quick-replies]
            [xi.rules.store :as rules-store]
            [xi.summary :as summary]
            [xi.server.room-manager :as rm]
            [xi.server.ws :as ws]
            [xi.session :as session]
            [xi.session.recent :as recent]
            [xi.subagent :as subagent]
            [xi.system-prompt :as system-prompt]
            [xi.user-state.store :as user-store]
            [xi.users :as users]
            [xi.util :as util]
            ["node:fs" :as fs]
            ["node:path" :as node-path]
            ["node:worker_threads" :as wt]))

(def providers
  "Provider id → provider map from the xi.config/providers vector, in picker
   order. With XI_FAKE_LLM (e2e tests) the scripted fake is the only provider,
   registered as :anthropic so no turn reaches a real model."
  (if (fake/script-path)
    {:anthropic (fake/provider :anthropic)}
    (into {} (map (juxt :id identity)) config/providers)))

;; ── Extensions (per mode) ─────────────────────────────────────────────────────
;; Declared in xi.config, instantiated and composed here into the seams the
;; core, the provider effects and the TUI consume. Two groups: server
;; extensions (state + provider/tool hooks server-side; mirrored in client
;; mode) and client extensions (process-local to the TUI client).

(defn- server-extensions
  "Extensions whose state + provider hooks live server-side (xi.config/server).
   `ask!` is threaded into extensions that raise their own confirm dialogs (nil
   in the client mirror); `manager` (xi.ext.manager) into the control
   extensions (/ext, /mcp)."
  [ring & [ask! manager]]
  (ext/instantiate config/server {:ring ring :ask! ask! :manager manager
                                  :providers providers}))

(defn- mirror-extensions
  "Server extensions instantiated for the client mirror: a throwaway manager so
   /ext and /mcp are presented locally (their fx are forwarded to the server),
   `:mirror? true` so factories skip create-time side effects. User extensions
   follow, reduced to what a client mirrors (xi.ext.user/mirror-extensions)."
  []
  (let [builtins (ext/instantiate config/server
                                  {:ring nil :ask! nil :manager (manager/create) :mirror? true})]
    (into builtins (user-ext/mirror-extensions builtins))))

(defn- client-extensions
  []
  (ext/instantiate config/client {}))

(defn- tooling-opts
  "Provider-effect tooling for agent/create-fx. Reads the extension manager
   live, so runtime enable/disable applies on the next turn
   (xi.tools.registry/resolve-tooling)."
  [manager ask!]
  {;; Policy is core, not an extension surface: the rules engine decides
   ;; every tool call before it runs.
   :tool-policy            (fn [tool-call ctx]
                             (rules-ext/tool-policy
                              tool-call (assoc ctx :recommend-rule? config/recommend-rule?)))
   :extra-tool-definitions (fn [] (:tool-definitions (manager/composed manager)))
   :extra-tool-registry    (fn [] (:tool-registry (manager/composed manager)))
   :remove-tools           (fn [] (:remove-tools (manager/composed manager)))
   :ask!                   ask!
   ;; Wired here, not in xi.agent, which the browser build shares.
   :turn-finished!         holds/settle-room!
   :fx                     holds/fx})

(defn- subagent-opts
  "tooling-opts plus the throwaway CLAUDE_CONFIG_DIR fns, so a sub-agent's
   Claude CLI session doesn't land in ~/.claude/projects as a top-level chat."
  [manager ask!]
  (merge (tooling-opts manager ask!)
         {:make-config-dir!   session/make-throwaway-config-dir!
          :remove-config-dir! session/remove-config-dir!}))

(def ^:private DEFAULT_MODEL "claude-opus-4-8")

(def ^:private HELP_TEXT
  "xi — a personal coding harness (ClojureScript + Bun)

USAGE
  xi [flags]                 Standalone TUI. One local room; auto-joins a
                             running server on the port unless --no-auto-join.
  xi server [flags]          WS server + a local TUI client in one process.
  xi prompt [flags] <text>   One-shot headless run: send one prompt, print the
  xi -p     [flags] <text>   response, and exit. Reads stdin when <text> is
                             omitted. Safe to script/pipe (no TUI, no server).
  xi join   [flags] [url]    Connect a TUI client to the latest room on a server.
  xi create [flags] [url]    Connect a TUI client to a new room on a server.
  xi sessions [flags]        List saved chats (the web sidebar's Recent set),
                             then exit. Machine-facing; no TUI, no server.
  xi clients [action]        Manage approved web clients: list (default),
                             pending, approve <code>, revoke <key-prefix|name>,
                             user <key-prefix|name> <user-id|->.
                             Edits ~/.config/xi/clients.edn; a running server
                             picks approvals up within ~2s. For approving
                             pairing codes over ssh on a headless server.
                             `user` assigns a paired device to a user id (`-`
                             clears it); applies on its next connection.
  xi help                    Show this help (also --help, -h).

FLAGS
  --port N                   Override the default port (7474). All modes.
  --host ADDR[,ADDR]         server: bind addresses (default 0.0.0.0, all interfaces;
                             loopback is always bound too).
  --model NAME               Override the default model.
  --session ID               Resume a saved session by id (standalone/join/create).
  --prompt TEXT              Send an initial prompt on launch (standalone/client).
  --no-auto-join             Standalone: stay local, don't join a running server.
  --join, --create           Standalone: redirect onto a running server instead.
  --headless                 server: run without a local TUI (clients attach remotely).
  --agent ID                 Run as the named agent from ~/.config/xi/config.edn
                             [:agents ID] — its :tools allowlist, :extensions
                             and system prompt replace the coding tools +
                             AGENTS.md; sessions live in
                             ~/.config/xi/personal-agent/<ID>/. No profile =
                             no tools. Standalone (`xi --agent ID`) stays a
                             local room; also server and prompt.
  --user ID                  Act as this user (default root, or XI_USER). A
                             server stamps every client's events with its user;
                             a joining TUI claims this id (a device assignment
                             in clients.edn wins). No authentication — users are
                             told apart, not verified. All modes.
  --debug-events             Write the full event stream as JSONL (see docs).
  --no-hardened-rules        Drop the non-overridable hardened rules tier
                             (sudo/remote-copy denies). Unsafe; agents cannot
                             set this — it is a launch-time operator override.
  --stream                   prompt: stream response tokens to stdout as they arrive.
  --no-store                 prompt: run ephemerally — leave no session behind.
  --json                     sessions: emit a JSON array instead of TSV lines.
                             prompt: emit {\"session-id\", \"text\"} JSON instead
                             of raw text (for scripting with --agent/--session).
  --all                      sessions: list every saved chat, not just recent.
  --limit N                  sessions: cap the number of chats listed.

ENVIRONMENT
  XI_PORT                    Default port when --port is omitted. All modes.
  XI_HOST                    Server bind address when --host is omitted.
  XI_CWD                     Working directory the agent runs in.
  XI_USER                    Default for --user.
  ANTHROPIC_API_KEY          Auth (otherwise the Claude CLI's own login).

EXAMPLES
  xi                                         # standalone TUI
  xi server --headless                       # headless server on :7474
  xi prompt \"summarize the architecture\"     # one-shot, buffered
  git diff | xi -p --no-store \"review this\"   # pipe + ephemeral run
  xi --session <id>                          # resume a saved session

See docs/guide/command-line.md for the full reference.")

(defn- parse-args [args]
  (loop [args (seq args) opts {:command :standalone :auto-join? true}]
    (if-not args
      opts
      (let [arg (first args)]
        (case arg
          "server"         (recur (next args) (assoc opts :command :server))
          "join"           (recur (next args) (assoc opts :command :join))
          "create"         (recur (next args) (assoc opts :command :create))
          ("prompt" "-p")  (recur (next args) (assoc opts :command :prompt))
          "sessions"       (recur (next args) (assoc opts :command :sessions))
          "clients"        (recur (next args) (assoc opts :command :clients))
          ("help" "--help" "-h") (recur (next args) (assoc opts :command :help))
          "--json"         (recur (next args) (assoc opts :json? true))
          "--all"          (recur (next args) (assoc opts :all? true))
          "--limit"        (recur (nnext args) (assoc opts :limit (js/parseInt (second args) 10)))
          "--stream"       (recur (next args) (assoc opts :stream? true))
          "--no-store"     (recur (next args) (assoc opts :no-store? true))
          "--prompt"       (recur (nnext args) (assoc opts :initial-prompt (second args)))
          ;; Redirect the default (standalone) invocation onto a running server
          "--join"         (recur (next args) (assoc opts :command :join))
          "--create"       (recur (next args) (assoc opts :command :create))
          ;; Opt out of auto-joining a running server when launching standalone
          "--no-auto-join" (recur (next args) (assoc opts :auto-join? false))
          "--headless"     (recur (next args) (assoc opts :headless? true))
          "--agent"        (recur (nnext args) (assoc opts :agent (second args)))
          "--user"         (recur (nnext args) (assoc opts :user (second args)))
          "--debug-events" (recur (next args) (assoc opts :debug-events? true))
          "--no-hardened-rules" (recur (next args) (assoc opts :no-hardened-rules? true))
          "--model"        (recur (nnext args) (assoc opts :model (second args)))
          "--session"      (recur (nnext args) (assoc opts :session-id (second args)))
          "--port"         (recur (nnext args) (assoc opts :port (js/parseInt (second args) 10)))
          "--host"         (recur (nnext args) (assoc opts :host (second args)))
          (recur (next args)
                 (cond
                   (and (#{:join :create} (:command opts))
                        (not (.startsWith arg "--")))
                   (assoc opts :url (if (.startsWith arg "ws") arg (str "ws://" arg)))
                   (and (= :prompt (:command opts))
                        (not (.startsWith arg "--")))
                   (update opts :prompt-parts (fnil conj []) arg)
                   (and (= :clients (:command opts))
                        (not (.startsWith arg "--")))
                   (update opts :clients-args (fnil conj []) arg)
                   ;; An unknown flag fails loudly: a removed restriction flag must not
                   ;; silently yield a full coding agent.
                   (.startsWith arg "--")
                   (do (.write js/process.stderr (str "xi: unknown flag " arg "\n"))
                       (js/process.exit 2))
                   :else opts)))))))

(defn- valid-port [v]
  (let [n (js/parseInt v 10)]
    (when (and (js/Number.isInteger n) (< 0 n 65536)) n)))

(defn- own-user
  "The user this process acts as: --user, else XI_USER, else root. A joining
   client claims it in :auth/hello; the server may override it from
   clients.edn."
  [opts]
  (util/user-id (or (:user opts) (aget js/process.env "XI_USER"))))

(defn resolve-port
  "The port for every mode: --port, else XI_PORT, else the default."
  [opts]
  (assoc opts :port (or (valid-port (:port opts))
                        (valid-port (aget js/process.env "XI_PORT"))
                        ws/DEFAULT_PORT)))

(defn- resolve-model-opts
  "Model and effort this process starts with: --model, else its user's last
   pick (xi.user-state :preferred-model), else the default."
  [{:keys [model] :as opts}]
  {:model  (or model
               (:preferred-model (user-store/load-state (own-user opts)))
               DEFAULT_MODEL)
   :effort "high"})

(defn- install-agent-extensions!
  "Make an agent profile's :extensions the enabled user-extension list
   (xi.ext.user/enabled-files); a profile without them keeps the rules.edn
   list. Re-read on `/ext reload`."
  [prof]
  (when (:extensions prof)
    (user-ext/set-enabled-override!
     (fn [] (or (:extensions (profile/load (:id prof))) #{})))))

(defn- make-handlers
  "Base pure handler map shared by every mode; `extra-commands` are extension
   commands joining the built-ins."
  ([] (make-handlers nil))
  ([extra-commands]
   (-> (merge events/core-handlers
              agent/handlers
              (commands/command-handlers extra-commands)
              compaction/handlers
              naming/handlers
              summary/handlers
              quick-replies/handlers)
       (assoc :agent/turn-end (events/chain (:agent/turn-end agent/handlers)
                                            commands/turn-end-session-sync
                                            quick-replies/maybe-suggest)
              :agent/abort (events/chain (:agent/abort agent/handlers)
                                         compaction/abort-handler)
              :prompt/submit (events/chain (:prompt/submit agent/handlers)
                                           naming/maybe-generate-title
                                           quick-replies/clear-on-submit)))))

;; ── Standalone ───────────────────────────────────────────────────────────────

(defn- start-standalone! [{:keys [debug-events? initial-prompt session-id agent] :as opts}]
  (let [prof (when agent (profile/load agent))
        _    (install-agent-extensions! prof)
        {:keys [model effort]} (resolve-model-opts
                                (update opts :model #(or % (:model prof))))
        cwd (or (aget js/process.env "XI_CWD") (:dir prof) (.cwd js/process))
        ring (log/create-ring)
        dialogs  (ext/create-dialogs)
        mgr      (manager/create)
        _        (manager/seed! mgr (into (server-extensions ring (:ask! dialogs) mgr)
                                          (client-extensions)))
        _        (mcp/install! mgr)
        _        (user-ext/install! mgr)
        composed (manager/composed mgr)
        agents-files (when-not prof (system-prompt/find-agents-md cwd))
        system-parts (if prof
                       (profile/system-parts prof)
                       (into (system-prompt/load-agents-parts cwd)
                             (ext/system-prompt-parts composed cwd)))
        system (system-prompt/parts->system system-parts)
        sess (session/create-session cwd (cond-> {:user (own-user opts)}
                                           agent (assoc :agent agent)))
        jsonl-writer (when debug-events?
                       (core-jsonl/create-writer
                        (str (aget js/process.env "HOME")
                             "/.pi/agent/logs/" (:id sess) ".events.jsonl")))
        client (client-tui/create!
                {:ring ring
                 :on-exit (fn []
                            (ext/on-shutdown! composed)
                            (when jsonl-writer ((:flush! jsonl-writer))))
                 :commands (commands/all-commands (:commands composed))
                 :prompt-badge (fn [st] (ext/prompt-badges composed st))
                 :keybindings (:keybindings composed)})
        ;; Late-bound: a live extension reload rebuilds handlers and fx.
        handlers (manager/live-view
                  mgr
                  (fn [composed]
                    (-> (make-handlers (:commands composed))
                        (ext/merge-handlers composed)
                        (merge (:handlers dialogs)))))
        static-fx (merge (agent/create-fx
                          providers
                          (tooling-opts mgr (:ask! dialogs)))
                         (subagent/create-fx
                          providers
                          (subagent-opts mgr (:ask! dialogs)))
                         (fx/create-fx ring providers
                           {:system-prompt-fn
                            (fn [cwd]
                              (let [parts (into (system-prompt/load-agents-parts cwd)
                                                (ext/system-prompt-parts (manager/composed mgr) cwd))]
                                {:system       (system-prompt/parts->system parts)
                                 :system-parts parts}))})
                         (compaction/create-fx providers)
                         (naming/create-fx providers
                         {:make-config-dir!   session/make-throwaway-config-dir!
                          :remove-config-dir! session/remove-config-dir!})
                         (quick-replies/create-fx providers
                         {:make-config-dir!   session/make-throwaway-config-dir!
                          :remove-config-dir! session/remove-config-dir!})
                         (summary/create-fx providers
                         {:make-config-dir!   session/make-throwaway-config-dir!
                          :remove-config-dir! session/remove-config-dir!}))
        app (app/create-app {:initial-state (state/initial-state
                                             {:mode :standalone
                                              :port (:port opts)
                                              :user (own-user opts)
                                              :ext (:process-ext-init composed)})
                             :handlers      handlers
                             :transform-event (ext/transform-event composed)
                             :effects       (manager/live-view
                                             mgr
                                             (fn [composed]
                                               (merge static-fx (:fx composed) (:fx dialogs) (:effects client))))
                             :on-render     (:render client)
                             :ring          ring
                             :jsonl-writer  jsonl-writer})
        {:keys [dispatch!]} app
        _ (user-ext/start! app {:ask! (:ask! dialogs)})
        _ (ext-persist/install! app mgr)]
    (when jsonl-writer
      (js/process.on "exit" (fn [] ((:flush! jsonl-writer)))))
    (dispatch! (users/loaded-event (own-user opts)))
    (dispatch! {:type :room/create
                :room-id "main"
                :room {:model model
                       :cwd cwd
                       :effort effort
                       :system system
                       :system-parts system-parts
                       :agents-files agents-files
                       :ext (cond-> (:room-ext-init composed)
                              prof (merge (profile/room-ext prof)))
                       :agent-id agent
                       :only-tools (:tools prof)
                       :session sess}})
    (when-let [summary (and session-id
                            (if agent
                              (session/find-personal-agent-session-by-id session-id agent)
                              (session/find-session-by-id session-id)))]
      (dispatch! {:type :session/resumed
                  :room-id "main"
                  :session (session/load-session summary)
                  :summary summary
                  :messages (session/read-session-messages summary)}))
    (when (seq initial-prompt)
      (dispatch! {:type :prompt/submit :room-id "main" :text initial-prompt}))))

;; ── Prompt (one-shot, headless) ──────────────────────────────────────────────

(defn- read-stdin
  []
  (js/Promise.
   (fn [resolve _reject]
     (let [chunks #js []]
       (doto js/process.stdin
         (.on "data" (fn [c] (.push chunks (.toString c))))
         (.on "end" (fn [] (resolve (.trim (.join chunks ""))))))
       (.resume js/process.stdin)))))

(defn- start-prompt!
  "Run one prompt with no TUI and exit 0/1; the response streams (--stream) or
   prints at turn end. Dialogs resolve to their safe defaults. The run persists
   an Xi session (continue it via --session; --json emits {\"session-id\"
   \"text\"}), unless :no-store?, which runs against a throwaway
   CLAUDE_CONFIG_DIR torn down on exit and skips the session save."
  [{:keys [prompt-text stream? no-store? agent session-id json?] :as opts}]
  (let [agent?  (some? agent)
        prof    (when agent? (profile/load agent))
        _       (install-agent-extensions! prof)
        {:keys [model effort]} (resolve-model-opts
                                (update opts :model #(or % (:model prof))))
        resumed (when session-id
                  (if agent?
                    (session/find-personal-agent-session-by-id session-id agent)
                    (session/find-session-by-id session-id)))
        _ (when (and session-id (not resumed))
            (.write js/process.stderr (str "xi: session not found: " session-id "\n"))
            (js/process.exit 1))
        loaded (when resumed (session/load-session resumed))
        ;; Resumes follow the session's recorded cwd: the provider resolves the
        ;; resume id within the current cwd's transcript dir.
        cwd (or (aget js/process.env "XI_CWD")
                (when-let [c (:cwd loaded)] (when (fs/existsSync c) c))
                (:dir prof)
                (.cwd js/process))
        config-dir (when no-store? (session/make-throwaway-config-dir!))
        _ (when config-dir
            (aset js/process.env "CLAUDE_CONFIG_DIR" config-dir))
        ring (log/create-ring)
        ;; terminal-title would write ANSI escapes into the one-shot response.
        dialogs  (ext/create-dialogs)
        mgr      (manager/create)
        _        (manager/seed! mgr (remove #(= :terminal-title (:id %))
                                            (server-extensions ring (:ask! dialogs) mgr)))
        _        (mcp/install! mgr)
        _        (user-ext/install! mgr)
        composed (manager/composed mgr)
        agents-files (when-not agent? (system-prompt/find-agents-md cwd))
        system-parts (if prof
                       (profile/system-parts prof)
                       (into (system-prompt/load-agents-parts cwd)
                             (ext/system-prompt-parts composed cwd)))
        system (system-prompt/parts->system system-parts)
        sess (if loaded
               (assoc loaded :provider-session-id (:cli-session-id loaded))
               (session/create-session
                cwd (cond-> {:user (own-user opts)}
                      agent? (assoc :agent agent))))
        acc  #js {:out "" :error nil}
        finish!
        (fn []
          (ext/on-shutdown! composed)
          (when config-dir (session/remove-config-dir! config-dir))
          (let [code (if (.-error acc) 1 0)]
            (when (.-error acc)
              (.write js/process.stderr (str (.-error acc) "\n")))
            (let [tail (cond
                         ;; The session id is the Xi session id (continue via --session).
                         json?   (str (js/JSON.stringify
                                       #js {:session-id (:id sess)
                                            :text (.-out acc)})
                                      "\n")
                         stream? "\n"
                         :else   (str (.-out acc) "\n"))]
              (.write js/process.stdout tail
                      (fn [] (js/process.exit code))))))
        handlers (-> (make-handlers (:commands composed))
                     (assoc :prompt/submit (:prompt/submit agent/handlers))
                     (cond-> no-store?
                       (assoc :agent/turn-end (:agent/turn-end agent/handlers)))
                     (ext/merge-handlers composed)
                     (merge (:handlers dialogs)))
        {:keys [dispatch! add-tap!] :as app}
        (app/create-app {:initial-state (state/initial-state
                                         {:mode        :server
                                          :clientless? true
                                          :user        (own-user opts)
                                          :ext         (:process-ext-init composed)})
                         :handlers      handlers
                         :transform-event (ext/transform-event composed)
                         :effects       (merge (agent/create-fx
                                                providers
                                                (tooling-opts mgr (:ask! dialogs)))
                                               (subagent/create-fx
                                                providers
                                                (subagent-opts mgr (:ask! dialogs)))
                                               (fx/create-fx ring providers
                                                 {:system-prompt-fn
                                                  (fn [cwd]
                                                    (let [parts (into (system-prompt/load-agents-parts cwd)
                                                                      (ext/system-prompt-parts composed cwd))]
                                                      {:system       (system-prompt/parts->system parts)
                                                       :system-parts parts}))})
                                               (compaction/create-fx providers)
                                               (:fx composed)
                                               (:fx dialogs))
                         :ring          ring})]
    (user-ext/start! app {:ask! (:ask! dialogs)})
    (add-tap!
     (fn [event _state]
       (case (:type event)
         :agent/text-delta
         (let [t (:text event)]
           (set! (.-out acc) (str (.-out acc) t))
           (when (and stream? (not json?)) (.write js/process.stdout t)))

         :agent/error
         (set! (.-error acc) (or (get-in event [:error :message])
                                 (str (:error event))))

         ;; Deferred a tick: the :agent/turn-end effects include the session save.
         :agent/turn-end (js/setTimeout finish! 0)
         nil)))
    (dispatch! (users/loaded-event (own-user opts)))
    (dispatch! {:type :room/create
                :room-id "main"
                :room {:model model
                       :cwd cwd
                       :effort effort
                       :system system
                       :system-parts system-parts
                       :agents-files agents-files
                       :ext (cond-> (:room-ext-init composed)
                              prof (merge (profile/room-ext prof)))
                       :agent-id agent
                       :only-tools (:tools prof)
                       :session sess}})
    (dispatch! {:type :prompt/submit :room-id "main" :text prompt-text})))

;; ── Client (join/create + the server's local TUI) ────────────────────────────

(defn- client-url [{:keys [url port]}]
  (or url (str "ws://localhost:" port)))

(defn- server-running?
  [port]
  (js/Promise.
   (fn [resolve _reject]
     (let [net    (js/require "node:net")
           done?  (atom false)
           socket (.createConnection net #js {:port port :host "127.0.0.1"})
           finish (fn [result]
                    (when-not @done?
                      (reset! done? true)
                      (.destroy socket)
                      (resolve result)))]
       (.setTimeout socket 300)
       (.once socket "connect" (fn [] (finish true)))
       (.once socket "timeout" (fn [] (finish false)))
       (.once socket "error"   (fn [_] (finish false)))))))

(defn- start-client!
  "Connect a TUI to a running server: forward input, mirror broadcasts. The
   mirror extensions' room-state handlers chain onto the mirrored base; the
   local client extensions run unwrapped on this process."
  [{:keys [target initial-prompt defer-room? session-id] :as opts}]
  (let [;; --session rejoins that exact session instead of "latest".
        target (if session-id {:session-id session-id} target)
        defer-room? (if session-id false defer-room?)
        url (client-url opts)
        cwd (or (aget js/process.env "XI_CWD") (.cwd js/process))
        ring (log/create-ring)
        mirror (ext/compose (mirror-extensions))
        local  (ext/compose (client-extensions))
        commands (into (commands/all-commands (:commands mirror))
                       (:commands local))
        prompt-badge (fn [st] (str (ext/prompt-badges mirror st)
                                   (ext/prompt-badges local st)))
        keybindings (into (:keybindings mirror) (:keybindings local))
        ;; Deferred room: a client-local :pending room renders the empty chat
        ;; through the normal room paths (the transport's :local-room? seam). The
        ;; server room is created on the first submit: :local-submit stashes the
        ;; text + images and joins "new"; the stash replays on :room/joined.
        pending-room?
        (when defer-room?
          (fn [ev] (= :pending (:room-id ev))))
        pending-submit
        (when defer-room?
          (fn [st ev]
            (let [text   (case (:type ev)
                           :input/submit (:text ev)
                           :command/run  (str "/" (:name ev)
                                              (when (seq (:args ev))
                                                (str " " (:args ev)))))
                  images (into (vec (get-in st [:rooms :pending :ui :pending-images]))
                               (:images ev))]
              {:state   (assoc st :client/pending-submit {:text text :images images})
               :effects [[:ws/send (cond-> {:type :room/join :target "new"}
                                     cwd (assoc :cwd cwd))]]})))
        deferred-handlers
        (when defer-room?
          {:client/clear-pending
           (fn [st _] {:state (-> st
                                  (dissoc :client/pending-submit)
                                  (update :rooms dissoc :pending))})
           ;; Mirror the server's default model onto the pending room.
           :lobby/state
           (fn [st ev]
             (cond-> (ws-transport/lobby-state st ev)
               (and (:model ev) (get-in st [:rooms :pending]))
               (update :state assoc-in [:rooms :pending :agent :model] (:model ev))))})
        dispatch-ref (atom nil)
        transport (ws-transport/create!
                   {:url url
                    ;; The local client-key is trusted implicitly: no pairing wait.
                    :hello {:client-key  (auth/ensure-client-key!)
                            :client-name (str "tui@" (.hostname (js/require "node:os")))
                            :platform    "tui"
                            :user        (own-user opts)
                            ;; Passed to MCP servers as _meta (xi.ext.mcp/call-meta).
                            :pid         (.-pid js/process)}
                    :target target
                    :cwd cwd
                    :reconnect? true
                    :on-status (fn [connected?]
                                 (when-let [d @dispatch-ref]
                                   (d {:type :connection/status
                                       :connected? connected?})))})
        client (client-tui/create!
                {:ring ring
                 :on-exit (fn [] (ext/on-shutdown! local) ((:close! transport)))
                 :commands commands
                 :prompt-badge prompt-badge
                 :keybindings keybindings})
        base (-> (make-handlers (:commands mirror))
                 (ext/merge-handlers mirror))
        {:keys [dispatch! add-tap!]}
        (app/create-app {:initial-state (cond-> (state/initial-state
                                                 {:mode :client
                                                  :ext (:process-ext-init local)})
                                          defer-room?
                                          (-> (assoc-in [:rooms :pending]
                                                        (state/make-room
                                                         :pending
                                                         {:cwd cwd
                                                          :agents-files (system-prompt/find-agents-md cwd)}))
                                              (assoc :active-room :pending)))
                         :handlers      (ws-transport/make-handlers
                                         base
                                         {:local-room?  pending-room?
                                          :local-submit pending-submit
                                          ;; Editor inserts are client-side but emitted by server-side
                                          ;; handlers, so their effect passes the mirror strip.
                                          :client-fx #{:editor/insert-text}
                                          :local-handlers
                                          (merge (:handlers local)
                                                 deferred-handlers
                                                 ;; Client-local handlers, never forwarded.
                                                 {;; Forwarded so the server attaches us to
                                                  ;; the session's live room (room-for-session).
                                                  :room/join
                                                  (fn [_st ev] {:effects [[:ws/send ev]]})
                                                  :connection/status
                                                  (fn [st {:keys [connected?]}]
                                                    {:state (assoc st :client/connected? connected?)})
                                                  :auth/pending
                                                  (fn [st {:keys [code]}]
                                                    {:state (assoc st :client/auth {:status :pending :code code})})
                                                  :auth/ok
                                                  (fn [st ev]
                                                    (let [st (dissoc st :client/auth)]
                                                      {:state (or (:state (ws-transport/auth-ok st ev)) st)}))
                                                  :auth/denied
                                                  (fn [st _] {:state (assoc st :client/auth {:status :denied})})
                                                  :client/optimistic-set
                                                  (fn [st {:keys [text images]}]
                                                    {:state (assoc st :client/optimistic
                                                                   (cond-> {:kind :user :text text}
                                                                     (seq images) (assoc :images (vec images))))})
                                                  :client/optimistic-clear
                                                  (fn [st _] {:state (dissoc st :client/optimistic)})})})
                         :transform-event (ext/transform-event local)
                         ;; Mirror fx run only for the pending room (defer mode is
                         ;; localhost-only, so running them in-process is equivalent).
                         :effects       (merge (when defer-room? (:fx mirror))
                                               (:effects transport)
                                               (:fx local)
                                               (:effects client))
                         :on-render     (:render client)
                         :ring          ring})]
    ;; Optimistic prompt echo, like the web client; cleared when the server
    ;; broadcasts the real :prompt/submit. Queued submissions get none.
    (add-tap!
     (fn [event state]
       (cond
         (and (= :input/submit (:type event))
              (not (:remote? event))
              (not (get-in state [:rooms (:room-id event) :agent :busy?]))
              (let [parsed (commands/parse-input (:text event))]
                (or (= :prompt (:type parsed))
                    (seq (:images event)))))
         (dispatch! {:type :client/optimistic-set
                     :text (:text event)
                     :images (:images event)})

         (and (:remote? event)
              (= :prompt/submit (:type event)))
         (dispatch! {:type :client/optimistic-clear}))))
    ;; Deferred room: replay the stashed first submission once the room joins,
    ;; images first.
    (when defer-room?
      (add-tap!
       (fn [event state]
         (when (and (= :room/joined (:type event))
                    (:client/pending-submit state))
           (let [{:keys [text images]} (:client/pending-submit state)
                 room-id (:room-id event)]
             (dispatch! {:type :client/clear-pending})
             (doseq [img images]
               (dispatch! {:type :ui/attach-image :room-id room-id
                           :image img :label "image"}))
             (dispatch! {:type :input/submit :room-id room-id :text text}))))))
    ;; Auto-submit the initial prompt once the room is joined. Fires once.
    (when (seq initial-prompt)
      (let [sent? (atom false)]
        (add-tap!
         (fn [event _state]
           (when (and (= :room/joined (:type event)) (not @sent?))
             (reset! sent? true)
             (dispatch! {:type :prompt/submit
                         :room-id (:room-id event)
                         :text initial-prompt}))))))
    (reset! dispatch-ref dispatch!)
    ((:set-dispatch! transport) dispatch!)))

;; ── Server ───────────────────────────────────────────────────────────────────

(defn- log-crash!
  "Record a stray async error to stderr and ~/.config/xi/crash.log (which
   survives a `bb serve:restart`)."
  [label err]
  (js/console.error (str "[xi] " label ":") (crash-log/record! label err)))

(defn- install-crash-guard!
  "Keep the server alive across stray async errors (notably EPIPE from a
   subprocess pipe on teardown), logging each loudly so root causes stay
   findable."
  []
  (.on js/process "unhandledRejection"
       (fn [reason _promise] (log-crash! "unhandledRejection" reason)))
  (.on js/process "uncaughtException"
       (fn [err] (log-crash! "uncaughtException" err))))

(defn- start-server!
  "Host rooms over WS. Unless --headless, a local TUI joins through the same WS
   path as any remote client."
  [{:keys [port host headless? agent] :as opts}]
  (install-crash-guard!)
  ;; Drop stale /nix/store env vars so spawned tools use the current toolchain.
  (env/sanitize-inherited-env!)
  (let [;; --agent: the profile's :model is the default; its :extensions
        ;; replace the rules.edn list.
        prof        (when agent (profile/load agent))
        _           (install-agent-extensions! prof)
        server-opts (resolve-model-opts
                     (update opts :model #(or % (:model prof))))
        ring (log/create-ring)
        dialogs  (ext/create-dialogs)
        mgr      (manager/create)
        _        (manager/seed! mgr (server-extensions ring (:ask! dialogs) mgr))
        _        (mcp/install! mgr)
        _        (user-ext/install! mgr)
        composed (manager/composed mgr)
        server (ws/create-server
                {:server-opts server-opts
                 :providers providers
                 :agent-id agent
                 :ext-system-prompt-parts (fn [cwd] (ext/system-prompt-parts (manager/composed mgr) cwd))
                 :room-ext-init (manager/live-view mgr :room-ext-init)
                 :usage-sources (manager/live-view mgr :usage-sources)
                 :ext composed})
        ;; Late-bound: a live extension reload rebuilds handlers and fx.
        handlers (manager/live-view
                  mgr
                  (fn [composed]
                   (-> (make-handlers (:commands composed))
                     (ext/merge-handlers composed)
                     (merge (:handlers dialogs))
                     (merge rm/handlers)
                     ;; Reaping on :room/attach catches the room a client left by switching
                     ;; chats (a re-attach sends no :room/leave).
                     (update :room/attach events/chain rm/reap-idle-clientless-rooms)
                     ;; Buffers outlive their room: parked per session, revived into its next room.
                     (update :room/close #(events/chain rm/park-buffers %))
                     (update :room/create events/chain rm/revive-buffers)
                     (update :session/delete events/chain rm/forget-parked-buffers)
                     (update :client/update events/chain rm/client-update-presence)
                     ;; Sessions busy at a hard restart auto-resume, queue included.
                     (update :agent/session-init events/chain commands/in-flight-session-sync)
                     (update :prompt/submit events/chain commands/in-flight-session-sync)
                     (update :prompt/queue-remove events/chain commands/in-flight-session-sync)
                     (update :agent/turn-end events/chain rm/turn-end-room-cleanup)
                     (assoc :client/disconnect
                            (events/chain rm/client-disconnect-cleanup
                                          (:client/disconnect events/core-handlers))))))
        app (app/create-app {:initial-state (state/initial-state
                                             {:mode :server
                                              :port port
                                              :user (own-user opts)
                                              :ext (:process-ext-init composed)})
                             :handlers handlers
                             :transform-event (ext/transform-event composed)
                             :effects  (let [static-fx
                                             (merge (agent/create-fx
                                                     providers
                                                     (tooling-opts mgr (:ask! dialogs)))
                                                    (subagent/create-fx
                                                     providers
                                                     (subagent-opts mgr (:ask! dialogs)))
                                                    (fx/create-fx ring providers
                                                      {:system-prompt-fn
                                                       (fn [cwd]
                                                         (let [parts (into (system-prompt/load-agents-parts cwd)
                                                                           (ext/system-prompt-parts (manager/composed mgr) cwd))]
                                                           {:system       (system-prompt/parts->system parts)
                                                            :system-parts parts}))})
                                                    (compaction/create-fx providers)
                                                    (naming/create-fx providers
                                                     {:make-config-dir!   session/make-throwaway-config-dir!
                                                      :remove-config-dir! session/remove-config-dir!})
                                                    (quick-replies/create-fx providers
                                                     {:make-config-dir!   session/make-throwaway-config-dir!
                                                      :remove-config-dir! session/remove-config-dir!})
                                                    (summary/create-fx providers
                                                     {:make-config-dir!   session/make-throwaway-config-dir!
                                                      :remove-config-dir! session/remove-config-dir!}))]
                                         (manager/live-view
                                          mgr
                                          (fn [composed]
                                            (merge static-fx (:fx composed) (:fx dialogs) (:fx server)))))
                             :on-runaway (fn [msg] (log-crash! "dispatch-livelock" msg))
                             :ring ring})
        _ (user-ext/start! app {:ask! (:ask! dialogs)})
        _ (ext-persist/install! app mgr)
        ;; Rooms the server provisions itself act for its own user.
        _ ((:dispatch! app) (users/loaded-event (own-user opts)))
        {actual-port :port} ((:start! server) app {:port port :host host})]
    (if headless?
      (do (js/console.error (str "[xi] Headless server on ws://" (str/join "," (ws/resolve-hosts host)) ":" actual-port))
          (js/console.error "[xi] Connect with: xi join"))
      (start-client! {:target "new" :port actual-port :user (:user opts)}))))

;; ── Entry ────────────────────────────────────────────────────────────────────

(defn- run-prompt!
  [{:keys [prompt-parts] :as opts}]
  (let [inline (some->> (seq prompt-parts) (str/join " "))]
    (cond
      (seq inline) (start-prompt! (assoc opts :prompt-text inline))
      (not (.-isTTY js/process.stdin))
      (-> (read-stdin)
          (.then (fn [text]
                   (if (seq text)
                     (start-prompt! (assoc opts :prompt-text text))
                     (do (js/console.error "xi prompt: no prompt provided")
                         (js/process.exit 1))))))
      :else (do (js/console.error "usage: xi prompt [--stream] [--no-store] <text>   (or pipe text via stdin)")
                (js/process.exit 1)))))

(defn- start-standalone-or-join!
  "Launch standalone, but connect to a running server when one listens on the
   port and auto-join isn't disabled. Always opens a fresh room; /resume
   re-attaches via a {:session-id} target."
  [{:keys [auto-join? port initial-prompt session-id agent] :as opts}]
  ;; --agent always stays local: a running server provisions rooms from its
  ;; own profile.
  (if (or (not auto-join?) agent)
    (start-standalone! opts)
    (-> (server-running? port)
        (.then (fn [running?]
                 (cond
                   (not running?) (start-standalone! opts)
                   session-id (start-client! opts)
                   (seq initial-prompt) (start-client! (assoc opts :target "new"))
                   ;; Otherwise a virtual room: the server room is created on the first prompt.
                   :else (start-client! (assoc opts :target nil :defer-room? true))))))))

;; ── Sessions (headless listing) ──────────────────────────────────────────────

(defn- fetch-recent-sessions
  "The running server's \"Recent\" set, read from its lobby websocket through
   the shared xi.session.recent filter so the CLI and the web agree. Resolves a
   vector of {:session-id :name :cwd}, or nil when no server answers."
  [opts]
  (js/Promise.
   (fn [resolve _reject]
     (let [done?  (atom false)
           timer  (atom nil)
           closer (atom nil)
           finish (fn [result]
                    (when-not @done?
                      (reset! done? true)
                      (when-let [t @timer] (js/clearTimeout t))
                      (when-let [c @closer] (try (c) (catch :default _ nil)))
                      (resolve result)))
           transport (ws-transport/create!
                      {:url        (client-url opts)
                       :hello      {:client-key  (auth/ensure-client-key!)
                                    :client-name (str "cli@" (.hostname (js/require "node:os")))
                                    :platform    "cli"
                                    :pid         (.-pid js/process)}
                       :target     nil
                       :reconnect? false
                       :on-close   (fn [] (finish nil))})]
       (reset! closer (:close! transport))
       ((:set-dispatch! transport)
        (fn [ev]
          (when (= :lobby/state (:type ev))
            (let [cards (recent/recent-cards
                         {:rooms      (:rooms ev)
                          :sessions   (:sessions ev)
                          :started-at (:started-at ev)
                          :now        (js/Date.now)})]
              (finish (mapv #(select-keys % [:session-id :name :cwd]) cards))))))
       (reset! timer (js/setTimeout #(finish nil) 3000))))))

(defn- print-sessions! [{:keys [json? limit]} sessions]
  (let [sessions (cond->> sessions
                   (and limit (pos? limit)) (take limit))]
    (if json?
      (.write js/process.stdout
              (str (js/JSON.stringify (clj->js sessions)) "\n"))
      (doseq [{:keys [session-id name cwd]} sessions]
        (.write js/process.stdout (str session-id "\t" name "\t" cwd "\n"))))
    (js/process.exit 0)))

(defn- run-sessions!
  "List saved chats and exit: the recent set from the running server's lobby
   (--all lists every saved chat from disk), --limit N caps, --json emits an
   array instead of TSV lines."
  [{:keys [all? port] :as opts}]
  (if all?
    (print-sessions! opts (->> (session/list-all-sessions)
                               (map #(dissoc % :filepath :user-messages))))
    (-> (fetch-recent-sessions opts)
        (.then
         (fn [rows]
           (if (nil? rows)
             (do (.write js/process.stderr
                         (str "xi sessions: no running server on port "
                              port
                              " — recent is relative to a running server. "
                              "Use --all to list every saved chat.\n"))
                 (print-sessions! opts []))
             (print-sessions! opts rows)))))))

(defn- fmt-ts [ts]
  (if (number? ts)
    (-> (js/Date. ts) .toISOString (.replace "T" " ") (.slice 0 16))
    "?"))

(defn- run-clients!
  "Manage the client-key auth store (~/.config/xi/clients.edn) from the shell;
   the running server polls it, so an approval is admitted within ~2s."
  [{:keys [clients-args]}]
  (let [[action arg] clients-args
        die! (fn [& lines]
               (doseq [l (remove nil? lines)] (.write js/process.stderr (str l "\n")))
               (js/process.exit 1))]
    (case (or action "list")
      "list"
      (let [clients (auth/approved-clients)]
        (if (empty? clients)
          (println "No approved clients (the local TUI key is trusted implicitly).")
          (doseq [[k {:keys [name platform approved-at last-seen user]}] clients]
            (println (str "  " (subs k 0 (min 8 (count k))) "…  "
                          (or name "unknown") " (" (or platform "?") ")"
                          (when user (str "  user " user))
                          "  approved " (fmt-ts approved-at)
                          (when last-seen (str "  last seen " (fmt-ts last-seen))))))))

      "pending"
      (let [pending (auth/read-pending)]
        (if (empty? pending)
          (println "No pending clients.")
          (doseq [[code {:keys [client-name platform requested-at]}] pending]
            (println (str "  " code "  " (or client-name "unknown")
                          " (" (or platform "?") ")"
                          "  requested " (fmt-ts requested-at))))))

      "approve"
      (let [pending (auth/read-pending)
            entry   (get pending arg)]
        (cond
          (nil? arg)
          (die! "usage: xi clients approve <code>   (see xi clients pending)")

          (nil? entry)
          (die! (str "No pending client with code " arg ".")
                (when (seq pending)
                  (str "Pending codes: " (str/join ", " (keys pending)))))

          :else
          (do (auth/approve! (:client-key entry)
                             {:name     (:client-name entry)
                              :platform (:platform entry)})
              (auth/remove-pending! arg)
              (println (str "Approved " (or (:client-name entry) "unknown")
                            " (" arg ") — the server admits it within ~2s.")))))

      ("revoke" "user")
      (let [clients (auth/approved-clients)
            hits    (filter (fn [[k {:keys [name]}]]
                              (or (and arg (str/starts-with? k arg))
                                  (= name arg)))
                            clients)
            usage   (if (= action "user")
                      "usage: xi clients user <key-prefix|name> <user-id|->   (see xi clients list)"
                      "usage: xi clients revoke <key-prefix|name>   (see xi clients list)")
            user-id (when (= action "user") (second (next clients-args)))
            short   (fn [k] (str (subs k 0 (min 8 (count k))) "…"))]
        (cond
          (or (nil? arg) (and (= action "user") (nil? user-id)))
          (die! usage)

          (empty? hits)
          (die! (str "No approved client matches " arg "."))

          (> (count hits) 1)
          (apply die! "Ambiguous — matches:"
                 (map (fn [[k {:keys [name]}]]
                        (str "  " (short k) "  " (or name "unknown")))
                      hits))

          (= action "revoke")
          (let [[k {:keys [name]}] (first hits)]
            (auth/revoke! k)
            (println (str "Revoked " (or name "unknown") " (" (short k) ").")))

          ;; `-` clears the assignment.
          (= user-id "-")
          (let [[k {:keys [name]}] (first hits)]
            (auth/set-user! k nil)
            (println (str (or name "unknown") " (" (short k) ") no longer assigned to a user"
                          " — applies on its next connection.")))

          (not= user-id (util/user-id user-id))
          (die! (str "Invalid user id " (pr-str user-id)
                     " — lowercase letters, digits, '.', '_' or '-', up to 64 chars."))

          :else
          (let [[k {:keys [name]}] (first hits)]
            (auth/set-user! k user-id)
            (println (str (or name "unknown") " (" (short k) ") now acts as user " user-id
                          " — applies on its next connection.")))))

      (die! (str "Unknown clients action: " action)
            "usage: xi clients [list|pending|approve <code>|revoke <key-prefix|name>|user <key-prefix|name> <user-id|->]"))
    (js/process.exit 0)))

(defn- silence-worker-console!
  "Drop a worker's console: the TUI's stdout interception never sees it, so
   anything it logs (the shadow-cljs devtools client in dev builds) paints over
   the alternate screen."
  []
  (doseq [k ["log" "info" "debug" "warn" "error"]]
    (aset js/console k (fn [& _] nil))))

(defn main [& args]
  (if-not wt/isMainThread
    ;; A worker_threads Worker of the same bundle: become the role workerData
    ;; names (xi.ext.clj-worker, xi.ext.clj-socket).
    (do (silence-worker-console!)
        (if (= "xi-socket-bridge" (some-> wt/workerData (aget "role")))
          (clj-socket/bridge-install!)
          (clj-worker/install!)))
    (let [{:keys [command] :as opts} (resolve-port (parse-args args))]
      (rules-store/set-hardened-disabled! (:no-hardened-rules? opts))
      (when-let [script (fake/script-path)]
        (.write js/process.stderr (str "[fake-llm] XI_FAKE_LLM=" script
                                       " — every model is the scripted fake\n")))
      (case command
        :help       (do (.write js/process.stdout (str HELP_TEXT "\n"))
                        (js/process.exit 0))
        :standalone (start-standalone-or-join! opts)
        :server     (start-server! opts)
        :prompt     (run-prompt! opts)
        :sessions   (run-sessions! opts)
        :clients    (run-clients! opts)
        :join       (start-client! (assoc opts :target "latest"))
        :create     (start-client! (assoc opts :target "new"))))))
