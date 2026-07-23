(ns xi.web.cache
  "LocalStorage offline cache for the web client.

   Backend wins: the cache only fills the gap before the WS connects (and
   when offline). `:room/joined` / `:lobby/state` overwrite it. Stored as
   EDN (the same format the wire uses), so keywords/nesting survive a
   round-trip — no JSON shims.

   Keys:
     xi/lobby            last {:rooms :sessions} for an instant home paint
     xi/room/<sid>       last {:history :model :msg-hash} per session for chat paint
     xi/room-lru         [sid …] most-recent-first, caps the room snapshots
     xi/watched          {session-id response-count-when-last-seen}"
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            [xi.core.state :as state]))

;; ── Primitives ───────────────────────────────────────────────────────────────

(defn- store-get [k]
  (try
    (when-let [raw (.getItem js/localStorage k)]
      (reader/read-string raw))
    (catch :default _ nil)))

(defn- store-set!
  "Write v under k. Returns true on success, false on failure (e.g. quota)
   so callers can evict and retry."
  [k v]
  (try
    (.setItem js/localStorage k (pr-str v))
    true
    (catch :default e
      (js/console.warn "[cache] write failed:" e)
      false)))

(defn- store-remove! [k]
  (try (.removeItem js/localStorage k) (catch :default _ nil)))

;; ── Lobby ────────────────────────────────────────────────────────────────────

(def ^:private lobby-key "xi/lobby")

;; How many saved sessions to keep in the cached lobby. The real list can be
;; thousands of entries (hundreds of KB) — far more than a first paint needs
;; and enough on its own to blow the localStorage quota, after which *every*
;; write silently fails. A first paint only needs the recent + favorited ones;
;; the live WS :lobby/state restores the full list once connected.
(def ^:private max-cached-sessions 80)

(defn- session-recency [s]
  (or (:last-accessed s) (:timestamp s) ""))

(defn- trim-lobby
  "Shrink the cached lobby's :sessions to favorites + the most-recently-accessed
   N, so the cache stays small. :rooms (live rooms only) is left as-is."
  [lobby]
  (update lobby :sessions
          (fn [sessions]
            (if (<= (count sessions) max-cached-sessions)
              sessions
              (let [favs   (filter :favorite? sessions)
                    recent (->> sessions
                                (sort-by session-recency)
                                reverse
                                (take max-cached-sessions))]
                (vec (distinct (concat favs recent))))))))

(defn save-lobby! [lobby] (when lobby (store-set! lobby-key (trim-lobby lobby))))
(defn load-lobby [] (store-get lobby-key))

;; ── Per-session room snapshot ────────────────────────────────────────────────

(defn- room-key [session-id] (str "xi/room/" session-id))

(def ^:private room-lru-key "xi/room-lru")

;; Cap on how many per-session room snapshots we keep. Each holds a full
;; history (with tool results) and can be hundreds of KB, so an unbounded set
;; blows the ~5MB localStorage quota — after which *every* write (lobby
;; included) throws and the cache silently stops updating. We prune to the N
;; most-recently-saved rooms on every write.
(def ^:private max-cached-rooms 15)

(defn- load-room-lru [] (or (store-get room-lru-key) []))

(defn- room-key->sid [k]
  (when (and k (str/starts-with? k "xi/room/"))
    (subs k (count "xi/room/"))))

(defn- all-cached-sids
  "Session ids of every xi/room/<sid> key in localStorage — including legacy
   keys written before the LRU existed, so they get pruned too."
  []
  (try
    (->> (range (.-length js/localStorage))
         (keep #(room-key->sid (.key js/localStorage %)))
         vec)
    (catch :default _ [])))

(defn- prune-rooms!
  "Remove every xi/room/<sid> key whose sid is not in keep-sids."
  [keep-sids]
  (let [keep (set keep-sids)]
    (doseq [sid (all-cached-sids)]
      (when-not (keep sid) (store-remove! (room-key sid))))))

(defn save-room!
  "Cache a room's renderable slice (history + model) under its session id, then
   prune to the most-recently-saved rooms so the store can't overflow. On a
   quota failure, drop every other cached room and retry with just this one."
  [session-id {:keys [history model msg-hash]}]
  (when (and session-id (seq history))
    (let [payload {:history history :model model :msg-hash msg-hash}
          lru     (->> (load-room-lru)
                       (remove #(= % session-id))
                       (cons session-id)
                       (take max-cached-rooms)
                       vec)]
      (if (store-set! (room-key session-id) payload)
        (do (prune-rooms! lru)
            (store-set! room-lru-key lru))
        ;; Quota hit: this session plus the others won't fit. Evict every other
        ;; room and retry with only this one cached.
        (do (prune-rooms! [session-id])
            (when (store-set! (room-key session-id) payload)
              (store-set! room-lru-key [session-id])))))))

(defn load-room
  "Cached {:history :model :msg-hash} for a session, or nil."
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

;; ── Recently-executed commands (quick-command bar) ───────────────────────────

(def ^:private recent-commands-key "xi/recent-commands")

(defn load-recent-commands [] (or (store-get recent-commands-key) []))

(defn save-recent-commands! [commands]
  (store-set! recent-commands-key (vec commands)))

;; ── Hydrate + persist ────────────────────────────────────────────────────────

(defn hydrate
  "Seed initial app state from the cache before the WS connects: cached
   lobby, the deep-linked session's cached history (under :web/cache), and
   the watched map. `route` is the initial route parsed from the URL."
  [base route]
  (let [sid    (:session-id route)
        cached (load-room sid)]
    (cond-> (assoc base :web/route route
                        :web/watched (load-watched)
                        ;; Frozen for the session: the order the quick-command
                        ;; bar shows. `:web/command-usage` accumulates live
                        ;; recency and is persisted to re-seed both on reload.
                        :web/recent-commands (load-recent-commands)
                        :web/command-usage (load-recent-commands))
      (load-lobby) (assoc :lobby (load-lobby))
      cached       (assoc-in [:web/cache sid] cached))))

(def ^:private persist-on
  "Event types after which the cache is worth refreshing.
   :history/append is intentionally excluded — it fires on every streaming
   delta and would thrash localStorage during long turns. :agent/tool-result
   gives a mid-turn checkpoint; :agent/turn-end persists the final state."
  #{:lobby/state :room/joined :session/resumed
    :agent/turn-end :agent/tool-result :agent/abort})

(defn persist-tap
  "App tap that mirrors lobby + the active room into the cache. Gated to a
   few event types so we don't write localStorage on every delta."
  [event state]
  (when (persist-on (:type event))
    (when-let [lobby (:lobby state)] (save-lobby! lobby))
    (when-let [room (state/active-room state)]
      (when-let [sid (get-in room [:session :id])]
        (save-room! sid {:history  (:history room)
                         :model    (get-in room [:agent :model])
                         :msg-hash (:msg-hash room)})))))
