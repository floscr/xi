(ns xi.cli
  "Xi entry point — one core, three connection modes.

   Subcommands:
     xi               → standalone TUI (one local room, no sockets)
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
     --port N         WS port (server/join/create; default 7474)
     --headless       server only, no local TUI
     --stream         (prompt) stream response tokens to stdout as they arrive
     --debug-events   (standalone) write the full event stream to
                      ~/.pi/agent/logs/<session>.events.jsonl"
  (:require [clojure.string :as str]
            [xi.agent :as agent]
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
            [xi.ext.worktree.core :as ext.worktree]
            [xi.ext.core :as ext]
            [xi.ext.dictation :as ext.dictation]
            [xi.ext.diff.core :as ext.diff]
            [xi.ext.done-notify :as ext.done-notify]
            [xi.ext.events :as ext.events]
            [xi.ext.github :as ext.github]
            [xi.ext.github-code-search.core :as ext.github-code-search]
            [xi.ext.gtd :as ext.gtd]
            [xi.ext.kb :as ext.kb]
            [xi.ext.perplexity :as ext.perplexity]
            [xi.ext.permission-gate :as ext.permission-gate]
            [xi.ext.plan-mode :as ext.plan-mode]
            [xi.ext.process-manager :as ext.process-manager]
            [xi.ext.projects :as ext.projects]
            [xi.ext.pushover :as ext.pushover]
            [xi.ext.skills :as ext.skills]
            [xi.ext.terminal-title :as ext.terminal-title]
            [xi.ext.todo-intercept :as ext.todo-intercept]
            [xi.ext.web :as ext.web]
            [xi.fx :as fx]
            [xi.naming :as naming]
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
   an unconfigured pushover) are dropped by ext/compose. `ask!` (the dialog
   ask! from ext/create-dialogs) is threaded into extensions that raise their
   own confirm dialogs from effects (worktree removal); nil in the client
   mirror, where those effects never run."
  [ring & [ask!]]
  [ext.plan-mode/extension
   ext.done-notify/extension
   (ext.pushover/extension)
   ext.diff/extension
   (ext.worktree/create ask!)
   ext.kb/extension
   ext.web/extension
   ext.perplexity/extension
   ext.commit/extension
   ext.clj-surgeon/extension
   ext.github/extension
   ext.github-code-search/extension
   ext.gtd/extension
   ext.permission-gate/extension
   ext.todo-intercept/extension
   ext.terminal-title/extension
   ext.clipboard-image/extension
   ext.process-manager/extension
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

(def ^:private DEFAULT_MODEL "claude-opus-4-8")



(defn- parse-args [args]
  (loop [args (seq args) opts {:command :standalone}]
    (if-not args
      opts
      (let [arg (first args)]
        (case arg
          "server"         (recur (next args) (assoc opts :command :server))
          "join"           (recur (next args) (assoc opts :command :join))
          "create"         (recur (next args) (assoc opts :command :create))
          ("prompt" "-p")  (recur (next args) (assoc opts :command :prompt))
          "--stream"       (recur (next args) (assoc opts :stream? true))
          "--headless"     (recur (next args) (assoc opts :headless? true))
          "--personal-agent-only" (recur (next args) (assoc opts :personal-agent? true))
          "--debug-events" (recur (next args) (assoc opts :debug-events? true))
          "--model"        (recur (nnext args) (assoc opts :model (second args)))
          "--port"         (recur (nnext args) (assoc opts :port (js/parseInt (second args) 10)))
          (recur (next args)
                 (cond
                   ;; Positional URL for join/create
                   (and (#{:join :create} (:command opts))
                        (not (.startsWith arg "--")))
                   (assoc opts :url (if (.startsWith arg "ws") arg (str "ws://" arg)))
                   ;; Positional prompt text for prompt mode
                   (and (= :prompt (:command opts))
                        (not (.startsWith arg "--")))
                   (update opts :prompt-parts (fnil conj []) arg)
                   :else opts)))))))

(defn- resolve-model-opts [{:keys [model]}]
  {:model  (or model
               (aget js/process.env "XI_MODEL")
               DEFAULT_MODEL)
   :effort (or (aget js/process.env "XI_EFFORT")
               "high")})

(defn- make-handlers
  "Base pure handler map shared by every mode. extra-commands are extension
   commands that join the built-ins for dispatch + /help."
  ([] (make-handlers nil))
  ([extra-commands]
   (-> (merge events/core-handlers
              agent/handlers
              (commands/command-handlers extra-commands)
              compaction/handlers
              naming/handlers)
       ;; Persist the session once the provider reports a session id
       (assoc :agent/turn-end (events/chain (:agent/turn-end agent/handlers)
                                            commands/turn-end-session-sync)
              ;; Escape also stops an in-flight compaction
              :agent/abort (events/chain (:agent/abort agent/handlers)
                                         compaction/abort-handler)
              ;; First message of an unnamed session → kick off auto-titling
              :prompt/submit (events/chain (:prompt/submit agent/handlers)
                                           naming/maybe-generate-title)))))

;; ── Standalone (phase 4, unchanged) ──────────────────────────────────────────

(defn- start-standalone! [{:keys [debug-events?] :as opts}]
  (let [{:keys [model effort]} (resolve-model-opts opts)
        cwd (or (aget js/process.env "XI_CWD") (.cwd js/process))
        ring (log/create-ring)
        ;; Standalone runs everything locally — server + client extensions.
        dialogs  (ext/create-dialogs)
        composed (ext/compose (into (server-extensions ring (:ask! dialogs)) (client-extensions)))
        agents-files (system-prompt/find-agents-md cwd)
        system-parts (into (system-prompt/load-agents-parts cwd)
                           (ext/system-prompt-parts composed cwd))
        system (system-prompt/parts->system system-parts)
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
                                               (fx/create-fx ring
                                                 {:system-prompt-fn
                                                  (fn [cwd]
                                                    (let [parts (into (system-prompt/load-agents-parts cwd)
                                                                      (ext/system-prompt-parts composed cwd))]
                                                      {:system       (system-prompt/parts->system parts)
                                                       :system-parts parts}))})
                                               (compaction/create-fx providers)
                                               (naming/create-fx providers
                                               {:make-config-dir!   session/make-throwaway-config-dir!
                                                :remove-config-dir! session/remove-config-dir!})
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
                       :system-parts system-parts
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

;; ── Prompt (one-shot, headless) ──────────────────────────────────────────────

(defn- read-stdin
  "Resolve to the trimmed contents of stdin. Only call when stdin is piped
   (not a TTY) — a TTY would block forever."
  []
  (js/Promise.
   (fn [resolve _reject]
     (let [chunks #js []]
       (doto js/process.stdin
         (.on "data" (fn [c] (.push chunks (.toString c))))
         (.on "end" (fn [] (resolve (.trim (.join chunks ""))))))
       (.resume js/process.stdin)))))

(defn- start-prompt!
  "Run a single prompt with no TUI and exit. The assistant's text response is
   streamed to stdout as it arrives (--stream) or buffered and printed once the
   turn ends. Dialogs (permission confirms, cwd recovery) resolve to their safe
   defaults since no client is attached. Exits 0 on success, 1 on error."
  [{:keys [prompt-text stream?] :as opts}]
  (let [{:keys [model effort]} (resolve-model-opts opts)
        cwd (or (aget js/process.env "XI_CWD") (.cwd js/process))
        ring (log/create-ring)
        ;; Drop terminal-title: it writes raw ANSI escapes to stdout, which
        ;; would corrupt the one-shot response.
        dialogs  (ext/create-dialogs)
        composed (ext/compose (remove #(= :terminal-title (:id %))
                                      (server-extensions ring (:ask! dialogs))))
        agents-files (system-prompt/find-agents-md cwd)
        system-parts (into (system-prompt/load-agents-parts cwd)
                           (ext/system-prompt-parts composed cwd))
        system (system-prompt/parts->system system-parts)
        sess (session/create-session cwd)
        acc  #js {:out "" :error nil}
        finish!
        (fn []
          (ext/on-shutdown! composed)
          (let [code (if (.-error acc) 1 0)]
            (when (.-error acc)
              (.write js/process.stderr (str (.-error acc) "\n")))
            (let [tail (if stream? "\n" (str (.-out acc) "\n"))]
              (.write js/process.stdout tail
                      (fn [] (js/process.exit code))))))
        handlers (-> (make-handlers (:commands composed))
                     ;; One-shot: skip auto-titling (naming chain) on submit.
                     (assoc :prompt/submit (:prompt/submit agent/handlers))
                     (ext/merge-handlers composed)
                     (merge (:handlers dialogs)))
        {:keys [dispatch! add-tap!]}
        (app/create-app {:initial-state (state/initial-state
                                         ;; :server mode → create-dialogs' ask!
                                         ;; auto-resolves (no clients attached).
                                         {:mode :server
                                          :ext (:process-ext-init composed)})
                         :handlers      handlers
                         :transform-event (ext/transform-event composed)
                         :effects       (merge (agent/create-fx
                                                providers
                                                (tooling-opts composed (:ask! dialogs)))
                                               (fx/create-fx ring
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
    (add-tap!
     (fn [event _state]
       (case (:type event)
         :agent/text-delta
         (let [t (:text event)]
           (set! (.-out acc) (str (.-out acc) t))
           (when stream? (.write js/process.stdout t)))

         :agent/error
         (set! (.-error acc) (or (get-in event [:error :message])
                                 (str (:error event))))

         :agent/turn-end (finish!)
         nil)))
    (dispatch! {:type :room/create
                :room-id "main"
                :room {:model model
                       :cwd cwd
                       :effort effort
                       :system system
                       :system-parts system-parts
                       :agents-files agents-files
                       :ext (:room-ext-init composed)
                       :session sess}})
    (dispatch! {:type :prompt/submit :room-id "main" :text prompt-text})))

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
  (let [server-opts (resolve-model-opts opts)
        ring (log/create-ring)
        dialogs  (ext/create-dialogs)
        composed (ext/compose (server-extensions ring (:ask! dialogs)))
        server (ws/create-server
                {:server-opts server-opts
                 :personal-agent? personal-agent?
                 :ext-system-prompt-parts (fn [cwd] (ext/system-prompt-parts composed cwd))
                 :room-ext-init (:room-ext-init composed)
                 :ext composed})
        handlers (-> (make-handlers (:commands composed))
                     (ext/merge-handlers composed)
                     (merge (:handlers dialogs))
                     (merge rm/handlers)
                     ;; Auto-destroy: turn finished with nobody attached /
                     ;; last client dropped while idle. Reaping on :room/attach
                     ;; catches the room a client just left by switching chats
                     ;; (a re-attach sends no :room/leave).
                     (update :room/attach events/chain rm/reap-idle-clientless-rooms)
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
                                              (fx/create-fx ring
                                                {:system-prompt-fn
                                                 (fn [cwd]
                                                   (let [parts (into (system-prompt/load-agents-parts cwd)
                                                                     (ext/system-prompt-parts composed cwd))]
                                                     {:system       (system-prompt/parts->system parts)
                                                      :system-parts parts}))})
                                              (compaction/create-fx providers)
                                              (naming/create-fx providers
                                               {:make-config-dir!   session/make-throwaway-config-dir!
                                                :remove-config-dir! session/remove-config-dir!})
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

(defn- run-prompt!
  "Resolve the prompt text (positional args, else piped stdin) and run it."
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
      :else (do (js/console.error "usage: xi prompt [--stream] <text>   (or pipe text via stdin)")
                (js/process.exit 1)))))

(defn main [& args]
  (let [{:keys [command] :as opts} (parse-args args)]
    (case command
      :standalone (start-standalone! opts)
      :server     (start-server! opts)
      :prompt     (run-prompt! opts)
      :join       (start-client! (assoc opts :target "latest"))
      :create     (start-client! (assoc opts :target "new")))))
