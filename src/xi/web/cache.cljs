(ns xi.web.cache
  "LocalStorage offline cache for the web client.

   Backend wins: the cache only fills the gap before the WS connects (and
   when offline). `:room/joined` / `:lobby/state` overwrite it. Stored as
   EDN (the same format the wire uses), so keywords/nesting survive a
   round-trip — no JSON shims.

   Keys:
     xi/lobby            last {:rooms :sessions} for an instant home paint
     xi/room/<sid>       last {:history :model} per session for chat paint
     xi/watched          {session-id response-count-when-last-seen}"
  (:require [cljs.reader :as reader]
            [xi.core.state :as state]))

;; ── Primitives ───────────────────────────────────────────────────────────────

(defn- store-get [k]
  (try
    (when-let [raw (.getItem js/localStorage k)]
      (reader/read-string raw))
    (catch :default _ nil)))

(defn- store-set! [k v]
  (try
    (.setItem js/localStorage k (pr-str v))
    (catch :default e
      (js/console.warn "[cache] write failed:" e))))

(defn- store-remove! [k]
  (try (.removeItem js/localStorage k) (catch :default _ nil)))

;; ── Lobby ────────────────────────────────────────────────────────────────────

(def ^:private lobby-key "xi/lobby")

(defn save-lobby! [lobby] (when lobby (store-set! lobby-key lobby)))
(defn load-lobby [] (store-get lobby-key))

;; ── Per-session room snapshot ────────────────────────────────────────────────

(defn- room-key [session-id] (str "xi/room/" session-id))

(defn save-room!
  "Cache a room's renderable slice (history + model) under its session id."
  [session-id {:keys [history model]}]
  (when (and session-id (seq history))
    (store-set! (room-key session-id) {:history history :model model})))

(defn load-room
  "Cached {:history :model} for a session, or nil."
  [session-id]
  (when session-id (store-get (room-key session-id))))

;; ── Watched sessions (unread) ────────────────────────────────────────────────

(def ^:private watched-key "xi/watched")

(defn load-watched [] (or (store-get watched-key) {}))

(defn watch!
  "Mark a session read at the given response count."
  [session-id response-count]
  (when session-id
    (store-set! watched-key (assoc (load-watched) session-id (or response-count 0)))))

(defn unwatch! [session-id]
  (when session-id
    (store-set! watched-key (dissoc (load-watched) session-id))))

;; ── Hydrate + persist ────────────────────────────────────────────────────────

(defn hydrate
  "Seed initial app state from the cache before the WS connects: cached
   lobby, the deep-linked session's cached history (under :web/cache), and
   the watched map. `route` is the initial route parsed from the URL."
  [base route]
  (let [sid    (:session-id route)
        cached (load-room sid)]
    (cond-> (assoc base :web/route route
                        :web/watched (load-watched))
      (load-lobby) (assoc :lobby (load-lobby))
      cached       (assoc-in [:web/cache sid] cached))))

(def ^:private persist-on
  "Event types after which the cache is worth refreshing."
  #{:lobby/state :room/joined :agent/turn-end :history/append :agent/abort})

(defn persist-tap
  "App tap that mirrors lobby + the active room into the cache. Gated to a
   few event types so we don't write localStorage on every delta."
  [event state]
  (when (persist-on (:type event))
    (when-let [lobby (:lobby state)] (save-lobby! lobby))
    (when-let [room (state/active-room state)]
      (when-let [sid (get-in room [:session :id])]
        (save-room! sid {:history (:history room)
                         :model   (get-in room [:agent :model])})))))
