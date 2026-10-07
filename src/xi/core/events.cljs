(ns xi.core.events
  "Pure event reducer.

   An event is a plain map with a :type keyword (plus :event/id, :event/ts
   stamped by the dispatcher). A handler is a pure fn:

     (fn [state event]) → {:state state' :effects [[:fx/type payload] ...]}
                        | nil  ;; no change

   Handlers never perform side effects — effects are data, interpreted by
   xi.core.app. Unknown event types are valid: they flow to taps/log only
   (extensions and transports may listen without a state transition).

   Event naming: :room/*, :client/*, :history/*, :agent/*, :ui/*,
   :prompt/*, :render/*."
  (:require [xi.buffers :as buffers]
            [xi.core.state :as state]
            [xi.dialog :as dialog]))

;; ── Rooms ────────────────────────────────────────────────────────────────────

(defn- room-create [st {:keys [room-id room]}]
  (when-not (state/get-room st room-id)
    {:state (-> st
                (assoc-in [:rooms room-id] (state/make-room room-id room))
                (update :active-room #(or % room-id)))}))

(defn- room-switch [st {:keys [room-id]}]
  (when (state/get-room st room-id)
    {:state (assoc st :active-room room-id)}))

(defn- room-close [st {:keys [room-id]}]
  (when (state/get-room st room-id)
    (let [st' (update st :rooms dissoc room-id)]
      {:state (cond-> st'
                (= room-id (:active-room st'))
                (assoc :active-room (first (state/room-ids st'))))})))

;; ── Clients ──────────────────────────────────────────────────────────────────

(defn- client-connect [st {:keys [client-id client]}]
  {:state (assoc-in st [:connection :clients client-id]
                    (merge {:visible? true} client))})

(defn- client-disconnect [st {:keys [client-id]}]
  {:state (update-in st [:connection :clients] dissoc client-id)})

(defn- client-update
  "Per-client metadata update: visibility, and the buffer the client shows
   (`:buffer`, :chat or a buffer id — buffer presence, folded into the room's
   :members by the room manager). Connection-level: keyed by client-id, no
   room state, never broadcast."
  [st {:keys [client-id visible?] :as ev}]
  (when (get-in st [:connection :clients client-id])
    {:state (cond-> st
              (some? visible?)
              (assoc-in [:connection :clients client-id :visible?] visible?)
              (contains? ev :buffer)
              (assoc-in [:connection :clients client-id :buffer] (or (:buffer ev) :chat)))}))

;; ── Users (server-side records) ───────────────────────────────────────────────────────

(defn- blank-user [id]
  {:id id :name nil :meta {} :ui {} :ext {}})

(defn- user-loaded
  "Install a user's record (xi.users/loaded-event): the config's profile
   (:name, read-only :meta) with the stored state — :ui, and :ext, what each
   extension keeps about them. Replaces any earlier copy, so a reconnect
   picks up a config edit."
  [st {:keys [user profile ui ext]}]
  (when user
    {:state (assoc-in st [:users user]
                      {:id   user
                       :name (:name profile)
                       :meta (or (:meta profile) {})
                       :ui   (or ui {})
                       :ext  (or ext {})})}))

(defn- user-ui-set
  "One piece of a user's UI state was saved (xi.server.ws :user-state/save)."
  [st {:keys [user key value]}]
  (when (and user key)
    {:state (assoc-in (update-in st [:users user] #(or % (blank-user user)))
                      [:users user :ui key] value)}))

(defn- user-ext-set
  "Extension `ext`'s state for `user` was saved (xi.users/set-ext-state!).
   nil forgets it. This is the only way the :ext slice changes, and only
   xi.api.user / xi.users send it, so an extension handler cannot."
  [st {:keys [user ext value]}]
  (when (and user (keyword? ext))
    (let [st (update-in st [:users user] #(or % (blank-user user)))]
      {:state (if (nil? value)
                (update-in st [:users user :ext] dissoc ext)
                (assoc-in st [:users user :ext ext] value))})))

;; ── Presence (per room, mirrored) ────────────────────────────────────────────

(defn- room-presence
  "Install a room's member list: client-id → {:user :platform}. The room
   manager derives it from the connection registry on attach / leave /
   disconnect and dispatches it so every client's mirror learns who is in
   the room (the registry itself never crosses the wire)."
  [st {:keys [room-id members]}]
  (when (state/get-room st room-id)
    {:state (assoc-in st [:rooms room-id :members] (or members {}))}))

;; ── History ──────────────────────────────────────────────────────────────────

(defn- history-append [st {:keys [room-id entry]}]
  (when (state/get-room st room-id)
    {:state (update-in st [:rooms room-id :history] conj entry)}))

;; ── Agent ────────────────────────────────────────────────────────────────────

(defn- agent-busy [st {:keys [room-id busy?]}]
  (when (state/get-room st room-id)
    {:state (assoc-in st [:rooms room-id :agent :busy?] busy?)}))

(defn- agent-set-model [st {:keys [room-id model provider]}]
  (when (state/get-room st room-id)
    {:state (update-in st [:rooms room-id :agent]
                       merge (cond-> {}
                               model    (assoc :model model)
                               provider (assoc :provider provider)))}))

;; ── UI (per room) ────────────────────────────────────────────────────────────

(defn- dialog-open
  "Add the dialog to the room. A permission ask that previews a change
   (:diff, raised for the tool call named by :call) also leaves that preview
   on the gated tool-call entry, so its block still shows what was asked once
   the ask is answered (denied) or the turn is interrupted under it."
  [st {:keys [room-id dialog] :as ev}]
  (when (state/get-room st room-id)
    (let [dialog  (update dialog :id #(or % (:event/id ev)))
          history (vec (get-in st [:rooms room-id :history]))
          idx     (when (and (:diff dialog) (:call dialog))
                    (dialog/permission-tool-index dialog history 0))]
      {:state (cond-> (update-in st [:rooms room-id :ui :dialogs] conj dialog)
                idx (assoc-in [:rooms room-id :history idx :diff] (:diff dialog)))})))

(defn- dialog-close [st {:keys [room-id dialog-id]}]
  (when (state/get-room st room-id)
    {:state (update-in st [:rooms room-id :ui :dialogs]
                       (fn [ds]
                         (if dialog-id
                           (vec (remove #(= dialog-id (:id %)) ds))
                           (vec (rest ds)))))}))

(defn- buffer-set
  "Install (or refresh) a room buffer — see xi.buffers for the shape and ids.
   The buffer list is shared room state; this never changes what any client
   is looking at (:ui/buffer-switch does)."
  [st {:keys [room-id buffer-id buffer] :as ev}]
  (when (state/get-room st room-id)
    {:state (update-in st [:rooms room-id] buffers/install buffer-id buffer (:event/ts ev))}))

(defn- buffer-close [st {:keys [room-id buffer-id]}]
  (when (get-in st [:rooms room-id :ui :buffers buffer-id])
    {:state (update-in st [:rooms room-id] buffers/close buffer-id)}))

(defn- buffers-close-all [st {:keys [room-id]}]
  (when (seq (get-in st [:rooms room-id :ui :buffers]))
    {:state (update-in st [:rooms room-id] buffers/close-all)}))

(defn- buffer-switch
  "Show a buffer (or :chat). The view is per client: a switch a server-side
   flow dispatched for one client (its :client-id — a /diff reply, a file
   read) is applied only there (xi.buffers/switch-here?); a client's own
   switch never reaches the server (xi.client.ws-transport local-ui-events)."
  [st {:keys [room-id buffer-id] :as ev}]
  (when (and (state/get-room st room-id)
             (buffers/switch-here? st ev))
    {:state (assoc-in st [:rooms room-id :ui :active-buffer] buffer-id)}))

;; ── Theme (per process) ──────────────────────────────────────────────────────

(defn- theme-set
  "Set the light/dark mode the TUI paints tool/code blocks with. `:mode` is
   `:light`, `:dark` or nil (back to the default). Process-level, so any
   extension can follow an OS or terminal theme switch without a core hook."
  [st {:keys [mode]}]
  {:state (if (#{:light :dark} mode)
            (assoc-in st [:theme :mode] mode)
            (update st :theme dissoc :mode))})

;; ── Registry ─────────────────────────────────────────────────────────────────

(def core-handlers
  {:room/create       room-create
   :room/switch       room-switch
   :room/close        room-close
   :client/connect    client-connect
   :client/disconnect client-disconnect
   :client/update     client-update
   :room/presence     room-presence
   :user/loaded       user-loaded
   :user/ui-set       user-ui-set
   :user/ext-set      user-ext-set
   :history/append    history-append
   :agent/busy        agent-busy
   :agent/set-model   agent-set-model
   :ui/dialog-open    dialog-open
   :ui/dialog-close   dialog-close
   :ui/buffer-set     buffer-set
   :ui/buffer-switch  buffer-switch
   :ui/buffer-close   buffer-close
   :ui/buffers-close-all buffers-close-all
   :theme/set         theme-set})

(defn chain
  "Compose handlers left→right into one. Each handler sees the state
   accumulated so far; effects concatenate. nil results are skipped."
  [& handlers]
  (fn [st event]
    (reduce (fn [acc handler]
              (if-let [result (handler (:state acc) event)]
                {:state   (or (:state result) (:state acc))
                 :effects (into (:effects acc) (:effects result))}
                acc))
            {:state st :effects []}
            handlers)))

(defn handle-event
  "Apply one event to state via the handler registry. Pure.
   Returns {:state state' :effects [...]} — state unchanged when no handler
   matches or the handler returns nil."
  [handlers st event]
  (if-let [handler (get handlers (:type event))]
    (let [result (handler st event)]
      {:state   (or (:state result) st)
       :effects (vec (:effects result))})
    {:state st :effects []}))
