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
  [st {:keys [client-id target cwd] :as ev}]
  (let [target (or target "latest")]
    (if (map? target)
      ;; Map target {:session-id sid} — check for a live room first (e.g.
      ;; mobile reconnects) before creating a new one.
      (if-let [existing (room-for-session st (:session-id target))]
        {:effects [[:app/dispatch {:type :room/attach
                                   :client-id client-id
                                   :room-id existing}]]}
        {:effects [[:room/setup {:client-id  client-id
                                 :room-id    (gen-room-id ev)
                                 :session-id (:session-id target)}]]})
      (let [existing (cond
                       (= "new" target)    nil
                       (= "latest" target) (latest-room-id st)
                       :else               (when (state/get-room st target) target))]
        (if existing
          {:effects [[:app/dispatch {:type :room/attach
                                     :client-id client-id
                                     :room-id existing}]]}
          {:effects [[:room/setup (cond-> {:client-id client-id
                                           :room-id   (gen-room-id ev)
                                           :cwd       cwd}
                                    (:session-id ev) (assoc :session-id (:session-id ev)))]]})))))

(defn- room-attach [st {:keys [client-id room-id]}]
  (when (state/get-room st room-id)
    {:state   (assoc-in st [:connection :clients client-id :room-id] room-id)
     :effects [[:ws/send-to {:client-id client-id
                             :event {:type :room/joined
                                     :room-id room-id
                                     :room (state/get-room st room-id)}}]]}))

(defn- room-leave [st {:keys [client-id]}]
  (when-let [room-id (get-in st [:connection :clients client-id :room-id])]
    (let [st'    (update-in st [:connection :clients client-id] dissoc :room-id)
          empty? (empty? (clients-in-room st' room-id))
          idle?  (not (get-in st' [:rooms room-id :agent :busy?]))]
      (cond-> {:state   st'
               :effects [[:ws/send-to {:client-id client-id
                                       :event {:type :room/left :room-id room-id}}]]}
        (and empty? idle?)
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

(defn- projects-web-list
  "Roomless: return the list of project directories."
  [_st {:keys [client-id]}]
  {:effects [[:projects/web-list-reply {:client-id client-id}]]})

(defn- projects-web-sessions
  "Roomless: return sessions for a specific CWD."
  [_st {:keys [client-id cwd]}]
  {:effects [[:projects/web-sessions-reply {:client-id client-id :cwd cwd}]]})

(def handlers
  {:room/join              room-join
   :room/attach            room-attach
   :room/leave             room-leave
   :room/list              room-list
   :session/counts         session-counts
   :models/web-list        models-web-list
   :projects/web-list      projects-web-list
   :projects/web-sessions  projects-web-sessions})

;; ── Auto-destroy chains (pure) ───────────────────────────────────────────────

(defn client-disconnect-cleanup
  "Chain BEFORE the core :client/disconnect handler (needs the client's
   room while it's still recorded): close the room when this was its last
   client and the agent is idle."
  [st {:keys [client-id]}]
  (when-let [room-id (get-in st [:connection :clients client-id :room-id])]
    (let [others (remove #{client-id} (clients-in-room st room-id))]
      (when (and (empty? others)
                 (not (get-in st [:rooms room-id :agent :busy?])))
        {:effects [[:app/dispatch {:type :room/close :room-id room-id}]]}))))

(defn turn-end-room-cleanup
  "Chain onto :agent/turn-end: a turn just finished in a room nobody is
   attached to — close it (the session is already persisted on disk)."
  [st {:keys [room-id]}]
  (when (and (state/get-room st room-id)
             (empty? (clients-in-room st room-id)))
    {:effects [[:app/dispatch {:type :room/close :room-id room-id}]]}))
