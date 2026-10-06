(ns xi.core.state
  "App state schema + pure constructors/accessors.

   ONE state atom per process, owned by xi.core.app — everything in this
   namespace is pure. The same shape is used in every mode:

   {:connection {:id      uuid
                 :mode    :standalone | :server | :client
                 :port    int | nil   ;; server/standalone: the (would-be) WS port
                 :user    \"root\"      ;; this process' own user id (xi.util/user-id)
                 :clients {client-id {:kind :tui|:web :visible? bool :user \"alice\"}}}
    :rooms      {room-id {:id :history :session :agent :members :ext :ui}}
    :users      {user-id {:id :name :meta :ui :ext}}  ;; server-side, never on the wire
    :active-room room-id | nil
    :ext        {}}   ;; process-local extension state, keyed by extension id

   Extension state is scoped two ways:
   - room-scoped  [:rooms rid :ext <id>] — rides in :room/joined snapshots,
                  mirrors to clients (e.g. plan-mode :enabled?)
   - process-local [:ext <id>]           — never crosses the wire
                  (e.g. the rules engine's server-session rules)

    Standalone = one local room, connected to nothing. Server hosts N rooms.
   Client mirrors remote rooms into the same shape.

   Users: every connection belongs to a user (a string id, \"root\" by
   default). The server records it per client in [:connection :clients cid
   :user] and stamps :user on every event a client sends; a room's
   :members (room-scoped, mirrored) lists who is attached; :user history
   entries carry the sender. Roles and authentication are extension
   territory, keyed by the id.

   `:users` is the record of each user the process has loaded (xi.users):
   :name and :meta from the config file (read-only), :ui the user's UI state
   (theme, …) and :ext what each extension keeps about them, {ext-id data},
   persisted per user (xi.user-state.store). Extensions read it from state;
   they change their entry only through xi.api.user."
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
    :members {}                       ;; presence: client-id → {:user :platform} (mirrored,
                                      ;; maintained by the room manager via :room/presence)
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
  ([{:keys [mode connection-id ext port clientless? user]}]
   {:connection (cond-> {:id      (or connection-id (random-uuid))
                         :mode    (or mode :standalone)
                         ;; this process' own user: the person at a standalone
                         ;; TUI, the operator of a server (HTTP-started and
                         ;; other clientless prompts are theirs); a client
                         ;; learns its resolved user from :auth/ok
                         :user    (util/user-id user)
                         :clients {}}
                  port (assoc :port port)
                  ;; no client can ever attach (prompt mode): dialogs
                  ;; resolve to their safe defaults (xi.ext.core/create-dialogs)
                  clientless? (assoc :clientless? true))
    :rooms       {}
    ;; who the process knows: loaded at connect (xi.users), server-side only
    :users       {}
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

(defn own-user
  "This process' user id (see initial-state)."
  [state]
  (get-in state [:connection :user] util/root-user))

(defn room-users
  "Distinct user ids attached to a room, in attach order."
  [room]
  (vec (distinct (map :user (vals (:members room))))))

(defn event-user
  "The user an event acts for: the :user the server stamped on a client's
   event. An event with none — server-side automation such as a slash command
   that submits a prompt — acts for the one user in its room when there is
   exactly one, else for this process' own user (standalone input, the HTTP
   API, a queued prompt drain in a shared room)."
  [state event]
  (or (:user event)
      (let [users (room-users (get-in state [:rooms (:room-id event)]))]
        (when (= 1 (count users)) (first users)))
      (own-user state)))

(defn turn-user
  "The user a room's current turn acts for: whoever sent the latest prompt,
   else this process' own user. What a tool call or sub-agent in that turn
   does it does on behalf of this user."
  [state room-id]
  (let [history (get-in state [:rooms room-id :history])]
    (or (when (vector? history)
          (some #(when (= :user (:kind %)) (:user %)) (rseq history)))
        (own-user state))))

(defn user-record
  "The loaded record of user `user-id`: {:id :name :meta :ui :ext}, or nil."
  [state user-id]
  (get-in state [:users user-id]))

(defn user-ext
  "What extension `ext-id` keeps about user `user-id` (nil when nothing)."
  [state user-id ext-id]
  (get-in state [:users user-id :ext ext-id]))

(defn room-ext
  "Room-scoped extension state for ext-id (mirrors to clients)."
  [state room-id ext-id]
  (get-in state [:rooms room-id :ext ext-id]))

(defn process-ext
  "Process-local extension state for ext-id (never crosses the wire)."
  [state ext-id]
  (get-in state [:ext ext-id]))

(defn drop-provider-session
  "Detach a room's session from its live provider (Claude CLI) session so the
   next turn starts a fresh one, remembering the id it replaces under
   :superseded-cli-session-ids. Listings dedup raw Claude transcripts against
   the ids an Xi session claims (xi.session/claimed-cli-ids); without this
   record a fork (retry / edit / tree navigate) or a dead-session retry left
   the previous transcript orphaned — a duplicate card for the same
   conversation in the lobby."
  [session]
  (let [old (or (:provider-session-id session) (:cli-session-id session))]
    (cond-> (assoc session :provider-session-id nil)
      old (update :superseded-cli-session-ids
                  (fn [ids] (vec (distinct (conj (or ids []) old))))))))
