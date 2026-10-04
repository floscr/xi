(ns xi.web.cache
  "LocalStorage offline cache for the web client.

   Backend wins: the cache only fills the gap before the WS connects (and
   when offline). `:room/joined` / `:lobby/state` overwrite it. Serialized
   with transit (the same codec the wire uses), so keywords/nesting survive a
   round-trip while decoding ~40× faster than the EDN reader — the parse cost
   matters because every chat switch reads a full cached history (with tool
   results) synchronously on the main thread, twice, which janked switching on
   mobile when this used cljs.reader.

   Keys:
     xi/lobby            last {:rooms :sessions} for an instant home paint
     xi/room/<sid>       last {:history :model :msg-hash :msg-count :history-hash}
                         per session for chat paint (:history-hash lets a
                         live-room join skip re-sending it, see save-room!)
     xi/room-lru         [sid …] most-recent-first, caps the room snapshots
     xi/watched          {session-id response-count-when-last-seen}"
  (:require [clojure.string :as str]
            [cognitect.transit :as transit]
            [xi.core.state :as state]))

;; ── Primitives ───────────────────────────────────────────────────────────────

;; Reusable transit reader/writer (see xi.wire — they reset their per-message
;; cache each read/write, so one instance is safe to share). Transit decodes a
;; large cached room far faster than cljs.reader, which is the whole point:
;; store-get runs synchronously on the chat-switch path.
(def ^:private writer (transit/writer :json))
(def ^:private reader (transit/reader :json))

(defn- store-get [k]
  (try
    (when-let [raw (.getItem js/localStorage k)]
      (transit/read reader raw))
    (catch :default _ nil)))

(defn- store-set-raw!
  "Write an already-encoded string under k. Returns true on success, false on
   failure (e.g. quota) so callers can evict and retry. Quiet: a quota miss
   is expected while evicting."
  [k raw]
  (try
    (.setItem js/localStorage k raw)
    true
    (catch :default _ false)))

(defn- store-set!
  "Encode v and write it under k. Returns true on success, false on failure."
  [k v]
  (or (store-set-raw! k (transit/write writer v))
      (do (js/console.warn "[cache] write failed:" k) false)))

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

;; The lobby map last written. :lobby/state re-fires the save with the same map
;; whenever any other event ticks the persist tap, so skip identical ones.
(defonce ^:private last-lobby (atom nil))

(defn save-lobby! [lobby]
  (when (and lobby (not (identical? lobby @last-lobby)))
    (when (store-set! lobby-key (trim-lobby lobby))
      (reset! last-lobby lobby))))
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

(defn- room-slice
  "The persisted slice of a room map. Always carries all four keys (nil when
   absent) so a slice built on save equals the one decoded from storage."
  [room]
  {:history   (:history room)
   :model     (:model room)
   :msg-hash  (:msg-hash room)
   :msg-count (:msg-count room)})

;; [session-id payload] of the snapshot last written to (or decoded from)
;; localStorage. save-room! fires on every :lobby/state and :room/joined, which
;; on a chat switch carry the very history we just loaded from the cache;
;; re-encoding hundreds of KB and rewriting it synchronously right as the switch
;; settles is pure jank. The history is compared by identity (O(1)): a cache
;; hit promotes the decoded vector itself into the room, and any real change
;; (append, resume) yields a new vector.
(defonce ^:private last-saved (atom nil))

(defn- unchanged-room? [session-id payload]
  (let [[sid prev] @last-saved]
    (and (= sid session-id)
         (identical? (:history prev) (:history payload))
         (= (dissoc prev :history) (dissoc payload :history)))))

;; Sessions whose snapshot didn't fit even with every other room evicted:
;; {sid history-count-at-failure}. Without this, every later persist (each
;; :lobby/state tick) re-encodes megabytes only to fail again. A shorter
;; history (e.g. after /compact) gets another try.
(defonce ^:private too-big (atom {}))

;; No browser's whole localStorage holds more than this many UTF-16 chars
;; (Chromium: 10 MiB of UTF-16; Firefox and Safari allow less), so a snapshot
;; past it is skipped outright instead of evicting every other room first.
(def ^:private max-room-chars (* 5 1024 1024))

(defn- store-evicting!
  "Write the encoded snapshot `raw` for session-id, evicting the other cached
   rooms least-recently-saved first, one at a time, until it fits. `lru` is
   most-recent-first with session-id at its head. Returns the surviving LRU,
   or nil when it doesn't fit even alone."
  [session-id raw lru]
  (loop [kept (vec lru)]
    (cond
      (store-set-raw! (room-key session-id) raw) kept
      (<= (count kept) 1)                        nil
      :else (do (store-remove! (room-key (peek kept)))
                (recur (pop kept))))))

(defn save-room!
  "Cache a room's renderable slice (history + model) under its session id, then
   prune to the most-recently-saved rooms so the store can't overflow. On a
   quota failure, evict the oldest other rooms until it fits; a room too big
   for the quota on its own is remembered and skipped. A no-op when the slice
   is the one already stored.

   The stored payload also carries :history-hash (cljs `hash` of the history),
   which a live-room join echoes so the server can elide the history it would
   otherwise re-send (xi.server.room-manager/joined-payload)."
  [session-id room]
  (when (and session-id (seq (:history room)))
    (let [payload (room-slice room)
          n       (count (:history payload))]
      (when-not (or (unchanged-room? session-id payload)
                    (when-let [failed-n (get @too-big session-id)] (>= n failed-n)))
        (let [raw (transit/write writer (assoc payload :history-hash (hash (:history payload))))
              lru (->> (load-room-lru)
                       (remove #(= % session-id))
                       (cons session-id)
                       (take max-cached-rooms)
                       vec)]
          ;; Drop strays + rooms past the cap first: on a quota miss that alone
          ;; may free enough.
          (prune-rooms! lru)
          (if-let [kept (when (<= (count raw) max-room-chars)
                          (store-evicting! session-id raw lru))]
            (do (reset! last-saved [session-id payload])
                (swap! too-big dissoc session-id)
                (store-set! room-lru-key kept))
            (do (swap! too-big assoc session-id n)
                (store-set! room-lru-key (vec (rest lru)))
                (js/console.warn "[cache] room snapshot exceeds the storage quota; not caching"
                                 session-id))))))))

;; One-entry memo of the last decoded room: [session-id raw decoded]. A chat
;; switch reads the same snapshot more than once (paint seed + join hash) and a
;; tap prefetches it on pointerdown, so only the first read pays the transit
;; decode. Keyed on the raw string, so a save-room! in between can never serve
;; stale data — the cheap getItem still runs, only the parse is skipped.
(defonce ^:private last-room (atom nil))

(defn load-room
  "Cached {:history :model :msg-hash :msg-count} for a session, or nil."
  [session-id]
  (when session-id
    (try
      (when-let [raw (.getItem js/localStorage (room-key session-id))]
        (let [[sid memo-raw decoded] @last-room]
          (if (and (= sid session-id) (= memo-raw raw))
            decoded
            (let [decoded (transit/read reader raw)]
              (reset! last-room [session-id raw decoded])
              ;; Storage now holds exactly this; see last-saved.
              (reset! last-saved [session-id (room-slice decoded)])
              decoded))))
      (catch :default _ nil))))

(defn prefetch-room!
  "Decode a session's cached snapshot ahead of time (see load-room's memo) so
   the tap that follows doesn't pay for it. Returns nil."
  [session-id]
  (load-room session-id)
  nil)

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

;; ── Recently-used skills (skills palette page) ───────────────────────────────

(def ^:private recent-skills-key "xi/recent-skills")

(defn load-recent-skills [] (or (store-get recent-skills-key) []))

(defn save-recent-skills! [skills]
  (store-set! recent-skills-key (vec skills)))

;; ── Preferred model (default for new chats) ──────────────────────────────────

(def ^:private preferred-model-key "xi/preferred-model")

(defn load-preferred-model [] (store-get preferred-model-key))

(defn save-preferred-model! [model]
  (when (string? model) (store-set! preferred-model-key model)))

;; ── Collapsed sidebar groups ─────────────────────────────────────────────────

(def ^:private sidebar-collapsed-key "xi/sidebar-collapsed")

(defn load-sidebar-collapsed [] (set (store-get sidebar-collapsed-key)))

(defn save-sidebar-collapsed! [groups]
  (store-set! sidebar-collapsed-key (vec groups)))

;; ── Appearance overrides ─────────────────────────────────────────────────────

(def ^:private appearance-key "xi/appearance")

(defn load-appearance
  "The browser's appearance overrides (xi.web.appearance), {} when unset.
   Validated by the consumer (`appearance/normalize`), not here."
  []
  (or (store-get appearance-key) {}))

(defn save-appearance! [settings]
  (if (seq settings)
    (store-set! appearance-key settings)
    (store-remove! appearance-key)))

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
                        :web/command-usage (load-recent-commands)
                        :web/recent-skills (load-recent-skills)
                        :web/preferred-model (load-preferred-model)
                        :web/sidebar-collapsed (load-sidebar-collapsed)
                        ;; This browser's appearance overrides (xi.web.appearance).
                        :web/appearance (load-appearance))
      (load-lobby) (assoc :lobby (load-lobby))
      cached       (assoc-in [:web/cache sid] cached))))

(def ^:private persist-on
  "Event types after which the cache is worth refreshing.
   :history/append is intentionally excluded — it fires on every streaming
   delta and would thrash localStorage during long turns. :agent/tool-result
   gives a mid-turn checkpoint; :agent/turn-end persists the final state.
   :session/resumed-tail matters: on a cache-echoing join the server marks the
   full :session/resumed :no-broadcast? and ships only the tail, so without it
   the merged history never lands back in localStorage and every switch
   repaints the same stale snapshot."
  #{:lobby/state :room/joined :session/resumed :session/resumed-tail
    :agent/turn-end :agent/tool-result :agent/abort})

(def ^:private tool-result-persist-interval-ms
  "Min gap between :agent/tool-result checkpoints. save-room! serializes the
   full history (can be hundreds of KB) synchronously on the main thread, so
   tool-heavy turns would otherwise jank the UI on every result."
  5000)

(defonce ^:private last-tool-persist (atom 0))

(defn persist-tap
  "App tap that mirrors lobby + the active room into the cache. Gated to a
   few event types so we don't write localStorage on every delta; mid-turn
   :agent/tool-result checkpoints are additionally rate-limited."
  [event state]
  (when (persist-on (:type event))
    (when (or (not= :agent/tool-result (:type event))
              (let [now (js/Date.now)]
                (when (> (- now @last-tool-persist) tool-result-persist-interval-ms)
                  (reset! last-tool-persist now)
                  true)))
      (when-let [lobby (:lobby state)] (save-lobby! lobby))
      (when-let [room (state/active-room state)]
        (when-let [sid (get-in room [:session :id])]
          (save-room! sid {:history   (:history room)
                           :model     (get-in room [:agent :model])
                           :msg-hash  (:msg-hash room)
                           :msg-count (:msg-count room)}))))))
