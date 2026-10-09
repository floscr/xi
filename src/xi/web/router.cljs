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

(defn- routes-of
  "The route table: `routes` may be a map or an atom holding one (user
   extensions add routes after the web client started)."
  [routes]
  (if (satisfies? IDeref routes) @routes routes))

(defn parse-path
  "URL path → route map. Extension routes (keyed by first segment) take
   precedence; their :parse fn receives the remaining segments."
  [routes path]
  (let [routes   (routes-of routes)
        segments (filterv seq (str/split (or path "/") #"/"))]
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
                              :else (js/decodeURIComponent seg2))]
                   (cond-> {:page :home}
                     dir (assoc :dir dir)))
      {:page :home}))))

(defn route->path
  "Route map → URL path. Extension :path fns (keyed by page) take
   precedence over the built-ins."
  [routes {:keys [page session-id file task-id dir cwd number] :as route}]
  (if-let [f (some #(get-in % [:path page]) (vals (routes-of routes)))]
    (f route)
    (case page
    :chat (if session-id (str "/chat/" session-id) "/chat")
    :git-status (if cwd (str "/git-status/" (js/encodeURIComponent cwd)) "/git-status")
    ;; :home — use /projects/:cwd when drilling into a directory
    (cond
      (= dir :all) "/projects/all"
      dir          (str "/projects/" (js/encodeURIComponent dir))
      :else        "/"))))

(defn roomless-pages
  "Pages that imply leaving the active room on navigation: the built-ins
   plus every extension route entry's :roomless-pages."
  [routes]
  (into #{:home} (mapcat :roomless-pages) (vals (routes-of routes))))

(def builtin-segments
  #{"chat" "projects" "git-status"})

(defn pending-extension-path?
  "A path whose first segment is neither built in nor in `routes`: most likely
   a user extension's page whose route only exists once the web half has loaded
   (xi.web.user-ext re-dispatches the navigation). Its URL must survive the
   home fallback until then."
  [routes path]
  (let [seg (first (filter seq (str/split (or path "/") #"/")))]
    (boolean (and seg
                  (not (contains? builtin-segments seg))
                  (not (contains? (routes-of routes) seg))))))

;; ── Navigation (pure handler) ────────────────────────────────────────────────

(defn- session->room-id
  "An existing live room hosting this session, if any (so we attach instead
   of resuming a duplicate)."
  [st session-id]
  (some (fn [r] (when (= session-id (:session-id r)) (:id r)))
        (get-in st [:lobby :rooms])))

(defn stash-draft-chat
  "Park a typed-into virtual new chat (:web/pending-room) in :web/draft-chats
   so leaving it doesn't lose the prompt (the text stays in :web/drafts under
   the pending room's id). An empty one is dropped."
  [st]
  (let [{:keys [id] :as pending} (:web/pending-room st)]
    (if (and pending (not (str/blank? (get-in st [:web/drafts id]))))
      (update st :web/draft-chats
              #(conj (filterv (fn [r] (not= id (:id r))) %) pending))
      st)))

;; ── Join pacing ──
;; A join of a session without a live room resumes it on the server (disk
;; read, room setup) and answers with a snapshot, so flicking through the
;; sidebar must not join every session it passes. The cache paints each one
;; at once; the first join of a burst goes out immediately, later ones wait
;; until no navigation came for join-burst-ms and then join only the last.
;; State: :web/join-seq (monotonic), :web/join-burst (seq of the open burst),
;; :web/pending-join ({:target :session-id} waiting for the burst to end).

(def join-burst-ms 150)

;; Timeline entries a switch paints first: enough to fill the viewport, a
;; fraction of the DOM of the full window (xi.web.views/initial-window-size),
;; which the timeline grows back to when the burst ends.
(def first-paint-window 12)

(defn- join-effects [{:keys [target session-id]}]
  [[:room/join-with-cache {:target target :session-id session-id}]
   [:app/dispatch {:type :session/mark-read :session-id session-id}]])

(defn- pace-join
  "Fold `join` ({:target :session-id}, nil when the navigation joins nothing)
   into the navigate result. Any navigation drops a deferred join."
  [{:keys [state] :as result} join]
  (let [st (dissoc state :web/pending-join)]
    (if-not join
      (assoc result :state st)
      (let [burst? (some? (:web/join-burst st))
            n      (inc (:web/join-seq st 0))]
        {:state   (cond-> (assoc st :web/join-seq n :web/join-burst n)
                    burst? (assoc :web/pending-join join))
         :effects (-> (vec (:effects result))
                      (into (when-not burst? (join-effects join)))
                      (conj [:app/dispatch-after
                             {:ms    join-burst-ms
                              :event {:type :room/join-burst-end :seq n}}]))}))))

(defn join-burst-end
  [st {:keys [seq]}]
  (when (= seq (:web/join-burst st))
    (let [join (:web/pending-join st)]
      {:state   (cond-> (dissoc st :web/join-burst :web/pending-join)
                  (= first-paint-window (:web/timeline-window st))
                  (dissoc :web/timeline-window))
       :effects (if join (join-effects join) [])})))

(defn flush-pending-join
  "Send a burst's deferred join now, for an action that needs the room (a
   submit): `result` ({:state :effects}) with the join effects appended."
  [{:keys [state] :as result}]
  (if-let [join (:web/pending-join state)]
    {:state   (dissoc state :web/pending-join)
     :effects (into (vec (:effects result)) (join-effects join))}
    result))

(defn- navigate*
  "Set the route, push/replace history, and drive the implied room change.
   :page :home | :chat, :session-id (chat only), :params (an extension route's
   own), :replace? (popstate / initial load), :keep-url? (a deep link into a
   page whose route is still loading, see pending-extension-path?)."
  [roomless st {:keys [page session-id file task-id dir cwd number params replace? keep-url?]}]
  (let [route      (cond-> {:page page :session-id session-id}
                     file         (assoc :file file)
                     task-id      (assoc :task-id task-id)
                     dir          (assoc :dir dir)
                     cwd          (assoc :cwd cwd)
                     number       (assoc :number number)
                     (map? params) (assoc :params params))
        active-room (state/active-room st)
        active-sid (get-in active-room [:session :id])
        ;; Already viewing this session (e.g. the post-join URL fix) → don't
        ;; re-join or re-mark.
        already?   (and (= page :chat) session-id (= session-id active-sid))
        ;; The room we're leaving is a brand-new session the user never sent
        ;; a message in (no history) and left an empty prompt for. Switching
        ;; straight to another chat would orphan it as an idle "active" room
        ;; in the lobby, so close it on the way out. (The roomless-page branch
        ;; below already leaves the room, so we only need this for chat→chat.)
        leaving-empty-new?
        (and active-room (not already?)
             (empty? (:history active-room))
             (not (get-in active-room [:agent :busy?]))
             (str/blank? (get-in st [:web/drafts (or active-sid :new)])))
        effects (cond-> [[:history/push (cond-> {:route route :replace? replace?}
                                          keep-url? (assoc :keep-url? true))]]
                  ;; Leave (→ server-side close) the empty room BEFORE joining
                  ;; the next one, so the server frees it instead of orphaning
                  ;; it (room/leave acts on the client's current membership).
                  (and leaving-empty-new? (not (roomless page)))
                  (conj [:app/dispatch {:type :room/leave}])

                  ;; The room we leave keeps its live history in the cache's
                  ;; memory tier, so coming back paints it as it was.
                  (and active-sid (not already?))
                  (conj [:cache/remember-room {:session-id active-sid
                                               :room       active-room}])

                  ;; Paint the target's cached history immediately; the join
                  ;; follows via pace-join.
                  (and (= page :chat) session-id (not already?))
                  (conj [:cache/seed-room {:session-id session-id}])

                  (roomless page)
                  (conj [:app/dispatch {:type :room/leave}])

                  ;; Fetch sessions when drilling into a project directory
                  (and (= page :home) dir (not= dir :all))
                  (conj [:app/dispatch {:type :projects/web-sessions :cwd dir}])

                  ;; The all-sessions view needs the full list — the lobby
                  ;; broadcast only carries a capped recent subset.
                  (and (= page :home) (= dir :all))
                  (conj [:app/dispatch {:type :sessions/all}])

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
    (pace-join
     {:state   (cond-> (assoc st :web/route route
                             ;; reset the virtualized timeline window on every
                             ;; navigation so a new session starts compact
                             :web/timeline-window nil)
                 (and (= page :chat) session-id (not already?))
                 (assoc :web/timeline-window first-paint-window)
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
                 ;; session or home): drop its pending-room so its draft
                 ;; can't resurface in another chat — a typed-in one is parked
                 ;; as a sidebar draft first. A fresh virtual chat gets a new
                 ;; pending-room (with a new id) via :room/new.
                 (or (not= page :chat) session-id)
                 (-> stash-draft-chat (dissoc :web/pending-room))
                 ;; Sync the git-status cwd from the route
                 (= page :git-status) (assoc :web/git-status-cwd cwd)
                 ;; Sync project dir drill-down from the route
                 (= page :home) (-> (assoc :web/selected-project-dir dir)
                                    (cond->
                                      ;; Clear stale sessions when navigating away
                                      (nil? dir) (dissoc :web/project-sessions
                                                         :web/project-sessions-cwd)
                                      ;; Leaving the all-sessions view: drop the
                                      ;; full list so it's re-fetched fresh next
                                      ;; time (the capped lobby keeps painting).
                                      (not= dir :all) (dissoc :web/all-sessions)
                                      ;; Clear old data when drilling into a new dir
                                      (and dir (not= dir :all))
                                      (-> (dissoc :web/project-sessions)
                                          (update :web/search dissoc :project-sessions)
                                          (update :web/content-search dissoc :project-sessions)
                                          (update :web/content-matches dissoc :project-sessions)
                                          (assoc :web/project-sessions-loading? true)))))
      :effects effects}
     ;; Join through the cache-aware effect so the client can echo the cached
     ;; msg-hash and let the server skip re-sending unchanged history. Always
     ;; carry :session-id so the server can resume even when the lobby cache
     ;; has a stale room-id that no longer exists on a restarted server.
     (when (and (= page :chat) session-id (not already?))
       {:target     (or (session->room-id st session-id)
                        {:session-id session-id})
        :session-id session-id}))))

(defn nav-back
  [_st {:keys [fallback]}]
  {:effects [[:nav/back {:fallback fallback}]]})

(defn buffer-open-event
  "The client-local event that shows row `buffer-id` of `room`:
   :ui/buffer-switch for a buffer, :subagent/reveal for a background sub-agent
   (they list with the buffers), nil when the room holds neither."
  [room-id room buffer-id]
  (cond
    (get-in room [:ui :buffers buffer-id])
    {:type :ui/buffer-switch :room-id room-id :buffer-id buffer-id}

    (some #(= buffer-id (:id %)) (get-in room [:ext :subagents :agents]))
    {:type :subagent/reveal :room-id room-id :sub-id buffer-id}))

(defn with-buffer
  "Wrap a navigate result to also open buffer `buffer-id` of the target chat:
   switch now when already viewing it, else remember it as :web/pending-buffer
   for the pending-buffer tap (xi.web.core)."
  [st {:keys [page session-id buffer-id]} result]
  (if-not (and buffer-id (= page :chat) session-id)
    result
    (let [active (state/active-room st)]
      (if (= session-id (get-in active [:session :id]))
        (if-let [ev (buffer-open-event (:id active) active buffer-id)]
          (update result :effects (fnil conj []) [:app/dispatch ev])
          result)
        (assoc-in result [:state :web/pending-buffer]
                  {:session-id session-id :buffer-id buffer-id})))))

(defn navigate
  "navigate* (the route, history and room change) plus `:buffer-id`: open that
   buffer of the target chat (with-buffer)."
  [roomless st ev]
  (with-buffer st ev (navigate* roomless st ev)))

(defn handlers
  "Router handler map over the extension route table (a map or an atom —
   read per navigation, so routes added later count)."
  [routes]
  {:route/navigate       (fn [st ev] (navigate (roomless-pages routes) st ev))
   :room/join-burst-end  join-burst-end
   :nav/back             nav-back})

;; ── History effect + init (impure edge) ──────────────────────────────────────

;; How many pushState entries the app owns. Used by :nav/back to decide
;; whether history.back() has somewhere to go or needs a fallback route.
(defonce nav-depth (atom 0))

;; iOS gives a home-screen PWA a native back/forward swipe that walks session
;; history, and no web API reliably disables it (a touchstart preventDefault
;; at the screen edge is best-effort at most, and iOS 26 adds a swipe-back
;; from anywhere in the content). There's no browser back button in
;; standalone mode either, so pushing entries only feeds that gesture: there,
;; every navigation replaces the current entry instead, leaving the swipe
;; nothing to go back to. In-app back buttons use their :nav/back fallback.
(def ^:private no-history?
  (boolean
   (or (and (exists? js/navigator) (true? (.-standalone js/navigator)))
       (and (exists? js/window.matchMedia)
            (.-matches (.matchMedia js/window "(display-mode: standalone)"))))))

(defn history-effect
  "The :history/push effect: pushState/replaceState the route's path; always
   replaces in a standalone PWA (no-history?)."
  [routes]
  (fn [_ctx {:keys [route replace? keep-url?]}]
    (when-not keep-url?
      (let [path (route->path routes route)]
        (if (or replace? no-history?)
          (.replaceState js/window.history #js {:navDepth @nav-depth} "" path)
          (when (not= path (.-pathname js/window.location))
            (let [d (swap! nav-depth inc)]
              (.pushState js/window.history #js {:navDepth d} "" path))))))))

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
  (when-let [d (and (not no-history?) (some-> js/history.state (.-navDepth)))]
    (reset! nav-depth d))
  (let [route->ev (fn []
                    (let [path (.-pathname js/window.location)]
                      (cond-> (assoc (parse-path routes path)
                                     :type :route/navigate :replace? true)
                        ;; an unknown segment falls back to home for now, but
                        ;; the URL stays: a user extension's page is re-routed
                        ;; once its web half has loaded (xi.web.user-ext)
                        (pending-extension-path? routes path) (assoc :keep-url? true))))]
    (dispatch! (route->ev))
    (.addEventListener js/window "popstate"
                       (fn [_]
                         (swap! nav-depth #(max 0 (dec %)))
                         (dispatch! (route->ev))))))
