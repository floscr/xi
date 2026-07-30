(ns xi.server.room-manager
  "Rooms as events — join/attach/leave/list plus the auto-destroy policy.

   Everything here is pure and operates on the same app state shape as the
   rest of the system: rooms live in :rooms, remote clients in
   [:connection :clients] where a :room-id key marks membership. The impure
   edges (sockets, session/system-prompt provisioning) live in xi.server.ws.

   Lifecycle:
     :room/join   {:client-id :target \"new\"|\"latest\"|room-id :cwd}
                  ─► existing room → :room/attach (via :app/dispatch)
                  ─► otherwise [:room/setup …] (fx provisions session +
                     system prompt, then dispatches :room/create and
                     :room/attach)
     :room/attach ─► marks membership + sends the :room/joined snapshot
     :room/leave  ─► clears membership, confirms with :room/left
     :room/list   ─► sends :lobby/state to the requesting client

   Auto-destroy (vs master: no personal-agent special case — the generic
   policy subsumes it): a room is closed when its last client leaves or
   disconnects while the agent is idle, or when a turn ends with no
   clients attached."
  (:require [xi.core.state :as state]))

;; ── Queries (pure) ───────────────────────────────────────────────────────────

(defn clients-in-room
  "Client-ids attached to room-id."
  [st room-id]
  (into []
        (keep (fn [[cid client]]
                (when (= room-id (:room-id client)) cid)))
        (get-in st [:connection :clients])))

(defn keep-alive?
  "A room must survive client departure / idle reaping while its agent is
   running OR a dialog is awaiting a response. Closing a room discards its
   :ui :dialogs, which would strand the pending question and the turn
   suspended on it (a dialog stays open even with no client viewing — see
   ext/create-dialogs).

   It must also survive while the process-manager extension is tracking
   live background processes: closing the room fires :room/close, which
   kills them (ext/process-manager on-room-close). We only auto-close a
   room the user has walked away from — not one running their dev server."
  [room]
  (or (boolean (get-in room [:agent :busy?]))
      (boolean (seq (get-in room [:ui :dialogs])))
      (boolean (seq (get-in room [:ext :process-manager :processes])))))

(defn prunable?
  "A room is prunable (\"inactive\") when its agent isn't mid-turn and it holds
   no pending dialog — nobody is actively working in or waiting on it. Unlike
   keep-alive? this deliberately IGNORES live background processes: a manual
   prune is meant to tear those down (and detach any lingering client) to clear
   rooms left behind by open terminals."
  [room]
  (and (not (get-in room [:agent :busy?]))
       (empty? (get-in room [:ui :dialogs]))))

(defn room-summaries
  "Lobby-facing room list, newest first."
  [st]
  (->> (vals (:rooms st))
       (map (fn [room]
              {:id           (:id room)
               :clients      (count (clients-in-room st (:id room)))
               :created      (:created room)
               :session-id   (get-in room [:session :id])
               :session-name (get-in room [:session :name])
               :cwd          (:cwd room)
               :busy?        (boolean (get-in room [:agent :busy?]))
               :has-dialog?  (boolean (seq (get-in room [:ui :dialogs])))}))
       (sort-by :created)
       reverse
       vec))

(defn- latest-room-id [st]
  (->> (vals (:rooms st)) (sort-by :created) last :id))

(defn- gen-room-id
  "Room ids derive from the event stamp — unique per process, pure."
  [{:keys [event/ts event/id]}]
  (str "r-" (.toString (or ts 0) 36) "-" (or id 0)))

;; ── Handlers (pure) ──────────────────────────────────────────────────────────

(defn- room-for-session
  "Find an existing room hosting this session-id, if any."
  [st session-id]
  (some (fn [[_ room]]
          (when (= session-id (get-in room [:session :id]))
            (:id room)))
        (:rooms st)))

(defn- room-join
  "Resolve a join target to an existing room (→ attach) or a new one
   (→ :room/setup effect, which creates then attaches).

   Targets: \"new\" | \"latest\" | room-id | {:session-id sid} (resume a
   saved session into a fresh room)."
  [st {:keys [client-id target cwd cached-msg-hash] :as ev}]
  (let [target     (or target "latest")
        ;; A session-id may ride on the event (web navigation always carries
        ;; it) or inside a {:session-id …} target (mobile reconnect).
        session-id (or (:session-id ev)
                       (when (map? target) (:session-id target)))
        ;; Always prefer a live room hosting this session over resuming a
        ;; fresh copy from disk. Critical while the agent is mid-turn: the
        ;; disk snapshot lags the running room, so resuming it would show an
        ;; older state (and split new prompts into a second room). A
        ;; stale/renamed room-id in the client's lobby must never cause a disk
        ;; resume when a live room for the session still exists.
        live       (when session-id (room-for-session st session-id))]
    (cond
      live
      {:effects [[:app/dispatch {:type :room/attach
                                 :client-id client-id
                                 :room-id live}]]}

      (map? target)
      ;; Map target {:session-id sid} with no live room — resume from disk.
      {:effects [[:room/setup {:client-id       client-id
                               :room-id         (gen-room-id ev)
                               :session-id      (:session-id target)
                               :cached-msg-hash cached-msg-hash}]]}

      :else
      (let [existing (cond
                       (= "new" target)    nil
                       (= "latest" target) (latest-room-id st)
                       :else               (when (state/get-room st target) target))]
        (if existing
          {:effects [[:app/dispatch {:type :room/attach
                                     :client-id client-id
                                     :room-id existing}]]}
          {:effects [[:room/setup (cond-> {:client-id       client-id
                                           :room-id         (gen-room-id ev)
                                           :cwd             cwd
                                           :cached-msg-hash cached-msg-hash}
                                    (:session-id ev) (assoc :session-id (:session-id ev)))]]})))))

(defn- room-attach [st {:keys [client-id room-id]}]
  (when (state/get-room st room-id)
    {:state   (assoc-in st [:connection :clients client-id :room-id] room-id)
     :effects [[:ws/send-to {:client-id client-id
                             :event {:type :room/joined
                                     :room-id room-id
                                     :room (state/get-room st room-id)}}]]}))

;; ── Client departure / cleanup ───────────────────────────────────────────────
;; RECURRING PITFALL: never abort a BUSY room when its last client leaves or
;; disconnects. A disconnect is indistinguishable from "user switched chats"
;; (iOS Safari drops the socket on navigation), so any abort-on-disconnect —
;; even behind a grace period — eventually kills a live agent. Busy orphaned
;; rooms keep running; turn-end-room-cleanup reaps them. Only IDLE clientless
;; rooms are closed here. See docs/server.md "Busy rooms keep running".

(defn- room-leave [st {:keys [client-id]}]
  (when-let [room-id (get-in st [:connection :clients client-id :room-id])]
    (let [st'    (update-in st [:connection :clients client-id] dissoc :room-id)
          empty? (empty? (clients-in-room st' room-id))
          keep?  (keep-alive? (get-in st' [:rooms room-id]))]
      (cond-> {:state   st'
               :effects [[:ws/send-to {:client-id client-id
                                       :event {:type :room/left :room-id room-id}}]]}
        ;; Last client navigated away from an idle room — close it now.
        ;; Busy rooms (and rooms with a pending dialog) keep running with no
        ;; client attached (background agents are the point of a headless
        ;; server); turn-end-room-cleanup reaps them once the turn ends.
        (and empty? (not keep?))
        (update :effects conj [:app/dispatch {:type :room/close :room-id room-id}])))))

(defn- room-list [_st {:keys [client-id]}]
  ;; The full payload (rooms + saved sessions) is built impurely in the WS
  ;; layer, which can read sessions from disk.
  {:effects [[:lobby/send {:client-id client-id}]]})

(defn- session-counts
  "Unread-count query: assistant-turn counts per saved session. Reading
   sessions hits disk, so the pure handler just emits an effect the WS
   layer fulfils."
  [_st {:keys [client-id session-ids]}]
  {:effects [[:session/counts-reply {:client-id client-id
                                     :session-ids session-ids}]]})

(defn- models-web-list
  [_st {:keys [client-id]}]
  {:effects [[:models/web-list-reply {:client-id client-id}]]})

(defn- session-content-search
  "Roomless: search saved-session names + conversation text for a query.
   `cwd` scopes to a project; nil searches all sessions."
  [_st {:keys [client-id key query cwd]}]
  {:effects [[:session/content-search-reply
              {:client-id client-id :key key :query query :cwd cwd}]]})

(defn- diff-web-load
  "Roomless: return the combined working-tree diff for a CWD (the git-status
   view, which has no room to attach a :diff buffer to)."
  [_st {:keys [client-id cwd]}]
  {:effects [[:diff/web-load-reply {:client-id client-id :cwd cwd}]]})

(defn- commits-web-load
  "Roomless: list the commits made during a session (base..HEAD) for the web
   commit bar. The cwd + the session's created timestamp travel from the
   client's mirrored room, since the request carries no room-id."
  [_st {:keys [client-id cwd created]}]
  {:effects [[:commits/web-load-reply {:client-id client-id :cwd cwd :created created}]]})

(defn- favorites-toggle
  "Roomless: star/unstar a session by id. The write + lobby rebroadcast happen
   in the :favorites/toggle-reply effect (needs disk access)."
  [_st {:keys [session-id]}]
  {:effects [[:favorites/toggle-reply {:session-id session-id}]]})

(defn- session-mark-read
  "Roomless: record a session as seen up to its current response count. The
   authoritative count is recomputed server-side (in the reply effect), so the
   client only needs to name the session. The persist + lobby rebroadcast
   happen in the :session/mark-read-reply effect (needs disk access)."
  [_st {:keys [session-id]}]
  {:effects [[:session/mark-read-reply {:session-id session-id}]]})

(defn- rooms-prune
  "Roomless: force-close every inactive room (see prunable?). For each target
   we detach its clients (a direct :room/left drops each back to the lobby),
   then :room/close it — which kills the room's tracked processes
   (ext/process-manager on-room-close) and discards its in-memory history. The
   caller's own room is spared, so pruning from a chat can't close the chat
   you're looking at.

   Saved sessions are untouched: :room/close only tears down the live room; the
   on-disk session stays resumable. Issued from the web sidebar to clear rooms
   left hanging around by open terminals."
  [st {:keys [client-id]}]
  (let [own-room (get-in st [:connection :clients client-id :room-id])
        targets  (into #{}
                       (keep (fn [[rid room]]
                               (when (and (not= rid own-room) (prunable? room))
                                 rid)))
                       (:rooms st))
        attached (for [[cid client] (get-in st [:connection :clients])
                       :let  [rid (:room-id client)]
                       :when (contains? targets rid)]
                   [cid rid])]
    (when (seq targets)
      {:state   (reduce (fn [s [cid _]]
                          (update-in s [:connection :clients cid] dissoc :room-id))
                        st attached)
       :effects (-> []
                    (into (map (fn [[cid rid]]
                                 [:ws/send-to {:client-id cid
                                               :event {:type :room/left :room-id rid}}]))
                          attached)
                    (into (map (fn [rid] [:app/dispatch {:type :room/close :room-id rid}]))
                          targets))})))


(def handlers
  {:room/join              room-join
   :room/attach            room-attach
   :room/leave             room-leave
   :room/list              room-list
   :session/counts         session-counts
   :models/web-list        models-web-list
   :session/content-search session-content-search
   :diff/web-load          diff-web-load
   :commits/web-load       commits-web-load
   :favorites/toggle       favorites-toggle
   :session/mark-read      session-mark-read
   :rooms/prune            rooms-prune})

;; ── Auto-destroy chains (pure) ───────────────────────────────────────────────

(defn client-disconnect-cleanup
  "Chain BEFORE the core :client/disconnect handler (needs the client's
   room while it's still recorded): close the room when this was its last
   client AND it's idle. Busy rooms (and rooms with a pending dialog) keep
   running with no client attached — a disconnect (including iOS/Safari
   dropping the socket on navigation) must never abort a running agent or
   strand a pending question; turn-end-room-cleanup reaps the room when the
   turn ends."
  [st {:keys [client-id]}]
  (when-let [room-id (get-in st [:connection :clients client-id :room-id])]
    (let [others (remove #{client-id} (clients-in-room st room-id))]
      (when (and (empty? others)
                 (not (keep-alive? (get-in st [:rooms room-id]))))
        {:effects [[:app/dispatch {:type :room/close :room-id room-id}]]}))))


(defn turn-end-room-cleanup
  "Chain onto :agent/turn-end: a turn just finished in a room nobody is
   attached to — close it (the session is already persisted on disk). A room
   still holding a pending dialog is spared (its question outlives the turn)."
  [st {:keys [room-id]}]
  (when (and (state/get-room st room-id)
             (empty? (clients-in-room st room-id))
             (not (keep-alive? (state/get-room st room-id))))
    {:effects [[:app/dispatch {:type :room/close :room-id room-id}]]}))

(defn reap-idle-clientless-rooms
  "Close every room whose agent isn't running and which has no client
   attached. The per-event cleanups (room-leave, client-disconnect-cleanup,
   turn-end-room-cleanup) miss one case: a client switching directly between
   rooms only re-attaches (:room/attach), so the room it left is never sent a
   :room/leave and lingers idle + clientless. Chained onto :room/attach this
   sweeps those orphans. Busy rooms and rooms with a pending dialog are
   always spared — turn-end-room-cleanup reaps them once the turn ends (see
   the pitfall note above)."
  [st _ev]
  (let [closes (for [[room-id room] (:rooms st)
                     :when (and (empty? (clients-in-room st room-id))
                                (not (keep-alive? room)))]
                 [:app/dispatch {:type :room/close :room-id room-id}])]
    (when (seq closes)
      {:effects (vec closes)})))