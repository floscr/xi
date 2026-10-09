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
     xi/watched          {session-id response-count-when-last-seen}
     xi/models           {:models [id …] :at ms} the model picker's last list
                         (xi.web.models)"
  (:require [clojure.string :as str]
            [cognitect.transit :as transit]
            [xi.core.state :as state]
            [xi.user-state :as user-state]))

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
  "Write an already-encoded string under k; false on failure (quota) so callers
   can evict and retry."
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
;; write silently fails. A first paint only needs the recent + flagged ones;
;; the live WS :lobby/state restores the full list once connected.
(def ^:private max-cached-sessions 80)

(defn- session-recency [s]
  (or (:last-accessed s) (:timestamp s) ""))

(defn- trim-lobby
  "Shrink the cached lobby's :sessions to the pinned, extension-flagged and
   most-recently-accessed N."
  [lobby]
  (update lobby :sessions
          (fn [sessions]
            (if (<= (count sessions) max-cached-sessions)
              sessions
              (let [favs   (filter #(or (:pinned? %)
                                        (some true? (vals (user-state/extension-flags %))))
                                   sessions)
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
  "The persisted slice of a room map, always carrying all four keys so a slice
   built on save equals one decoded from storage."
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
   rooms least-recently-saved first until it fits. Returns the surviving LRU,
   or nil when it doesn't fit alone."
  [session-id raw lru]
  (loop [kept (vec lru)]
    (cond
      (store-set-raw! (room-key session-id) raw) kept
      (<= (count kept) 1)                        nil
      :else (do (store-remove! (room-key (peek kept)))
                (recur (pop kept))))))

(defn save-room!
  "Cache a room's renderable slice under its session id, evicting older rooms
   on quota failure (a room too big on its own is remembered and skipped). The
   payload carries :history-hash, which a live-room join echoes so the server
   can elide the history (xi.server.room-manager/joined-payload)."
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
  [session-id response-count]
  (when session-id
    (store-set! watched-key (assoc (load-watched) session-id (or response-count 0)))))

(defn unwatch! [session-id]
  (when session-id
    (store-set! watched-key (dissoc (load-watched) session-id))))

(defn clear-watched!
  "Forget every read marker this browser cached — they were another user's."
  []
  (store-remove! watched-key))

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
  (if (string? model)
    (store-set! preferred-model-key model)
    (store-remove! preferred-model-key)))

;; ── Model picker list ────────────────────────────────────────────────────────
;; Paints the picker without a server round-trip; xi.web.models decides when
;; it is stale.

(def ^:private model-list-key "xi/models")

(defn load-model-list
  "The cached {:models [id …] :at ms}, nil when unset or malformed."
  []
  (let [{:keys [models at] :as v} (store-get model-list-key)]
    (when (and (vector? models) (every? string? models) (number? at))
      v)))

(defn save-model-list! [models at]
  (store-set! model-list-key {:models (vec models) :at at}))

;; ── Whose UI state this is ─────────────────────────────────────────────────────────

(def ^:private cached-user-key "xi/user")

(defn load-cached-user
  "The user id whose UI state this browser cached last, nil before per-user
   state; a different user must not inherit it."
  []
  (store-get cached-user-key))

(defn save-cached-user! [user]
  (when (string? user) (store-set! cached-user-key user)))

;; ── Collapsed sidebar groups ─────────────────────────────────────────────────

(def ^:private sidebar-collapsed-key "xi/sidebar-collapsed")

(defn load-sidebar-collapsed [] (set (store-get sidebar-collapsed-key)))

(defn save-sidebar-collapsed! [groups]
  (store-set! sidebar-collapsed-key (vec groups)))

;; ── Unfolded buffer lists in the sidebar ───────────────────────────────────────
;; This browser's own (not per user): which session cards show their buffer
;; rows (xi.web.views/session-buffer-rows).

(def ^:private sidebar-buffers-open-key "xi/sidebar-buffers-open")

(defn load-sidebar-buffers-open [] (set (store-get sidebar-buffers-open-key)))

(defn save-sidebar-buffers-open! [session-ids]
  (store-set! sidebar-buffers-open-key (vec session-ids)))

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
  "Seed initial app state from the cache before the WS connects: the lobby, the
   deep-linked session's history (:web/cache) and the watched map."
  [base route]
  (let [sid    (:session-id route)
        cached (load-room sid)
        models (load-model-list)]
    (cond-> (assoc base :web/route route
                        :web/watched (load-watched)
                        ;; Frozen for the session: the order the quick-command
                        ;; bar shows. `:web/command-usage` accumulates live
                        ;; recency and is persisted to re-seed both on reload.
                        :web/recent-commands (load-recent-commands)
                        :web/command-usage (load-recent-commands)
                        :web/recent-skills (load-recent-skills)
                        :web/preferred-model (load-preferred-model)
                        :web/model-list (:models models)
                        :web/model-list-at (:at models)
                        :web/sidebar-collapsed (load-sidebar-collapsed)
                        :web/sidebar-buffers-open (load-sidebar-buffers-open)
                        ;; The appearance overrides (xi.web.appearance).
                        :web/appearance (load-appearance)
                        ;; whose UI state the values above are (see load-cached-user)
                        :web/cached-user (load-cached-user))
      (load-lobby) (assoc :lobby (load-lobby))
      cached       (assoc-in [:web/cache sid] cached))))

(def ^:private persist-on
  "Event types after which the cache is refreshed. :history/append is excluded
   (every streaming delta); :agent/tool-result is a mid-turn checkpoint;
   :session/resumed-tail matters because a cache-echoing join never broadcasts
   the full :session/resumed."
  #{:lobby/state :room/joined :session/resumed :session/resumed-tail
    :agent/turn-end :agent/tool-result :agent/abort})

(def ^:private tool-result-persist-interval-ms
  "Min gap between :agent/tool-result checkpoints (save-room! serializes the
   whole history synchronously)."
  5000)

(defonce ^:private last-tool-persist (atom 0))

(defn persist-tap
  "App tap mirroring the lobby + active room into the cache on `persist-on`
   events, tool-result checkpoints rate-limited."
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
