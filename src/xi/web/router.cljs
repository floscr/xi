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
  "URL path → route map."
  [path]
  (let [segments (filterv seq (str/split (or path "/") #"/"))]
    (case (first segments)
      "chat" {:page :chat :session-id (second segments)}
      "gtd"  (let [rest-segs (rest segments)
                   ;; Last segment that looks like a UUID is a task-id
                   last-seg  (last rest-segs)
                   task-id?  (and last-seg (re-find #"^[0-9a-f]{8}-" last-seg))
                   task-id   (when task-id? last-seg)
                   file-segs (if task-id? (butlast rest-segs) rest-segs)
                   file      (when (seq file-segs)
                               (js/decodeURIComponent (str/join "/" file-segs)))]
               (cond-> {:page :gtd}
                 file    (assoc :file file)
                 task-id (assoc :task-id task-id)))
      "projects" (let [seg2 (second segments)
                       dir  (cond
                              (nil? seg2) nil
                              (= seg2 "all") :all
                              :else (js/decodeURIComponent seg2))]
                   (cond-> {:page :home}
                     dir (assoc :dir dir)))
      {:page :home})))

(defn route->path
  "Route map → URL path."
  [{:keys [page session-id file task-id dir]}]
  (case page
    :chat (if session-id (str "/chat/" session-id) "/chat")
    :gtd  (cond-> "/gtd"
             file    (str "/" (js/encodeURIComponent file))
             task-id (str "/" task-id))
    ;; :home — use /projects/:cwd when drilling into a directory
    (cond
      (= dir :all) "/projects/all"
      dir          (str "/projects/" (js/encodeURIComponent dir))
      :else        "/")))

;; ── Navigation (pure handler) ────────────────────────────────────────────────

(defn- session->room-id
  "An existing live room hosting this session, if any (so we attach instead
   of resuming a duplicate)."
  [st session-id]
  (some (fn [r] (when (= session-id (:session-id r)) (:id r)))
        (get-in st [:lobby :rooms])))

(defn navigate
  "Set the route; push/replace history; drive the implied room change.
     :page       :home | :chat
     :session-id (chat only)
     :replace?   true for popstate / initial load (no new history entry)"
  [st {:keys [page session-id file task-id dir replace?]}]
  (let [route      (cond-> {:page page :session-id session-id}
                     file    (assoc :file file)
                     task-id (assoc :task-id task-id)
                     dir     (assoc :dir dir))
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
                  (and leaving-empty-new? (not (#{:home :gtd} page)))
                  (conj [:app/dispatch {:type :room/leave}])

                  (and (= page :chat) session-id (not already?))
                  (conj [:app/dispatch
                         ;; Always carry :session-id so the server can resume
                         ;; even when the lobby cache has a stale room-id that
                         ;; no longer exists on a restarted server.
                         (cond-> {:type   :room/join
                                  :target (or (session->room-id st session-id)
                                              {:session-id session-id})}
                           session-id (assoc :session-id session-id))]
                        [:app/dispatch {:type :session/mark-read
                                        :session-id session-id}])

                  (#{:home :gtd} page)
                  (conj [:app/dispatch {:type :room/leave}])

                  ;; Fetch task list when entering GTD without cached data
                  (and (= page :gtd) (empty? (:web/gtd-tasks st)))
                  (conj [:app/dispatch {:type :gtd/web-list}])

                  ;; Fetch sessions when drilling into a project directory
                  (and (= page :home) dir (not= dir :all))
                  (conj [:app/dispatch {:type :projects/web-sessions :cwd dir}]))]
    {:state   (cond-> (assoc st :web/route route
                            ;; reset the virtualized timeline window on every
                            ;; navigation so a new session starts compact
                            :web/timeline-window nil
                            ;; close the recent-sessions drawer on navigation
                            :web/sidebar-open? false)
                ;; Leaving a chat we were viewing: remember the session so the
                ;; next fresh count marks it read (the user saw responses that
                ;; landed while attached, before counts refreshed). See
                ;; counts-result.
                (and (#{:home :gtd} page) active-sid)
                (assoc :web/pending-read active-sid)
                ;; Sync file/task drill-down from the route
                (= page :gtd) (-> (assoc :web/gtd-file file)
                                  (assoc :web/gtd-task-id task-id))
                ;; Sync project dir drill-down from the route
                (= page :home) (-> (assoc :web/selected-project-dir dir)
                                   (cond->
                                     ;; Clear stale sessions when navigating away
                                     (nil? dir) (dissoc :web/project-sessions
                                                        :web/project-sessions-cwd)
                                     ;; Clear old data when drilling into a new dir
                                     (and dir (not= dir :all))
                                     (-> (dissoc :web/project-sessions)
                                         (assoc :web/project-sessions-loading? true)))))
     :effects effects}))

(defn nav-back
  "Pure handler for :nav/back — emits the :nav/back effect."
  [_st {:keys [fallback]}]
  {:effects [[:nav/back {:fallback fallback}]]})

(def handlers
  {:route/navigate navigate
   :nav/back       nav-back})

;; ── History effect + init (impure edge) ──────────────────────────────────────

;; How many pushState entries the app owns. Used by :nav/back to decide
;; whether history.back() has somewhere to go or needs a fallback route.
(defonce nav-depth (atom 0))

(defn history-effect
  "The `:history/push` effect — pushState/replaceState the route's path."
  [_ctx {:keys [route replace?]}]
  (let [path (route->path route)]
    (if replace?
      (.replaceState js/window.history #js {:navDepth @nav-depth} "" path)
      (when (not= path (.-pathname js/window.location))
        (let [d (swap! nav-depth inc)]
          (.pushState js/window.history #js {:navDepth d} "" path))))))

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
  [dispatch!]
  ;; Restore nav-depth from history.state (survives page reloads)
  (when-let [d (some-> js/history.state (.-navDepth))]
    (reset! nav-depth d))
  (let [route->ev (fn [] (assoc (parse-path (.-pathname js/window.location))
                                :type :route/navigate :replace? true))]
    (dispatch! (route->ev))
    (.addEventListener js/window "popstate"
                       (fn [_]
                         (swap! nav-depth #(max 0 (dec %)))
                         (dispatch! (route->ev))))))