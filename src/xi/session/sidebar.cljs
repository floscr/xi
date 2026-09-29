(ns xi.session.sidebar
  "Pure session-list logic shared by the web sidebar (xi.web.views) and the TUI
   sidebar (xi.client.sidebar). Turns the lobby mirror ([:lobby ...], mirrored
   onto every client) into the Recent / Hidden / Earlier display groups and a
   flat navigation order, plus the per-session live-status enrichment both
   surfaces render. Kept surface-neutral (no hiccup, no ANSI) so the TUI and
   web agree on grouping and ALT+j/k order."
  (:require [xi.palette :as palette]
            [xi.session.recent :as recent]))

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

(defn session-status
  "Enrich a session map with live indicator flags derived from app state:
   :active? (has a live room), :busy?, :has-dialog? (needs response),
   :unread? (more responses than last watched). Centralizes the logic shared
   by every session listing so indicators aren't computed twice.

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
    {:session-id  sid
     :name        (:name s)
     :cwd         (:cwd s)
     :timestamp   (or (:last-accessed s) (:timestamp s))
     :favorite?   (boolean (:favorite? s))
     :dismissed?  (boolean (:dismissed? s))
     :current?    (and sid (= sid (get-in state [:web/route :session-id])))
     :active?     (boolean room)
     :busy?       (boolean (:busy? room))
     :has-dialog? (boolean (:has-dialog? room))
     :unread?     (> (get counts sid 0) seen)}))

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
                  {:session-id  sid
                   :name        (or (some :session-name rooms) "New session")
                   :cwd         (some :cwd rooms)
                   :current?    (= sid (get-in state [:web/route :session-id]))
                   :active?     true
                   :busy?       (boolean (some :busy? rooms))
                   :has-dialog? (boolean (some :has-dialog? rooms))}))))))

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

(defn sidebar-session-order
  "Flattened session-ids as shown in the drawer sidebar
   (Recent \u2192 Hidden \u2192 Earlier). Used by ALT+j/k session navigation."
  [state]
  (let [{:keys [recent hidden earlier]} (sidebar-session-groups state)]
    (->> (concat recent hidden earlier)
         (keep :session-id)
         vec)))
