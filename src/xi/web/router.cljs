(ns xi.web.router
  "History-based routing for the web client, expressed as events over the
   single app atom — no separate router atom.

   Routes:
     /                  → {:page :home}
     /chat/:session-id  → {:page :chat :session-id sid}
     /projects           → {:page :home} (project list)
     /projects/:cwd      → {:page :home :dir <decoded-cwd>}

   `:route/navigate` is a pure handler: it sets `:web/route` and emits a
   `[:history/push …]` effect plus the room event the destination implies
   (join a session on entering chat, leave on returning home). `init!`
   seeds the initial route from the URL and forwards popstate as a
   `:replace?`-tagged navigate."
  (:require [clojure.string :as str]
            [xi.core.state :as state]))

;; ── Path <-> route ───────────────────────────────────────────────────────────

(defn parse-path
  "URL path → route map. Extension route entries (keyed by first URL
   segment, from ext/compose :routes) take precedence over the built-ins;
   their :parse fn receives the remaining segments."
  [routes path]
  (let [segments (filterv seq (str/split (or path "/") #"/"))]
    (if-let [entry (get routes (first segments))]
      ((:parse entry) (vec (rest segments)))
      (case (first segments)
      "chat" {:page :chat :session-id (second segments)}
      "git-status" (cond-> {:page :git-status}
                     (second segments)
                     (assoc :cwd (js/decodeURIComponent (str/join "/" (rest segments)))))
      "projects" (let [seg2 (second segments)
                       dir  (cond
                              (nil? seg2) nil
                              (= seg2 "all") :all
                              (= seg2 "favorites") :favorites
                              :else (js/decodeURIComponent seg2))]
                   (cond-> {:page :home}
                     dir (assoc :dir dir)))
      {:page :home}))))

(defn route->path
  "Route map → URL path. Extension :path fns (keyed by page) take
   precedence over the built-ins."
  [routes {:keys [page session-id file task-id dir cwd number] :as route}]
  (if-let [f (some #(get-in % [:path page]) (vals routes))]
    (f route)
    (case page
    :chat (if session-id (str "/chat/" session-id) "/chat")
    :git-status (if cwd (str "/git-status/" (js/encodeURIComponent cwd)) "/git-status")
    ;; :home — use /projects/:cwd when drilling into a directory
    (cond
      (= dir :all) "/projects/all"
      (= dir :favorites) "/projects/favorites"
      dir          (str "/projects/" (js/encodeURIComponent dir))
      :else        "/"))))

(defn roomless-pages
  "Pages that imply leaving the active room on navigation: the built-ins
   plus every extension route entry's :roomless-pages."
  [routes]
  (into #{:home} (mapcat :roomless-pages) (vals routes)))

;; ── Navigation (pure handler) ────────────────────────────────────────────────

(defn- session->room-id
  "An existing live room hosting this session, if any (so we attach instead
   of resuming a duplicate)."
  [st session-id]
  (some (fn [r] (when (= session-id (:session-id r)) (:id r)))
        (get-in st [:lobby :rooms])))

(defn navigate
  "Set the route; push/replace history; drive the implied room change.
     roomless    set of pages that imply leaving the active room
     :page       :home | :chat
     :session-id (chat only)
     :replace?   true for popstate / initial load (no new history entry)"
  [roomless st {:keys [page session-id file task-id dir cwd number replace?]}]
  (let [route      (cond-> {:page page :session-id session-id}
                     file    (assoc :file file)
                     task-id (assoc :task-id task-id)
                     dir     (assoc :dir dir)
                     cwd     (assoc :cwd cwd)
                     number  (assoc :number number))
        active-room (state/active-room st)
        active-sid (get-in active-room [:session :id])
        ;; Already viewing this session (e.g. the post-join URL fix) → don't
        ;; re-join or re-mark.
        already?   (and (= page :chat) session-id (= session-id active-sid))
        ;; The room we're leaving is a brand-new session the user never sent
        ;; a message in (no history) and left an empty prompt for. Switching
        ;; straight to another chat would orphan it as an idle "active" room
        ;; in the lobby, so close it on the way out. (The :home/:gtd branch
        ;; below already leaves the room, so we only need this for chat→chat.)
        leaving-empty-new?
        (and active-room (not already?)
             (empty? (:history active-room))
             (not (get-in active-room [:agent :busy?]))
             (str/blank? (get-in st [:web/drafts (or active-sid :new)])))
        effects (cond-> [[:history/push {:route route :replace? replace?}]]
                  ;; Leave (→ server-side close) the empty room BEFORE joining
                  ;; the next one, so the server frees it instead of orphaning
                  ;; it (room/leave acts on the client's current membership).
                  (and leaving-empty-new? (not (roomless page)))
                  (conj [:app/dispatch {:type :room/leave}])

                  (and (= page :chat) session-id (not already?))
                  (conj ;; Paint the target's cached history immediately while
                        ;; the join round-trips (slow on mobile).
                        [:cache/seed-room {:session-id session-id}]
                        ;; Join through the cache-aware effect so the client
                        ;; can echo the cached msg-hash and let the server skip
                        ;; re-sending unchanged history over the (slow) wire.
                        ;; Always carry :session-id so the server can resume
                        ;; even when the lobby cache has a stale room-id that
                        ;; no longer exists on a restarted server.
                        [:room/join-with-cache
                         {:target     (or (session->room-id st session-id)
                                          {:session-id session-id})
                          :session-id session-id}]
                        [:app/dispatch {:type :session/mark-read
                                        :session-id session-id}])

                  (roomless page)
                  (conj [:app/dispatch {:type :room/leave}])

                  ;; Fetch sessions when drilling into a project directory
                  (and (= page :home) dir (not= dir :all) (not= dir :favorites))
                  (conj [:app/dispatch {:type :projects/web-sessions :cwd dir}])

                  ;; Fetch the working-tree diff when entering the git-status page
                  (and (= page :git-status) cwd)
                  (conj [:app/dispatch {:type :git-status/load :cwd cwd}])

                  ;; Closing the mobile drawer on a real navigation (see the
                  ;; :web/sidebar-open? reset below): force a re-raster so iOS
                  ;; WebKit doesn't leave the drawer stuck open on a session
                  ;; switch. No-op on desktop/docked. Skipped on the post-join
                  ;; URL sync (already?), which must not touch the drawer.
                  (and (:web/sidebar-open? st) (not already?))
                  (conj [:sidebar/repaint]))]
    {:state   (cond-> (assoc st :web/route route
                            ;; reset the virtualized timeline window on every
                            ;; navigation so a new session starts compact
                            :web/timeline-window nil)
                ;; Close the recent-sessions drawer on a real navigation, but
                ;; NOT on the post-join URL sync (already? — a virtual new chat
                ;; getting its real session id after the first message). That
                ;; sync isn't a user navigation, so it must not yank a sidebar
                ;; the user left open shut from under them.
                (not already?)
                (assoc :web/sidebar-open? false)
                ;; Leaving a chat we were viewing: remember the session so the
                ;; next fresh count marks it read (the user saw responses that
                ;; landed while attached, before counts refreshed). See
                ;; counts-result.
                (and (roomless page) active-sid)
                (assoc :web/pending-read active-sid)
                ;; Leaving the virtual new chat for a real destination (a
                ;; session, home, or gtd): drop its pending-room so its draft
                ;; can't resurface in another chat. A fresh virtual chat gets a
                ;; new pending-room (with a new id) via :room/new.
                (or (not= page :chat) session-id)
                (dissoc :web/pending-room)
                ;; Sync the git-status cwd from the route
                (= page :git-status) (assoc :web/git-status-cwd cwd)
                ;; Sync project dir drill-down from the route
                (= page :home) (-> (assoc :web/selected-project-dir dir)
                                   (cond->
                                     ;; Clear stale sessions when navigating away
                                     (nil? dir) (dissoc :web/project-sessions
                                                        :web/project-sessions-cwd)
                                     ;; Clear old data when drilling into a new dir
                                     (and dir (not= dir :all) (not= dir :favorites))
                                     (-> (dissoc :web/project-sessions)
                                         (update :web/search dissoc :project-sessions)
                                         (update :web/content-search dissoc :project-sessions)
                                         (update :web/content-matches dissoc :project-sessions)
                                         (assoc :web/project-sessions-loading? true)))))
     :effects effects}))

(defn nav-back
  "Pure handler for :nav/back — emits the :nav/back effect."
  [_st {:keys [fallback]}]
  {:effects [[:nav/back {:fallback fallback}]]})

(defn handlers
  "Router handler map, closed over the composed extension route table."
  [routes]
  (let [roomless (roomless-pages routes)]
    {:route/navigate (fn [st ev] (navigate roomless st ev))
     :nav/back       nav-back}))

;; ── History effect + init (impure edge) ──────────────────────────────────────

;; How many pushState entries the app owns. Used by :nav/back to decide
;; whether history.back() has somewhere to go or needs a fallback route.
(defonce nav-depth (atom 0))

(defn history-effect
  "The `:history/push` effect — pushState/replaceState the route's path.
   Closed over the composed extension route table."
  [routes]
  (fn [_ctx {:keys [route replace?]}]
    (let [path (route->path routes route)]
      (if replace?
        (.replaceState js/window.history #js {:navDepth @nav-depth} "" path)
        (when (not= path (.-pathname js/window.location))
          (let [d (swap! nav-depth inc)]
            (.pushState js/window.history #js {:navDepth d} "" path)))))))

(defn back-effect
  "The `:nav/back` effect — go back in browser history when the app owns
   entries, otherwise dispatch the fallback route."
  [{:keys [dispatch!]} {:keys [fallback]}]
  (if (pos? @nav-depth)
    (.back js/history)
    (dispatch! (assoc fallback :type :route/navigate :replace? true))))

(defn init!
  "Seed the initial route from the URL and forward popstate as navigate.
   Called once after the app is created."
  [routes dispatch!]
  ;; Restore nav-depth from history.state (survives page reloads)
  (when-let [d (some-> js/history.state (.-navDepth))]
    (reset! nav-depth d))
  (let [route->ev (fn [] (assoc (parse-path routes (.-pathname js/window.location))
                                :type :route/navigate :replace? true))]
    (dispatch! (route->ev))
    (.addEventListener js/window "popstate"
                       (fn [_]
                         (swap! nav-depth #(max 0 (dec %)))
                         (dispatch! (route->ev))))))