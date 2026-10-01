(ns xi.core.state
  "App state schema + pure constructors/accessors.

   ONE state atom per process, owned by xi.core.app — everything in this
   namespace is pure. The same shape is used in every mode:

   {:connection {:id      uuid
                 :mode    :standalone | :server | :client
                 :port    int | nil   ;; server/standalone: the (would-be) WS port
                 :clients {client-id {:kind :tui|:web :visible? bool}}}
    :rooms      {room-id {:id :history :session :agent :ext :ui}}
    :active-room room-id | nil
    :ext        {}}   ;; process-local extension state, keyed by extension id

   Extension state is scoped two ways:
   - room-scoped  [:rooms rid :ext <id>] — rides in :room/joined snapshots,
                  mirrors to clients (e.g. plan-mode :enabled?)
   - process-local [:ext <id>]           — never crosses the wire
                  (e.g. the rules engine's server-session rules)

    Standalone = one local room, connected to nothing. Server hosts N rooms.
   Client mirrors remote rooms into the same shape."
  (:require [xi.util :as util]))

(defn make-room
  "A room: independent conversation with its own history, session and UI."
  ([id] (make-room id nil))
  ([id {:keys [provider model cwd session system system-parts effort agents-files
               agent-id only-tools created ext]}]
   {:id      id
    :cwd     cwd
    :created created                  ;; ms timestamp (servers resolve "latest" by it)
    :history []                       ;; event-sourced chat history (local cache)
    :session session                  ;; current session map (+ :provider-session-id)
    :agent   {:busy?           false
              :provider        (or provider (util/provider-for-model model))
              :model           model
              :system          system          ;; concatenated system prompt string
              :system-parts    (or system-parts []) ;; [{:source :text}] with attribution
              :effort          effort
              :agents-files    agents-files    ;; paths shown in launch header
              :agent-id        agent-id        ;; named agent profile (xi.agent-profile), nil = project room
              :only-tools      only-tools}     ;; the profile's tool allowlist (name set), nil = every tool
    :ext     (or ext {})              ;; room-scoped extension state, keyed by ext id
                                      ;; (rides in :room/joined snapshots → mirrors)
    :ui      {:dialogs       []       ;; pending dialogs, FIFO
              :buffers       {}       ;; buffer-id → {:title :text ...}
              :active-buffer :chat}}))

(defn initial-state
  ([] (initial-state nil))
  ([{:keys [mode connection-id ext port]}]
   {:connection (cond-> {:id      (or connection-id (random-uuid))
                         :mode    (or mode :standalone)
                         :clients {}}
                  port (assoc :port port))
    :rooms       {}
    :active-room nil
    ;; process-local extension state, keyed by extension id (seeded from
    ;; the composed :process-ext-init); never crosses the wire
    :ext         (or ext {})}))

;; ── Accessors ────────────────────────────────────────────────────────────────

(defn get-room [state room-id]
  (get-in state [:rooms room-id]))

(defn active-room [state]
  (get-room state (:active-room state)))

(defn room-ids [state]
  (vec (keys (:rooms state))))

(defn mode [state]
  (get-in state [:connection :mode]))

(defn port
  "The WS port this process serves (server) or would join (standalone), or nil."
  [state]
  (get-in state [:connection :port]))

(defn room-ext
  "Room-scoped extension state for ext-id (mirrors to clients)."
  [state room-id ext-id]
  (get-in state [:rooms room-id :ext ext-id]))

(defn process-ext
  "Process-local extension state for ext-id (never crosses the wire)."
  [state ext-id]
  (get-in state [:ext ext-id]))
