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

(defn- make-handlers []
  (-> (merge events/core-handlers
             agent/handlers
             commands/handlers
             compaction/handlers)
      ;; Persist the session once the provider reports a session id
      (assoc :agent/turn-end (events/chain (:agent/turn-end agent/handlers)
                                           commands/turn-end-session-sync)
             ;; Escape also stops an in-flight compaction
             :agent/abort (events/chain (:agent/abort agent/handlers)
                                        compaction/abort-handler))))

;; ── Standalone (phase 4, unchanged) ──────────────────────────────────────────

(defn- start-standalone! [{:keys [debug-events?] :as opts}]
  (let [settings (load-settings)
        {:keys [model effort]} (resolve-model-opts opts settings)
        cwd (or (aget js/process.env "XI_CWD") (.cwd js/process))
        agents-files (system-prompt/find-agents-md cwd)
        system (system-prompt/load-agents-md cwd)
        sess (session/create-session cwd)
        ring (log/create-ring)
        jsonl-writer (when debug-events?
                       (core-jsonl/create-writer
                        (str (aget js/process.env "HOME")
                             "/.pi/agent/logs/" (:id sess) ".events.jsonl")))
        client (client-tui/create!
                {:ring ring
                 :on-exit (fn [] (when jsonl-writer ((:flush! jsonl-writer))))})
        {:keys [dispatch!]}
        (app/create-app {:initial-state (state/initial-state {:mode :standalone})
                         :handlers      (make-handlers)
                         :effects       (merge (agent/create-fx providers)
                                               (fx/create-fx)
                                               (compaction/create-fx providers)
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
  "Connect a TUI to a running server: forward input, mirror broadcasts."
  [{:keys [target] :as opts}]
  (let [url (client-url opts)
        ring (log/create-ring)
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
                 :on-exit (fn [] ((:close! transport)))})
        {:keys [dispatch!]}
        (app/create-app {:initial-state (state/initial-state {:mode :client})
                         :handlers      (ws-transport/make-handlers (make-handlers))
                         :effects       (merge (:effects transport)
                                               (:effects client))
                         :on-render     (:render client)
                         :ring          ring})]
    ((:set-dispatch! transport) dispatch!)))

;; ── Server ───────────────────────────────────────────────────────────────────

(defn- start-server!
  "Host rooms over WS. The server app runs providers + sessions and has no
   renderer; unless --headless, a local TUI joins through the same WS path
   as any remote client."
  [{:keys [port headless?] :as opts}]
  (let [settings (load-settings)
        server-opts (resolve-model-opts opts settings)
        ring (log/create-ring)
        server (ws/create-server {:server-opts server-opts})
        handlers (-> (make-handlers)
                     (merge rm/handlers)
                     ;; Auto-destroy: turn finished with nobody attached /
                     ;; last client dropped while idle
                     (update :agent/turn-end events/chain rm/turn-end-room-cleanup)
                     (assoc :client/disconnect
                            (events/chain rm/client-disconnect-cleanup
                                          (:client/disconnect events/core-handlers))))
        app (app/create-app {:initial-state (state/initial-state {:mode :server})
                             :handlers handlers
                             :effects  (merge (agent/create-fx providers)
                                              (fx/create-fx)
                                              (compaction/create-fx providers)
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
