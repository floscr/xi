(ns xi.web.router
  "History-based routing for the web client, expressed as events over the
   single app atom — no separate router atom.

   Routes:
     /                  → {:page :home}
     /chat/:session-id  → {:page :chat :session-id sid}

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
      "gtd"  {:page :gtd}
      {:page :home})))

(defn route->path
  "Route map → URL path."
  [{:keys [page session-id]}]
  (case page
    :chat (if session-id (str "/chat/" session-id) "/chat")
    :gtd  "/gtd"
    "/"))

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
  [st {:keys [page session-id replace?]}]
  (let [route      {:page page :session-id session-id}
        active-sid (get-in (state/active-room st) [:session :id])
        ;; Already viewing this session (e.g. the post-join URL fix) → don't
        ;; re-join or re-mark.
        already?   (and (= page :chat) session-id (= session-id active-sid))
        effects (cond-> [[:history/push {:route route :replace? replace?}]]
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

                  (= page :gtd)
                  (conj [:app/dispatch {:type :gtd/web-list}]))]
    {:state   (cond-> (assoc st :web/route route
                            ;; reset the virtualized timeline window on every
                            ;; navigation so a new session starts compact
                            :web/timeline-window nil)
                ;; Reset file drill-down when entering GTD
                (= page :gtd) (dissoc :web/gtd-file))
     :effects effects}))

(def handlers
  {:route/navigate navigate})

;; ── History effect + init (impure edge) ──────────────────────────────────────

(defn history-effect
  "The `:history/push` effect — pushState/replaceState the route's path."
  [_ctx {:keys [route replace?]}]
  (let [path (route->path route)]
    (if replace?
      (.replaceState js/window.history nil "" path)
      (when (not= path (.-pathname js/window.location))
        (.pushState js/window.history nil "" path)))))

(defn init!
  "Seed the initial route from the URL and forward popstate as navigate.
   Called once after the app is created."
  [dispatch!]
  (let [route->ev (fn [] (assoc (parse-path (.-pathname js/window.location))
                                :type :route/navigate :replace? true))]
    (dispatch! (route->ev))
    (.addEventListener js/window "popstate" (fn [_] (dispatch! (route->ev))))))