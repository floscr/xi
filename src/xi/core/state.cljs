(ns xi.core.state
  "App state schema + pure constructors/accessors.

   ONE state atom per process, owned by xi.core.app — everything in this
   namespace is pure. The same shape is used in every mode:

   {:connection {:id      uuid
                 :mode    :standalone | :server | :client
                 :clients {client-id {:kind :tui|:web :visible? bool}}}
    :rooms      {room-id {:id :history :session :agent :ui}}
    :active-room room-id | nil
    :ext        {}}   ;; extension-owned state, keyed by extension id

   Standalone = one local room, connected to nothing. Server hosts N rooms.
   Client mirrors remote rooms into the same shape.")

(defn make-room
  "A room: independent conversation with its own history, session and UI."
  ([id] (make-room id nil))
  ([id {:keys [provider model cwd]}]
   {:id      id
    :cwd     cwd
    :history []                       ;; event-sourced chat history (local cache)
    :session nil                      ;; current session ref (set on resume/save)
    :agent   {:busy?    false
              :provider (or provider :claude)
              :model    model}
    :ui      {:dialogs       []       ;; pending dialogs, FIFO
              :buffers       {}       ;; buffer-id → {:title :lines ...}
              :active-buffer :chat}}))

(defn initial-state
  ([] (initial-state nil))
  ([{:keys [mode connection-id]}]
   {:connection {:id      (or connection-id (random-uuid))
                 :mode    (or mode :standalone)
                 :clients {}}
    :rooms       {}
    :active-room nil
    :ext         {}}))

;; ── Accessors ────────────────────────────────────────────────────────────────

(defn get-room [state room-id]
  (get-in state [:rooms room-id]))

(defn active-room [state]
  (get-room state (:active-room state)))

(defn room-ids [state]
  (vec (keys (:rooms state))))

(defn mode [state]
  (get-in state [:connection :mode]))
