(ns xi.session.recent
  "Shared \"Recent\" filtering for session listings — the same predicate the web
   sidebar uses to split sessions into its top \"Recent\" section and the CLI
   uses to pick which chats to spin up as workspaces. Dependency-free (only
   js/Date) so it is importable by both the browser `web` target and the node
   `main`/CLI target.")

(def recent-active-window-ms
  "How fresh a session's last response must be to count as \"Recent\" — a couple
   of days."
  (* 2 24 60 60 1000))

(defn ->ms
  "Coerce a session timestamp (epoch ms number or ISO string) to epoch ms, or
   0 when absent/unparseable."
  [t]
  (cond
    (number? t) t
    (string? t) (let [n (.getTime (js/Date. t))] (if (js/isNaN n) 0 n))
    :else 0))

(defn recent?
  "True when a session belongs in the \"Recent\" set: its last activity
   (:timestamp — the last turn / save, never a mere open) happened during the
   current server run (at/after `started-at`) AND within
   `recent-active-window-ms` (a couple of days). Opening an Earlier session
   leaves it there; it moves up once new content arrives — a running turn
   (:busy?) qualifies straight away. A live room with no timestamp yet (a
   brand-new, never-persisted chat) qualifies, and so does a pinned session
   (:pinned?), however old: pinning opts a session out of ever aging into
   \"Earlier\".

   Callers without a server run (e.g. the one-shot CLI) pass `started-at` 0,
   reducing the predicate to \"activity within the last couple of days\"."
  [now-ms started-at {:keys [active? busy? pinned? timestamp]}]
  (let [t (->ms timestamp)]
    (boolean
     (or pinned?
         busy?
         (and active? (not (pos? t)))
         (and started-at
              (pos? t)
              (>= t started-at)
              (<= (- now-ms t) recent-active-window-ms))))))

(defn recent-cards
  "Build the \"Recent\" session set the web sidebar shows, from raw server
   state — so the CLI (`xi sessions`) and the web agree on which chats are
   recent. Mirrors xi.web.views/recent-sidebar:

     - `rooms`      live rooms (room-manager/room-summaries: maps with
                    :session-id :session-name :cwd :busy?). A live room
                    without a saved session always qualifies.
     - `sessions`   saved sessions from disk (summaries with :session-id :name
                    :cwd :last-accessed :timestamp).
     - `started-at` the server's boot time (epoch ms); recency is relative to
                    the current server run.
     - `now`        current epoch ms.

   Returns the qualifying sessions as {:session-id :name :cwd :active? :busy?}
   maps: orphan live-rooms (no disk session yet) first, then disk sessions,
   deduped by id, agents-running pinned to the top, filtered by `recent?`."
  [{:keys [rooms sessions started-at now]}]
  (let [active-sids (into #{} (keep :session-id) rooms)
        busy-sids   (into #{} (comp (filter :busy?) (keep :session-id)) rooms)
        known       (into #{} (keep :session-id) sessions)
        orphans     (->> rooms
                         (filter (fn [r] (and (:session-id r)
                                              (not (known (:session-id r))))))
                         (group-by :session-id)
                         (map (fn [[sid rs]]
                                {:session-id sid
                                 :name       (or (some :session-name rs) "New session")
                                 :cwd        (some :cwd rs)
                                 :active?    true
                                 :busy?      (boolean (some :busy? rs))})))
        enriched    (map (fn [s]
                           {:session-id (:session-id s)
                            :name       (:name s)
                            :cwd        (:cwd s)
                            :active?    (contains? active-sids (:session-id s))
                            :pinned?    (boolean (:pinned? s))
                            :busy?      (contains? busy-sids (:session-id s))
                            :timestamp  (or (:last-accessed s) (:timestamp s))})
                         sessions)
        cards       (->> (concat orphans enriched)
                         (filter :session-id)
                         (reduce (fn [{:keys [seen acc]} c]
                                   (if (seen (:session-id c))
                                     {:seen seen :acc acc}
                                     {:seen (conj seen (:session-id c))
                                      :acc  (conj acc c)}))
                                 {:seen #{} :acc []})
                         :acc)
        cards       (let [{busy true idle false} (group-by #(boolean (:busy? %)) cards)]
                      (concat busy idle))]
    (filter #(recent? now started-at %) cards)))
