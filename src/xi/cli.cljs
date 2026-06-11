(ns xi.cli
  "Xi entry point — one core, three connection modes.

   Subcommands:
     xi               → standalone TUI (one local room, no sockets)
     xi server        → host rooms over WS; local TUI joins via WS
                        (--headless for server-only)
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
     --port N         WS port (server/join/create; default 7474)
     --headless       server only, no local TUI
     --debug-events   (standalone) write the full event stream to
                      ~/.pi/agent/logs/<session>.events.jsonl"
  (:require [xi.agent :as agent]
            [xi.client.tui :as client-tui]
            [xi.client.ws-transport :as ws-transport]
            [xi.commands :as commands]
            [xi.compaction :as compaction]
            [xi.core.app :as app]
            [xi.core.events :as events]
            [xi.core.jsonl :as core-jsonl]
            [xi.core.log :as log]
            [xi.core.state :as state]
            [xi.ext.clipboard-image :as ext.clipboard-image]
            [xi.ext.clj-surgeon :as ext.clj-surgeon]
            [xi.ext.commit :as ext.commit]
            [xi.ext.core :as ext]
            [xi.ext.dictation :as ext.dictation]
            [xi.ext.done-notify :as ext.done-notify]
            [xi.ext.events :as ext.events]
            [xi.ext.gtd :as ext.gtd]
            [xi.ext.kb :as ext.kb]
            [xi.ext.perplexity :as ext.perplexity]
            [xi.ext.permission-gate :as ext.permission-gate]
            [xi.ext.plan-mode :as ext.plan-mode]
            [xi.ext.projects :as ext.projects]
            [xi.ext.pushover :as ext.pushover]
            [xi.ext.skills :as ext.skills]
            [xi.ext.terminal-title :as ext.terminal-title]
            [xi.ext.todo-intercept :as ext.todo-intercept]
            [xi.ext.web :as ext.web]
            [xi.fx :as fx]
            [xi.provider.claude :as claude]
            [xi.provider.ollama :as ollama]
            [xi.server.room-manager :as rm]
            [xi.server.ws :as ws]
            [xi.session :as session]
            [xi.system-prompt :as system-prompt]
            [xi.tui.core :as tui]
            [xi.tui.terminal :as term]))

(def providers
  {:claude claude/provider
   :ollama ollama/provider})

;; ── Extensions (per mode) ─────────────────────────────────────────────────────
;;
;; Extensions are composed at assembly time into the seams the core, the
;; provider effects and the TUI consume. Every seam degrades to a no-op
;; when no extensions are present.
;;
;; Two groups, because state lives in two places:
;;   server-extensions — state + provider/tool hooks that run server-side.
;;                       In client mode their room-state handlers are
;;                       mirrored and their badges/keybindings/commands
;;                       are presented locally.
;;   client-extensions — process-local, run in the TUI client process
;;                       (dictation): handlers installed unwrapped, fx local.

(defn- server-extensions
  "Extensions whose state + provider hooks live server-side. nils (e.g.
   an unconfigured pushover) are dropped by ext/compose."
  [ring]
  [ext.plan-mode/extension
   ext.done-notify/extension
   (ext.pushover/extension)
   ext.kb/extension
   ext.web/extension
   ext.perplexity/extension
   ext.commit/extension
   ext.clj-surgeon/extension
   ext.gtd/extension
   ext.permission-gate/extension
   ext.todo-intercept/extension
   ext.terminal-title/extension
   ext.clipboard-image/extension
   ext.projects/extension
   ext.skills/extension
   (ext.events/create ring)])

(defn- client-extensions
  "Process-local extensions that run in the TUI client process."
  []
  [(ext.dictation/create)])

(defn- tooling-opts
  "Provider-effect tooling threaded into agent/create-fx from a composed
   extension set + the dialog ask!."
  [composed ask!]
  {:tool-gate              (ext/tool-gate composed)
   :extra-tool-definitions (:tool-definitions composed)
   :extra-tool-registry    (:tool-registry composed)
   :ask!                   ask!})

(def ^:private DEFAULT_MODEL "claude-sonnet-4-20250514")

(def ^:private THINKING_TO_EFFORT
  "Map Pi thinking levels → Claude SDK effort levels."
  {"minimal" "low"
   "low"     "low"
   "medium"  "medium"
   "high"    "high"
   "xhigh"   "max"})

(defn- load-settings []
  (try
    (let [path (str (aget js/process.env "HOME") "/.pi/agent/settings.json")
          content (.readFileSync (js/require "node:fs") path "utf8")]
      (js->clj (js/JSON.parse content) :keywordize-keys true))
    (catch :default _e {})))

(defn- parse-args [args]
  (loop [args (seq args) opts {:command :standalone}]
    (if-not args
      opts
      (let [arg (first args)]
        (case arg
          "server"         (recur (next args) (assoc opts :command :server))
          "join"           (recur (next args) (assoc opts :command :join))
          "create"         (recur (next args) (assoc opts :command :create))
          "--headless"     (recur (next args) (assoc opts :headless? true))
          "--personal-agent-only" (recur (next args) (assoc opts :personal-agent? true))
          "--debug-events" (recur (next args) (assoc opts :debug-events? true))
          "--model"        (recur (nnext args) (assoc opts :model (second args)))
          "--port"         (recur (nnext args) (assoc opts :port (js/parseInt (second args) 10)))
          (recur (next args)
                 ;; Positional URL for join/create
                 (if (and (#{:join :create} (:command opts))
                          (not (.startsWith arg "--")))
                   (assoc opts :url (if (.startsWith arg "ws") arg (str "ws://" arg)))
                   opts)))))))

(defn- resolve-model-opts [{:keys [model]} settings]
  {:model  (or model
               (aget js/process.env "XI_MODEL")
               (:defaultModel settings)
               DEFAULT_MODEL)
   :effort (or (aget js/process.env "XI_EFFORT")
               (get THINKING_TO_EFFORT (:defaultThinkingLevel settings))
               "high")})

(defn- make-handlers
  "Base pure handler map shared by every mode. extra-commands are extension
   commands that join the built-ins for dispatch + /help."
  ([] (make-handlers nil))
  ([extra-commands]
   (-> (merge events/core-handlers
              agent/handlers
              (commands/command-handlers extra-commands)
              compaction/handlers)
       ;; Persist the session once the provider reports a session id
       (assoc :agent/turn-end (events/chain (:agent/turn-end agent/handlers)
                                            commands/turn-end-session-sync)
              ;; Escape also stops an in-flight compaction
              :agent/abort (events/chain (:agent/abort agent/handlers)
                                         compaction/abort-handler)))))

;; ── Standalone (phase 4, unchanged) ──────────────────────────────────────────

(defn- start-standalone! [{:keys [debug-events?] :as opts}]
  (let [settings (load-settings)
        {:keys [model effort]} (resolve-model-opts opts settings)
        cwd (or (aget js/process.env "XI_CWD") (.cwd js/process))
        ring (log/create-ring)
        ;; Standalone runs everything locally — server + client extensions.
        composed (ext/compose (into (server-extensions ring) (client-extensions)))
        dialogs  (ext/create-dialogs)
        agents-files (system-prompt/find-agents-md cwd)
        system (system-prompt/combine (system-prompt/load-agents-md cwd)
                                      (ext/system-prompt composed cwd))
        sess (session/create-session cwd)
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
        handlers (-> (make-handlers (:commands composed))
                     (ext/merge-handlers composed)
                     (merge (:handlers dialogs)))
        {:keys [dispatch!]}
        (app/create-app {:initial-state (state/initial-state
                                         {:mode :standalone
                                          :ext (:process-ext-init composed)})
                         :handlers      handlers
                         :transform-event (ext/transform-event composed)
                         :effects       (merge (agent/create-fx
                                                providers
                                                (tooling-opts composed (:ask! dialogs)))
                                               (fx/create-fx ring)
                                               (compaction/create-fx providers)
                                               (:fx composed)
                                               (:fx dialogs)
                                               (:effects client))
                         :on-render     (:render client)
                         :ring          ring
                         :jsonl-writer  jsonl-writer})]
    (when jsonl-writer
      (js/process.on "exit" (fn [] ((:flush! jsonl-writer)))))
    (dispatch! {:type :room/create
                :room-id "main"
                :room {:model model
                       :cwd cwd
                       :effort effort
                       :system system
                       :agents-files agents-files
                       :ext (:room-ext-init composed)
                       :session sess}})
    ;; Auto-resume after /reload (env var set by the :app/reload effect)
    (when-let [reload-sid (aget js/process.env "XI_RELOAD_SESSION")]
      (js-delete js/process.env "XI_RELOAD_SESSION")
      (when-let [summary (session/find-session-by-id reload-sid)]
        (dispatch! {:type :session/resumed
                    :room-id "main"
                    :session (session/load-session summary)
                    :summary summary
                    :messages (session/read-session-messages summary)})))))

;; ── Client (join/create + the server's local TUI) ────────────────────────────

(defn- client-url [{:keys [url port]}]
  (or url (str "ws://localhost:" (or port ws/DEFAULT_PORT))))

(defn- start-client!
  "Connect a TUI to a running server: forward input, mirror broadcasts.

   Two composed sets:
     mirror — server-side extensions; their room-state handlers are chained
              onto the mirrored base so replayed broadcasts stay in sync,
              and their commands/badges/keybindings are presented locally.
     local  — process-local client extensions; handlers installed unwrapped
              (never forwarded), fx + process state run on this client."
  [{:keys [target] :as opts}]
  (let [url (client-url opts)
        ring (log/create-ring)
        mirror (ext/compose (server-extensions nil))
        local  (ext/compose (client-extensions))
        commands (into (commands/all-commands (:commands mirror))
                       (:commands local))
        prompt-badge (fn [st] (str (ext/prompt-badges mirror st)
                                   (ext/prompt-badges local st)))
        keybindings (into (:keybindings mirror) (:keybindings local))
        transport (ws-transport/create!
                   {:url url
                    :target target
                    :cwd (or (aget js/process.env "XI_CWD") (.cwd js/process))
                    :on-close (fn []
                                (term/restore-stdout!)
                                (tui/stop-tui!)
                                (js/console.error (str "Disconnected from " url))
                                (js/process.exit 1))})
        client (client-tui/create!
                {:ring ring
                 :on-exit (fn [] (ext/on-shutdown! local) ((:close! transport)))
                 :commands commands
                 :prompt-badge prompt-badge
                 :keybindings keybindings})
        base (-> (make-handlers (:commands mirror))
                 (ext/merge-handlers mirror))
        {:keys [dispatch!]}
        (app/create-app {:initial-state (state/initial-state
                                         {:mode :client
                                          :ext (:process-ext-init local)})
                         :handlers      (ws-transport/make-handlers
                                         base
                                         {:local-handlers (:handlers local)})
                         ;; Only client-local hooks run here; server hooks
                         ;; ran server-side and mirrored events bypass them.
                         :transform-event (ext/transform-event local)
                         :effects       (merge (:effects transport)
                                               (:fx local)
                                               (:effects client))
                         :on-render     (:render client)
                         :ring          ring})]
    ((:set-dispatch! transport) dispatch!)))

;; ── Server ───────────────────────────────────────────────────────────────────

(defn- start-server!
  "Host rooms over WS. The server app runs providers + sessions and has no
   renderer; unless --headless, a local TUI joins through the same WS path
   as any remote client."
  [{:keys [port headless? personal-agent?] :as opts}]
  (let [settings (load-settings)
        server-opts (resolve-model-opts opts settings)
        ring (log/create-ring)
        composed (ext/compose (server-extensions ring))
        dialogs  (ext/create-dialogs)
        server (ws/create-server
                {:server-opts server-opts
                 :personal-agent? personal-agent?
                 :ext-system-prompt (fn [cwd] (ext/system-prompt composed cwd))
                 :room-ext-init (:room-ext-init composed)})
        handlers (-> (make-handlers (:commands composed))
                     (ext/merge-handlers composed)
                     (merge (:handlers dialogs))
                     (merge rm/handlers)
                     ;; Auto-destroy: turn finished with nobody attached /
                     ;; last client dropped while idle
                     (update :agent/turn-end events/chain rm/turn-end-room-cleanup)
                     (assoc :client/disconnect
                            (events/chain rm/client-disconnect-cleanup
                                          (:client/disconnect events/core-handlers))))
        app (app/create-app {:initial-state (state/initial-state
                                             {:mode :server
                                              :ext (:process-ext-init composed)})
                             :handlers handlers
                             :transform-event (ext/transform-event composed)
                             :effects  (merge (agent/create-fx
                                               providers
                                               (tooling-opts composed (:ask! dialogs)))
                                              (fx/create-fx ring)
                                              (compaction/create-fx providers)
                                              (:fx composed)
                                              (:fx dialogs)
                                              (:fx server))
                             :ring ring})
        {actual-port :port} ((:start! server) app {:port port})]
    (if headless?
      (do (js/console.error (str "[xi] Headless server on ws://localhost:" actual-port))
          (js/console.error "[xi] Connect with: xi join"))
      (start-client! {:target "new" :port actual-port}))))

;; ── Entry ────────────────────────────────────────────────────────────────────

(defn main [& args]
  (let [{:keys [command] :as opts} (parse-args args)]
    (case command
      :standalone (start-standalone! opts)
      :server     (start-server! opts)
      :join       (start-client! (assoc opts :target "latest"))
      :create     (start-client! (assoc opts :target "new")))))
