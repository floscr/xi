(ns xi.session.sidebar
  "Pure session-list logic shared by the web sidebar (xi.web.views) and the TUI
   sidebar (xi.client.sidebar). Turns the lobby mirror ([:lobby ...], mirrored
   onto every client) into the Recent / Hidden / Earlier display groups and a
   flat navigation order, plus the per-session live-status enrichment both
   surfaces render. Kept surface-neutral (no hiccup, no ANSI) so the TUI and
   web agree on grouping and ALT+j/k order."
  (:require [xi.core.state :as cstate]
            [xi.palette :as palette]
            [xi.session.recent :as recent]
            [xi.user-state :as user-state]))

(defn format-relative-time [t]
  (let [ms (cond (number? t) t
                 (string? t) (let [n (.getTime (js/Date. t))] (when-not (js/isNaN n) n))
                 :else nil)]
    (when ms
      (let [m (/ (- (js/Date.now) ms) 60000)]
        (cond (< m 1)    "just now"
              (< m 60)   (str (js/Math.floor m) "m ago")
              (< m 1440) (str (js/Math.floor (/ m 60)) "h ago")
              :else      (str (js/Math.floor (/ m 1440)) "d ago"))))))

(defn room-people
  "The other users attached to a room as avatar data [{:id :name :avatar}],
   from the public profiles on the lobby payload: the viewer's own user is
   left out, as in the chat top bar. Empty on a single-user server (only one
   user known): an avatar on every live chat would be noise there, and the
   common case stays as quiet as before."
  [state user-ids]
  (let [profiles (get-in state [:lobby :profiles])
        me       (cstate/own-user state)]
    (if (> (count profiles) 1)
      (->> user-ids
           (remove #{me})
           (mapv (fn [id] (assoc (get profiles id) :id id))))
      [])))

(defn session-buffers
  "The buffers open for a session — in its live room or parked on the server
   while no room hosts it — as the lobby lists them: `[{:id :kind :title} …]`
   (xi.buffers/summaries, `:buffers` on :lobby/state)."
  [state sid]
  (vec (get-in state [:lobby :buffers sid])))

(defn session-status
  "Enrich a session map with live indicator flags derived from app state:
   :active? (has a live room), :busy?, :has-dialog? (needs response),
   :error? (last turn failed), :unread? (more responses than last watched),
   :buffers (its open diffs / files, see session-buffers). Centralizes the
   logic shared by every session listing so indicators aren't computed twice.

   :unread? relies on the web-only :web/response-counts / :web/watched slices;
   on surfaces that don't track them (the TUI) it degrades to false."
  [state s]
  (let [sid     (:session-id s)
        rooms   (get-in state [:lobby :rooms])
        counts  (:web/response-counts state)
        ;; Seen-count = the later of the server-authoritative read state (synced
        ;; across devices, via the lobby payload) and the local overlay (an
        ;; instant, offline-durable clear on this device). Whichever is further
        ;; ahead wins, so a read on any device sticks.
        seen    (max (get-in state [:lobby :read sid] 0)
                     (get-in state [:web/watched sid] 0))
        room    (some (fn [r] (when (= (:session-id r) sid) r)) rooms)]
    (merge
     ;; the user's extension flags (e.g. :favorite?), which an extension's
     ;; sidebar groups and session menu items key on
     (user-state/extension-flags s)
     {:session-id  sid
      :name        (:name s)
      :cwd         (:cwd s)
      :timestamp   (or (:last-accessed s) (:timestamp s))
      :dismissed?  (boolean (:dismissed? s))
      :current?    (and sid (= sid (get-in state [:web/route :session-id])))
      :active?     (boolean room)
      :busy?       (boolean (:busy? room))
      :has-dialog? (boolean (:has-dialog? room))
      :error?      (boolean (:error? room))
      :people      (room-people state (:users room))
      :buffers     (session-buffers state sid)
      ;; buffer presence: buffer id → users on it (xi.buffers/viewers)
      :viewers     (:viewers room)
      :unread?     (> (get counts sid 0) seen)})))

(defn active-first
  "Enrich disk sessions with live indicators (via session-status) and pin the
   ones backed by a live room to the top, preserving the incoming
   (last-visited) order within each group. Keeps working/active rooms visible
   at the top of every session listing instead of buried by newer sessions."
  [state sessions]
  (let [{active true inactive false}
        (group-by (comp boolean :active?)
                  (map #(session-status state %) sessions))]
    (concat active inactive)))

(defn orphan-rooms
  "Live rooms from the lobby mirror that have no matching disk session in
   `sessions`. These are freshly created rooms whose session hasn't been
   persisted to disk yet, so the disk-session-first listings would otherwise
   miss them entirely. Optionally restrict to a single `cwd`. Returns
   session-card-ready data maps, newest first (room-summaries is pre-sorted)."
  ([state sessions] (orphan-rooms state sessions nil))
  ([state sessions cwd]
   (let [known-sids (set (keep :session-id sessions))]
     (->> (get-in state [:lobby :rooms])
          (filter (fn [r] (and (:session-id r)
                               (not (known-sids (:session-id r)))
                               (or (nil? cwd) (= cwd (:cwd r))))))
          ;; Several rooms can share one session-id (e.g. a lingering
          ;; clients:0 room plus a freshly reopened one). Collapse them to a
          ;; single card so the list shows one row — and one spinner — per
          ;; session instead of colliding on :replicant/key.
          (group-by :session-id)
          (mapv (fn [[sid rooms]]
                  (merge
                   ;; the user's extension flags (e.g. :favorite?) ride on
                   ;; the room summary (xi.server.ws/for-user)
                   (user-state/extension-flags (first rooms))
                   {:session-id  sid
                    :name        (or (some :session-name rooms) "New session")
                    :cwd         (some :cwd rooms)
                    :current?    (= sid (get-in state [:web/route :session-id]))
                    :active?     true
                    :busy?       (boolean (some :busy? rooms))
                    :has-dialog? (boolean (some :has-dialog? rooms))
                    :error?      (boolean (some :error? rooms))
                    :people      (room-people state (distinct (mapcat :users rooms)))
                    :buffers     (session-buffers state sid)
                    :viewers     (apply merge-with into (map :viewers rooms))})))))))

(defn extension-group
  "One extension-declared sidebar group (a `:sidebar-groups` entry, see
   xi.ext.core) as {:id :label :more :cards :total}: the lobby's sessions
   whose `:where` key is truthy, most recently visited first, `:limit` of
   them as session cards; :total counts them all and :more is passed through
   (the caller shows it when :total exceeds the cards). Read from the lobby's
   sessions and live rooms rather than the Recent list, so a session stays
   reachable however old it is (the server sends every flagged session,
   however old)."
  [state {:keys [where limit] :as group}]
  (let [sessions (get-in state [:lobby :sessions])
        ;; a live room hides its saved session from the lobby list, so rooms
        ;; the list leaves out come first: they are what is open right now
        live     (filter where (orphan-rooms state sessions))
        found    (concat live
                         (->> sessions
                              (filter where)
                              (sort-by palette/session-time #(compare %2 %1))))]
    (assoc (select-keys group [:id :label :more])
           :cards (->> (cond->> found limit (take limit))
                       (mapv #(session-status state %)))
           :total (count found))))

(defn extension-groups
  "The non-empty extension sidebar groups (see `extension-group`) in
   declaration order. They repeat sessions of the Recent / Hidden / Earlier
   groups, so keyboard navigation (`sidebar-session-order`) leaves them out."
  [state groups]
  (->> groups
       (map #(extension-group state %))
       (filterv (comp seq :cards))))

(defn sidebar-session-groups
  "Session cards for the drawer sidebar, split into the display groups
   Recent / Hidden / Earlier (in render order). Busy agents pin to the top,
   then most-recently-visited. Shared by the rendered sidebar and ALT+j/k
   keyboard navigation so both agree on order."
  [state]
  (let [dismissed-ids (->> (get-in state [:lobby :sessions])
                           (filter :dismissed?)
                           (map :session-id)
                           set)
        sessions (palette/recent-sessions state)
        orphans  (orphan-rooms state sessions)
        cards    (->> (concat orphans (map #(session-status state %) sessions))
                      (filter :session-id)
                      (reduce (fn [{:keys [seen acc]} c]
                                (if (seen (:session-id c))
                                  {:seen seen :acc acc}
                                  {:seen (conj seen (:session-id c))
                                   :acc  (conj acc c)}))
                              {:seen #{} :acc []})
                      :acc)
        cards    (let [{busy true idle false} (group-by #(boolean (:busy? %)) cards)]
                   (concat busy idle))
        cards    (map #(assoc % :dismissed? (boolean (dismissed-ids (:session-id %)))) cards)
        {hidden true visible false} (group-by :dismissed? cards)
        now      (js/Date.now)
        started  (get-in state [:lobby :started-at])
        {recent true earlier false} (group-by #(recent/recent? now started %) visible)]
    {:recent (vec recent) :hidden (vec hidden) :earlier (vec earlier)}))

(defn attention-order
  "Session-ids worth jumping to, most pressing first: sessions waiting on a
   dialog, then finished ones with unread output, then running ones. Each tier
   is newest-first, and a session lands in the first tier it qualifies for.
   `cards` are session-status maps (see `sidebar-session-groups`)."
  [cards]
  (let [newest-first #(sort-by (fn [c] (recent/->ms (:timestamp c))) > %)
        waiting?     :has-dialog?
        unread?      #(and (:unread? %) (not (:busy? %)))
        running?     :busy?]
    (->> (concat (newest-first (filter waiting? cards))
                 (newest-first (filter #(and (not (waiting? %)) (unread? %)) cards))
                 (newest-first (filter #(and (not (waiting? %)) (not (unread? %)) (running? %)) cards)))
         (keep :session-id)
         distinct
         vec)))

(defn next-attention-jump
  "Where the next press of the jump-to-attention key goes: `{:sid :visited}`,
   or nil when `order` (see `attention-order`) holds nothing but `cur`.
   `visited` is the set of sessions the current chain of presses already
   landed on. They are skipped, so repeated presses walk the whole order
   (dialog → unread → running) instead of bouncing between the top two while
   a dialog stays pending. Once the order is exhausted the chain restarts from
   the top."
  [order cur visited]
  (let [skip (conj visited cur)]
    (if-let [sid (first (remove skip order))]
      {:sid sid :visited (conj skip sid)}
      (when-let [sid (first (remove #{cur} order))]
        {:sid sid :visited #{cur sid}}))))

(defn sidebar-session-order
  "Flattened session-ids as shown in the drawer sidebar
   (Recent \u2192 Hidden \u2192 Earlier). Used by ALT+j/k session navigation."
  [state]
  (let [{:keys [recent hidden earlier]} (sidebar-session-groups state)]
    (->> (concat recent hidden earlier)
         (keep :session-id)
         vec)))
