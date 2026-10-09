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
   :platform :buffer}. :buffer is the view the client shows (:chat or a
   buffer id, from :client/update — xi.buffers/viewers). The registry never
   crosses the wire, so this derived map is stored on the room (:members) and
   broadcast as :room/presence."
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
  "A room must survive client departure / idle reaping while its agent is
   running OR a dialog is awaiting a response. Closing a room discards its
   :ui :dialogs, which would strand the pending question and the turn
   suspended on it (a dialog stays open even with no client viewing — see
   ext/create-dialogs).

   It must also survive while the process-manager extension is tracking
   live background processes: closing the room fires :room/close, which
   kills them (ext/process-manager on-room-close). We only auto-close a
   room the user has walked away from — not one running their dev server.

   Likewise it must survive while a background SUB-AGENT is still running
   (e.g. the /canvas-review builder): the sub-agent runs as a background
   process while the room's own agent is idle, so without this check the
   room reaps out from under it the moment its last client navigates away
   — aborting the turn mid-build (the canvas gets the diff but no nodes)."
  [room]
  (or (boolean (get-in room [:agent :busy?]))
      (boolean (seq (get-in room [:ui :dialogs])))
      (boolean (seq (get-in room [:ext :process-manager :processes])))
      (boolean (some #(= :running (:status %))
                     (get-in room [:ext :subagents :agents])))))

(defn prunable?
  "A room is prunable (\"inactive\") when its agent isn't mid-turn and it holds
   no pending dialog — nobody is actively working in or waiting on it. Unlike
   keep-alive? this deliberately IGNORES live background processes: a manual
   prune is meant to tear those down (and detach any lingering client) to clear
   rooms left behind by open terminals."
  [room]
  (and (not (get-in room [:agent :busy?]))
       (empty? (get-in room [:ui :dialogs]))))

(defn- room-errored?
  "True when the room's last turn ended in an error: the newest history entry
   is an :error and nothing is running. A new prompt (or an interrupt) pushes a
   later entry, which clears it."
  [room]
  (and (not (get-in room [:agent :busy?]))
       (= :error (:kind (last (:history room))))))

(defn room-summaries
  "Lobby-facing room list, newest first. Rooms whose session was deleted
   while still keep-alive (see session-delete) are dropped so the deleted
   card doesn't reappear in the lobby while the turn finishes."
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
  "Find an existing room hosting this session-id, if any. Matches on the Xi
   session uuid AND the Claude CLI id (:provider-session-id / :cli-session-id):
   a client may resume by either — a stale /chat/<cli-id> URL, a cached route,
   or the transient Claude-CLI card that surfaces mid-turn. Matching only the
   Xi uuid would miss the live room and fork a fresh disk resume, leaving the
   originating client (e.g. an open TUI) stuck on the now-orphaned room."
  [st session-id]
  (some (fn [[_ room]]
          (let [sess (:session room)]
            (when (or (= session-id (:id sess))
                      (= session-id (:provider-session-id sess))
                      (= session-id (:cli-session-id sess)))
              (:id room))))
        (:rooms st)))

(defn- room-join
  "Resolve a join target to an existing room (→ attach) or a new one
   (→ :room/setup effect, which creates then attaches).

   Targets: \"new\" | \"latest\" | room-id | {:session-id sid} (resume a
   saved session into a fresh room)."
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
  "The room part of a :room/joined event: {:room snapshot}, or — when the
   client says it already caches this room's history (or a clean prefix of
   it) as `cached-hash` over its first `cached-count` entries — the snapshot
   without :history plus {:history-base {:hash :count} :history-tail […]}, so
   re-opening a live chat ships only what's new instead of the whole
   transcript (megabytes for a long session). The client splices its cached
   history back in front of the tail. A history rewritten or still streaming
   into the cached last entry fails the hash → full snapshot. Hashes are cljs
   `hash` on both ends (same code, equal values ↔ equal hashes, as for the
   disk resume's :msg-hash); vectors and their maps cache them, so only
   entries new since the last attach cost anything."
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
  "Unread-count query: assistant-turn counts per saved session. Reading
   sessions hits disk, so the pure handler just emits an effect the WS
   layer fulfils."
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
  "Roomless: full-text search over saved sessions for the command palette's
   in-panel search — like session-content-search, but the reply carries full
   summaries with match snippets instead of bare ids. `names-only?` skips the
   transcript text (the search page's toggle)."
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
  "Roomless: list the commits made during a session (base..HEAD) for the web
   commit bar. The cwd + the session's created timestamp travel from the
   client's mirrored room, since the request carries no room-id."
  [_st {:keys [client-id cwd created]}]
  {:effects [[:commits/web-load-reply {:client-id client-id :cwd cwd :created created}]]})

(defn- files-web-list
  "Roomless: list a directory's children for the web file browser. The browse
   path is absolute (seeded from the client's mirrored room cwd, then advanced
   by drill-down); cwd is the fallback when no path is sent yet."
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
  "Roomless: hide/show a session in the sender's recent list by id. The persist
   + lobby rebroadcast happen in the :dismissed/toggle-reply effect (needs disk
   access).

   As a courtesy we also tear down the session's live room when it is safe to
   do so — i.e. it holds no client, its agent isn't mid-turn, no dialog is
   pending, and it isn't tracking background processes (keep-alive?). This
   frees a lingering, finished job the user is dismissing without ever killing
   a running turn, a room someone is still viewing, or a dev server. A room
   that fails these checks is simply left running (it auto-closes later)."
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
  "Roomless: pin/unpin a session in the sender's recent list by id. The
   persist + lobby rebroadcast happen in the :pinned/toggle-reply effect
   (needs disk access)."
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
  "Roomless: permanently delete a saved session by id. The unlink + lobby
   rebroadcast happen in the :session/delete-reply effect (needs disk access).

   We must also tear down the session's live room, if any — otherwise it
   lingers in the lobby (room-summaries keeps emitting its card) and would
   re-persist itself on the next turn sync, so the delete visibly does
   nothing when the session is open in a room.
   Unlike dismissed-toggle we do NOT skip rooms with a client attached: a
   delete is an explicit, destructive intent, so a session being viewed must
   go too. To avoid stranding that client we swap its room to a fresh blank
   session (:session/new, no save) rather than closing the room out from
   under it; a clientless room is simply closed.

   A blank room (see blank-room?) is the exception: swapping it to a fresh
   blank session just recreates the same \"New session\" card, so the delete
   never takes while any client has it open. Its clients are detached with
   :room/left (as rooms-prune does) and the room is closed.

   keep-alive? rooms can't be torn down — a running turn, a pending dialog,
   live background processes, or a running sub-agent must not be killed by a
   lobby delete. But leaving them fully untouched made the delete silently
   fail on an active room: the live room keeps emitting its lobby card
   (room-summaries) and re-persists the file on the next :session/sync, so
   the card flashes away (optimistic local drop) and immediately reappears
   from the authoritative rebroadcast. Instead we flag the room's session
   :deleted?, which suppresses its lobby card and skips its sync-persist while
   the turn finishes on its own; the room is reaped normally once it's no
   longer keep-alive."
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
  "Roomless: record a session as seen by the sender up to its current response
   count. The authoritative count is recomputed server-side (in the reply
   effect), so the client only needs to name the session. The persist + lobby
   rebroadcast happen in the :session/mark-read-reply effect (needs disk
   access)."
  [st {:keys [session-id] :as ev}]
  {:effects [[:session/mark-read-reply {:session-id session-id
                                        :user       (state/event-user st ev)}]]})

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


(defn- chat-start
  "Open a new chat seeded with `text` as its first user message. cwd defaults
   to the dispatching room's. :client-id, when given, is sent to the new chat.
   Extensions get this event from commands, keybindings and client clicks only
   (xi.ext.user.guard)."
  [st {:keys [room-id client-id cwd text]}]
  (when (and (string? text) (seq text))
    {:effects [[:chat/start (cond-> {:text text
                                     :cwd  (or cwd (get-in st [:rooms room-id :cwd]))}
                              client-id (assoc :client-id client-id))]]}))

(defn- user-state-set
  "A client changed one piece of its user's UI state (xi.user-state). The
   server stamps :user on the event, so a client can only write its own
   user's state; the :user-state/save effect persists it and tells the
   user's other devices. Unknown keys and invalid values are dropped."
  [_st {:keys [user key value]}]
  (when (user-state/client-valid? key value)
    {:effects [[:user-state/save {:user (util/user-id user) :key key :value value}]]}))


(defn- session-buffer-close
  "Roomless: close one buffer (or, without a :buffer-id, every buffer) of a
   session from the sidebar, wherever it lives — a live room gets the room
   event (so its clients mirror the close), a parked set is edited in place."
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
      (if (and (empty? others)
               (not (keep-alive? (get-in st [:rooms room-id]))))
        {:effects [[:app/dispatch {:type :room/close :room-id room-id}]]}
        ;; The room lives on without this client: refresh its presence
        ;; (computed without the leaver — the core handler drops it next).
        {:effects [(presence-effect (update-in st [:connection :clients] dissoc client-id)
                                    room-id)]}))))


(defn client-update-presence
  "Chain AFTER the core :client/update handler: a client told us which buffer
   it shows (`:buffer`) — refresh its room's presence so everyone in the room
   sees who is on which buffer (xi.buffers/viewers)."
  [st {:keys [client-id] :as ev}]
  (when (contains? ev :buffer)
    (when-let [room-id (get-in st [:connection :clients client-id :room-id])]
      (when (state/get-room st room-id)
        {:effects [(presence-effect st room-id)]}))))

(defn turn-end-room-cleanup
  "Chain onto :agent/turn-end: a turn just finished in a room nobody is
   attached to — close it (the session is already persisted on disk). A room
   still holding a pending dialog is spared (its question outlives the turn)."
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
  "Chain BEFORE the core :room/close handler (it needs the room): keep the
   closing room's buffers under its session id, `[:parked-buffers sid]`, so
   the next room resuming that session gets them back (revive-buffers). A room
   whose session was deleted while it finished its turn parks nothing."
  [st {:keys [room-id]}]
  (let [room (state/get-room st room-id)
        sid  (get-in room [:session :id])
        bufs (get-in room [:ui :buffers])]
    (when (and sid (seq bufs) (not (get-in room [:session :deleted?])))
      {:state (assoc-in st [:parked-buffers sid] bufs)})))

(defn revive-buffers
  "Chain AFTER the core :room/create handler: a room opening on a session with
   parked buffers takes them over (anything the new room already holds wins)
   and the parking slot is cleared. The :room/joined snapshot that follows the
   create carries them to the joining client."
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