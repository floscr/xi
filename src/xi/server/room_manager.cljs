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
  (:require [xi.buffers :as buffers]
            [xi.core.state :as state]
            [xi.ext.subagent.handlers :as sa]
            [xi.user-state :as user-state]
            [xi.util :as util]))

;; ── Queries (pure) ───────────────────────────────────────────────────────────

(defn clients-in-room
  [st room-id]
  (into []
        (keep (fn [[cid client]]
                (when (= room-id (:room-id client)) cid)))
        (get-in st [:connection :clients])))

(defn room-members
  "Presence for room-id from the connection registry: client-id → {:user
   :platform :buffer}. Stored on the room (:members) and broadcast as
   :room/presence, since the registry never crosses the wire."
  [st room-id]
  (into {}
        (keep (fn [[cid client]]
                (when (= room-id (:room-id client))
                  [cid (cond-> {:user (or (:user client) util/root-user)}
                         (:platform client) (assoc :platform (:platform client))
                         (:buffer client)   (assoc :buffer (:buffer client)))])))
        (get-in st [:connection :clients])))

(defn- presence-effect
  "Broadcast room-id's current members to its clients (xi.core.events
   installs them on every mirror)."
  [st room-id]
  [:app/dispatch {:type :room/presence :room-id room-id
                  :members (room-members st room-id)}])

(defn keep-alive?
  "A room must survive client departure and idle reaping while its agent is
   running, a dialog awaits a response, the process-manager extension tracks
   live background processes, or a background sub-agent is still running."
  [room]
  (or (boolean (get-in room [:agent :busy?]))
      (boolean (seq (get-in room [:ui :dialogs])))
      (boolean (seq (get-in room [:ext :process-manager :processes])))
      (boolean (some #(= :running (:status %))
                     (get-in room [:ext :subagents :agents])))))

(defn prunable?
  "A room is prunable when its agent isn't mid-turn and no dialog is pending.
   Unlike keep-alive? this ignores background processes: a manual prune tears
   those down."
  [room]
  (and (not (get-in room [:agent :busy?]))
       (empty? (get-in room [:ui :dialogs]))))

(defn- room-errored?
  "True when the room's last turn ended in an error and nothing is running."
  [room]
  (and (not (get-in room [:agent :busy?]))
       (= :error (:kind (last (:history room))))))

(defn room-summaries
  "Lobby-facing room list, newest first; rooms whose session was deleted while
   keep-alive are dropped."
  [st]
  (->> (vals (:rooms st))
       (remove #(get-in % [:session :deleted?]))
       (map (fn [room]
              {:id           (:id room)
               :clients      (count (clients-in-room st (:id room)))
               :created      (:created room)
               :session-id   (get-in room [:session :id])
               :session-name (get-in room [:session :name])
               :cwd          (:cwd room)
               :busy?        (boolean (get-in room [:agent :busy?]))
               :has-dialog?  (boolean (seq (get-in room [:ui :dialogs])))
               :error?       (room-errored? room)
               :users        (state/room-users room)
               ;; buffer presence: buffer id (or :chat) → the users on it
               :viewers      (buffers/viewers room)}))
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
  "An existing room hosting this session id, matched on the Xi uuid and the
   Claude CLI id (a client may resume by either)."
  [st session-id]
  (some (fn [[_ room]]
          (let [sess (:session room)]
            (when (or (= session-id (:id sess))
                      (= session-id (:provider-session-id sess))
                      (= session-id (:cli-session-id sess)))
              (:id room))))
        (:rooms st)))

(defn- room-join
  "Resolve a join target to an existing room (→ attach) or a new one (→
   :room/setup). Targets: \"new\" | \"latest\" | room-id | {:session-id sid}."
  [st {:keys [client-id target cwd model cached-msg-hash cached-msg-count
              cached-history-hash cached-history-count join-token] :as ev}]
  (let [target     (or target "latest")
        ;; Attaching to a live room: forward the client's cached-history
        ;; fingerprint so room-attach can skip what it already has.
        attach     (fn [room-id]
                     [:app/dispatch
                      (cond-> {:type :room/attach :client-id client-id :room-id room-id}
                        cached-history-hash (assoc :cached-history-hash cached-history-hash
                                                   :cached-history-count cached-history-count))])
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
      {:effects [(attach live)]}

      (map? target)
      ;; Map target {:session-id sid} with no live room — resume from disk.
      {:effects [[:room/setup {:client-id        client-id
                               :room-id          (gen-room-id ev)
                               :session-id       (:session-id target)
                               :cached-msg-hash  cached-msg-hash
                               :cached-msg-count cached-msg-count}]]}

      :else
      (let [existing (cond
                       (= "new" target)    nil
                       (= "latest" target) (latest-room-id st)
                       :else               (when (state/get-room st target) target))]
        (if existing
          {:effects [(attach existing)]}
          {:effects [[:room/setup (cond-> {:client-id        client-id
                                           :room-id          (gen-room-id ev)
                                           :cwd              cwd
                                           :cached-msg-hash  cached-msg-hash
                                           :cached-msg-count cached-msg-count}
                                    model            (assoc :model model)
                                    (:session-id ev) (assoc :session-id (:session-id ev))
                                    ;; Echo the client's join-token back on
                                    ;; :room/joined so the web pending-submit
                                    ;; fires into THIS new room only.
                                    join-token       (assoc :join-token join-token))]]})))))

(defn joined-payload
  "The room part of a :room/joined event: {:room snapshot}, or, when the client
   already caches this history (or a clean prefix) as `cached-hash` over its
   first `cached-count` entries, the snapshot without :history plus
   {:history-base {:hash :count} :history-tail […]}. Hashes are cljs `hash` on
   both ends."
  [room cached-hash cached-count]
  (let [history (:history room)
        n       (count history)]
    (if (and (some? cached-hash)
             (vector? history)
             (integer? cached-count)
             (< 0 cached-count)
             (<= cached-count n)
             (= cached-hash (hash (if (= cached-count n)
                                    history
                                    (subvec history 0 cached-count)))))
      {:room         (dissoc room :history)
       :history-base {:hash cached-hash :count cached-count}
       :history-tail (subvec history cached-count)}
      {:room room})))

(defn- room-attach [st {:keys [client-id room-id join-token cached-history-hash cached-history-count]}]
  (when (state/get-room st room-id)
    (let [previous (get-in st [:connection :clients client-id :room-id])
          st'      (assoc-in st [:connection :clients client-id :room-id] room-id)
          ;; Presence rides in the snapshot (the joiner sees itself at once)
          ;; and is broadcast to everyone already in the room.
          st'      (assoc-in st' [:rooms room-id :members] (room-members st' room-id))
          room     (state/get-room st' room-id)]
      {:state   st'
       :effects (cond-> [[:ws/send-to {:client-id client-id
                                       :event (cond-> (merge {:type :room/joined :room-id room-id}
                                                             (joined-payload room cached-history-hash
                                                                             cached-history-count))
                                                join-token (assoc :join-token join-token))}]
                         (presence-effect st' room-id)]
                  ;; A client switching rooms sends no :room/leave for the
                  ;; one it left — refresh that room's presence too.
                  (and previous (not= previous room-id) (state/get-room st' previous))
                  (conj (presence-effect st' previous)))})))

;; ── Client departure / cleanup ───────────────────────────────────────────────
;; RECURRING PITFALL: never abort a BUSY room when its last client leaves or
;; disconnects. A disconnect is indistinguishable from "user switched chats"
;; (iOS Safari drops the socket on navigation), so any abort-on-disconnect —
;; even behind a grace period — eventually kills a live agent. Busy orphaned
;; rooms keep running; turn-end-room-cleanup reaps them. Only IDLE clientless
;; rooms are closed here. See docs/architecture.md "Room lifecycle and auth".

(defn- room-leave [st {:keys [client-id]}]
  (when-let [room-id (get-in st [:connection :clients client-id :room-id])]
    (let [st'    (update-in st [:connection :clients client-id] dissoc :room-id)
          empty? (empty? (clients-in-room st' room-id))
          keep?  (keep-alive? (get-in st' [:rooms room-id]))]
      {:state   st'
       :effects [[:ws/send-to {:client-id client-id
                               :event {:type :room/left :room-id room-id}}]
                 ;; Last client navigated away from an idle room — close it now.
                 ;; Busy rooms (and rooms with a pending dialog) keep running with no
                 ;; client attached (background agents are the point of a headless
                 ;; server); turn-end-room-cleanup reaps them once the turn ends.
                 ;; A room that lives on tells its remaining clients who is left.
                 (if (and empty? (not keep?))
                   [:app/dispatch {:type :room/close :room-id room-id}]
                   (presence-effect st' room-id))]})))

(defn- room-list [_st {:keys [client-id]}]
  ;; The full payload (rooms + saved sessions) is built impurely in the WS
  ;; layer, which can read sessions from disk.
  {:effects [[:lobby/send {:client-id client-id}]]})

(defn- session-counts
  "Unread-count query; reading sessions hits disk, so the handler just emits an effect."
  [_st {:keys [client-id session-ids]}]
  {:effects [[:session/counts-reply {:client-id client-id
                                     :session-ids session-ids}]]})

(defn- sessions-all
  "Roomless: the full (uncapped) saved-session list + counts, for the
   all-sessions view. The lobby broadcast only carries a capped recent list."
  [_st {:keys [client-id]}]
  {:effects [[:sessions/all-reply {:client-id client-id}]]})

(defn- cwd-agents-files
  "Roomless: the AGENTS.md files that apply to `cwd`, for the launch header of
   a virtual new chat (no room exists yet to read them from)."
  [_st {:keys [client-id cwd]}]
  (when (string? cwd)
    {:effects [[:cwd/agents-files-reply {:client-id client-id :cwd cwd}]]}))

(defn- models-web-list
  [_st {:keys [client-id]}]
  {:effects [[:models/web-list-reply {:client-id client-id}]]})

(defn- session-content-search
  "Roomless: search saved-session names + conversation text for a query.
   `cwd` scopes to a project; nil searches all sessions."
  [_st {:keys [client-id key query cwd]}]
  {:effects [[:session/content-search-reply
              {:client-id client-id :key key :query query :cwd cwd}]]})

(defn- session-web-search
  "Roomless: full-text session search for the palette, replying with full
   summaries and snippets. `names-only?` skips transcript text."
  [_st {:keys [client-id query cwd names-only?]}]
  {:effects [[:session/web-search-reply
              {:client-id client-id :query query :cwd cwd
               :names-only? names-only?}]]})

(defn- diff-web-load
  "Roomless: return the combined working-tree diff for a CWD (the git-status
   view, which has no room to attach a :diff buffer to)."
  [_st {:keys [client-id cwd]}]
  {:effects [[:diff/web-load-reply {:client-id client-id :cwd cwd}]]})

(defn- commits-web-load
  "Roomless: the commits made during a session (base..HEAD); cwd and created
   timestamp travel from the client."
  [_st {:keys [client-id cwd created]}]
  {:effects [[:commits/web-load-reply {:client-id client-id :cwd cwd :created created}]]})

(defn- files-web-list
  "Roomless: a directory's children for the web file browser; cwd is the
   fallback when no path is sent."
  [_st {:keys [client-id cwd path]}]
  {:effects [[:files/web-list-reply {:client-id client-id :cwd cwd :path path}]]})

(defn- file-web-read
  "Roomless: read one file's contents for the web file viewer (the :file tab)."
  [_st {:keys [client-id cwd path]}]
  {:effects [[:file/web-read-reply {:client-id client-id :cwd cwd :path path}]]})

(defn- files-web-tree
  "Roomless: flat list of the project's files for the web fuzzy file finder,
   relative to the room's cwd (git-tracked when available)."
  [_st {:keys [client-id cwd]}]
  {:effects [[:files/web-tree-reply {:client-id client-id :cwd cwd}]]})

(defn- dismissed-toggle
  "Roomless: hide/show a session in the sender's recent list (persisted in the
   reply effect). Also closes the session's live room when it is clientless and
   not keep-alive."
  [st {:keys [session-id] :as ev}]
  (let [close-rids (for [[rid room] (:rooms st)
                         :when (and (= session-id (get-in room [:session :id]))
                                    (not (keep-alive? room))
                                    (empty? (clients-in-room st rid)))]
                     rid)]
    {:effects (into [[:dismissed/toggle-reply {:session-id session-id
                                               :user       (state/event-user st ev)}]]
                    (map (fn [rid] [:app/dispatch {:type :room/close :room-id rid}]))
                    close-rids)}))

(defn- pinned-toggle
  "Roomless: pin/unpin a session in the sender's recent list (persisted in the reply effect)."
  [st {:keys [session-id] :as ev}]
  {:effects [[:pinned/toggle-reply {:session-id session-id
                                    :user       (state/event-user st ev)}]]})

(defn- blank-room?
  "A live room nobody has prompted: no history and a session that never got a
   provider/CLI id, i.e. nothing on disk and nothing to lose."
  [room]
  (and (empty? (:history room))
       (not (get-in room [:session :cli-session-id]))
       (not (get-in room [:session :provider-session-id]))))

(defn- session-delete
  "Roomless: permanently delete a saved session (unlinked in the reply effect)
   and tear down its live room: a viewing client's room is swapped to a fresh
   blank session, a clientless one closed, a blank room's clients detached with
   :room/left. A keep-alive room is instead flagged :deleted? (card suppressed,
   sync-persist skipped) and reaped once idle."
  [st {:keys [session-id]}]
  (let [all-rids (for [[rid room] (:rooms st)
                       :when (= session-id (get-in room [:session :id]))]
                   rid)
        {kept true live false}
        (group-by #(keep-alive? (get-in st [:rooms %])) all-rids)
        {closable false attached true}
        (group-by #(boolean (seq (clients-in-room st %))) live)
        {blank true swap false}
        (group-by #(blank-room? (get-in st [:rooms %])) attached)
        detached (for [[cid client] (get-in st [:connection :clients])
                       :let  [rid (:room-id client)]
                       :when (some #{rid} blank)]
                   [cid rid])]
    {:state (as-> st s
              (reduce (fn [s rid]
                        (assoc-in s [:rooms rid :session :deleted?] true))
                      s kept)
              (reduce (fn [s [cid _]]
                        (update-in s [:connection :clients cid] dissoc :room-id))
                      s detached))
     :effects (-> [[:session/delete-reply {:session-id session-id}]]
                  (into (map (fn [[cid rid]]
                               [:ws/send-to {:client-id cid
                                             :event {:type :room/left :room-id rid}}]))
                        detached)
                  (into (map (fn [rid] [:app/dispatch {:type :room/close :room-id rid}]))
                        (concat closable blank))
                  (into (map (fn [rid] [:session/new {:room-id rid :save-current? false}]))
                        swap))}))

(defn- session-mark-read
  "Roomless: record a session as seen by the sender; the authoritative count is
   recomputed in the reply effect."
  [st {:keys [session-id] :as ev}]
  {:effects [[:session/mark-read-reply {:session-id session-id
                                        :user       (state/event-user st ev)}]]})

(defn- rooms-prune
  "Roomless: force-close every prunable room except the caller's: detach its
   clients (:room/left), then :room/close (which kills tracked processes).
   Saved sessions stay resumable."
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


(defn- chat-start
  "Open a new chat seeded with `text` as its first user message; cwd defaults
   to the dispatching room's, :client-id is sent to the new chat."
  [st {:keys [room-id client-id cwd text]}]
  (when (and (string? text) (seq text))
    {:effects [[:chat/start (cond-> {:text text
                                     :cwd  (or cwd (get-in st [:rooms room-id :cwd]))}
                              client-id (assoc :client-id client-id))]]}))

(defn- user-state-set
  "A client changed one piece of its own user's UI state (the server stamps
   :user); the :user-state/save effect persists it. Unknown keys and invalid
   values are dropped."
  [_st {:keys [user key value]}]
  (when (user-state/client-valid? key value)
    {:effects [[:user-state/save {:user (util/user-id user) :key key :value value}]]}))


(defn- session-buffer-close
  "Roomless: close one buffer (or all without :buffer-id) of a session, in its
   live room or its parked set."
  [st {:keys [session-id buffer-id]}]
  (if-let [rid (room-for-session st session-id)]
    {:effects [[:app/dispatch (if buffer-id
                                {:type :ui/buffer-close :room-id rid :buffer-id buffer-id}
                                {:type :ui/buffers-close-all :room-id rid})]]}
    (when (contains? (:parked-buffers st) session-id)
      (let [left (when buffer-id (dissoc (get-in st [:parked-buffers session-id]) buffer-id))]
        {:state (if (seq left)
                  (assoc-in st [:parked-buffers session-id] left)
                  (update st :parked-buffers dissoc session-id))}))))

(def handlers
  {:chat/start             chat-start
   :user-state/set         user-state-set
   :room/join              room-join
   :room/attach            room-attach
   :room/leave             room-leave
   :room/list              room-list
   :session/counts         session-counts
   :sessions/all           sessions-all
   :models/web-list        models-web-list
   :cwd/agents-files       cwd-agents-files
   :session/content-search session-content-search
   :session/web-search     session-web-search
   :diff/web-load          diff-web-load
   :commits/web-load       commits-web-load
   :files/web-list         files-web-list
   :files/web-tree         files-web-tree
   :file/web-read          file-web-read
   :dismissed/toggle       dismissed-toggle
   :pinned/toggle          pinned-toggle
   :session/delete         session-delete
   :session/mark-read      session-mark-read
   :session/buffer-close   session-buffer-close
   :rooms/prune            rooms-prune})

;; ── Auto-destroy chains (pure) ───────────────────────────────────────────────

(defn client-disconnect-cleanup
  "Chained before the core :client/disconnect handler: close the room when this
   was its last client and it is idle. Busy rooms keep running clientless;
   turn-end-room-cleanup reaps them."
  [st {:keys [client-id]}]
  (when-let [room-id (get-in st [:connection :clients client-id :room-id])]
    (let [others (remove #{client-id} (clients-in-room st room-id))]
      (if (and (empty? others)
               (not (keep-alive? (get-in st [:rooms room-id]))))
        {:effects [[:app/dispatch {:type :room/close :room-id room-id}]]}
        ;; The room lives on without this client: refresh its presence
        ;; (computed without the leaver — the core handler drops it next).
        {:effects [(presence-effect (update-in st [:connection :clients] dissoc client-id)
                                    room-id)]}))))


(defn client-update-presence
  "Chained after the core :client/update handler: refresh the room's presence
   with the client's :buffer."
  [st {:keys [client-id] :as ev}]
  (when (contains? ev :buffer)
    (when-let [room-id (get-in st [:connection :clients client-id :room-id])]
      (when (state/get-room st room-id)
        {:effects [(presence-effect st room-id)]}))))

(defn turn-end-room-cleanup
  "Chained onto :agent/turn-end: close a room nobody is attached to, unless a
   dialog is still pending."
  [st {:keys [room-id]}]
  (when (and (state/get-room st room-id)
             (empty? (clients-in-room st room-id))
             (not (keep-alive? (state/get-room st room-id))))
    {:effects [[:app/dispatch {:type :room/close :room-id room-id}]]}))

;; ── Buffers across the room's life ───────────────────────────────────────────
;; A room's buffers (xi.buffers: the diffs and files its users opened) are room
;; state, so closing the room would drop them — and an idle room closes the
;; moment its last client switches chats. Instead they are parked per session
;; in server memory and come back with the next room for that session. Nothing
;; is written to disk: a restart starts with no buffers, by design.

(defn park-buffers
  "Chained before the core :room/close handler: keep the room's buffers under
   `[:parked-buffers sid]` for the next room resuming the session. A deleted
   session parks nothing."
  [st {:keys [room-id]}]
  (let [room (state/get-room st room-id)
        sid  (get-in room [:session :id])
        bufs (get-in room [:ui :buffers])]
    (when (and sid (seq bufs) (not (get-in room [:session :deleted?])))
      {:state (assoc-in st [:parked-buffers sid] bufs)})))

(defn revive-buffers
  "Chained after the core :room/create handler: a room opening on a session
   with parked buffers takes them over (its own win) and clears the slot."
  [st {:keys [room-id]}]
  (let [sid    (get-in st [:rooms room-id :session :id])
        parked (get-in st [:parked-buffers sid])]
    (when (seq parked)
      {:state (-> st
                  (update-in [:rooms room-id :ui :buffers] #(merge parked %))
                  (update :parked-buffers dissoc sid))})))

(defn forget-parked-buffers
  "Chain onto :session/delete: a deleted session's parked buffers go with it."
  [st {:keys [session-id]}]
  (when (contains? (:parked-buffers st) session-id)
    {:state (update st :parked-buffers dissoc session-id)}))


(defn subagent-summaries
  "The lobby-sized rows of a room's background sub-agents in spawn order,
   `[{:id :kind :subagent :title :status} …]`, Explain sub-agents excluded."
  [room]
  (into []
        (keep (fn [{:keys [id label task status]}]
                (when-not (sa/explain-sub? id)
                  {:id     id
                   :kind   :subagent
                   :title  (or label task "sub-agent")
                   :status status})))
        (get-in room [:ext :subagents :agents])))

(defn session-buffers
  "Lobby-facing `{session-id [{:id :kind :title} …]}` of every session with
   buffers open (live or parked); a live room's sub-agents follow its buffers."
  [st]
  (-> {}
      (into (keep (fn [[sid bufs]]
                    (when (seq bufs) [sid (buffers/summaries bufs)])))
            (:parked-buffers st))
      (into (keep (fn [[_ room]]
                    (let [sid  (get-in room [:session :id])
                          rows (into (buffers/summaries (get-in room [:ui :buffers]))
                                     (subagent-summaries room))]
                      (when (and sid (seq rows) (not (get-in room [:session :deleted?])))
                        [sid rows]))))
            (:rooms st))))

(defn reap-idle-clientless-rooms
  "Close every idle room with no client attached. Chained onto :room/attach: a
   client switching rooms only re-attaches, so the room it left never gets a
   :room/leave. Busy rooms and pending dialogs are spared."
  [st _ev]
  (let [closes (for [[room-id room] (:rooms st)
                     :when (and (empty? (clients-in-room st room-id))
                                (not (keep-alive? room)))]
                 [:app/dispatch {:type :room/close :room-id room-id}])]
    (when (seq closes)
      {:effects (vec closes)})))